package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sun.security.krb5.internal.KDCOptions.with
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Manages shutdown signal files for coordinating IntelliJ instance shutdowns during sync.
 */
class ShutdownSignalManager(private val configRepository: ConfigRepository) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Creates a stop request signal for the specified project.
     *
     * @param project Project to stop
     * @param autoRestart Whether to automatically restart after sync
     * @param syncTypes Types of settings being synced (for logging/diagnostics)
     * @return The created signal
     */
    fun createStopRequest(
        project: ProjectMetadata,
        autoRestart: Boolean,
        syncTypes: List<String>
    ): StopRequestSignal {
        val signal = StopRequestSignal(
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            requestedBy = "system",
            autoRestart = autoRestart,
            syncTypes = syncTypes,
            timeoutSeconds = 60
        )

        val signalsDir = getSignalsDirectory(project)
        Files.createDirectories(signalsDir)

        val signalFile = getSignalFile(project)
        signalFile.writeText(json.encodeToString(signal))

        return signal
    }

    /**
     * Reads the stop request signal for the specified project, if it exists.
     *
     * @param project Project
     * @return The signal, or null if no signal exists or it's invalid
     */
    fun readStopRequest(project: ProjectMetadata): StopRequestSignal? {
        val signalFile = getSignalFile(project)
        if (!signalFile.exists()) return null

        return try {
            json.decodeFromString<StopRequestSignal>(signalFile.readText())
        } catch (e: Exception) {
            // Invalid or corrupted signal file
            null
        }
    }

    private fun getSignalFile(project: ProjectMetadata): Path =
        getSignalsDirectory(project).resolve("stop-request.json")

    /**
     * Cleans up all signal files for the specified project.
     *
     * @param project Project
     */
    fun cleanup(project: ProjectMetadata) {
        val signalsDir = getSignalsDirectory(project)
        if (signalsDir.exists()) {
            signalsDir.toFile().listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Cleans up stale signal files older than the specified age.
     *
     * @param project Project
     * @param maxAgeMinutes Maximum age in minutes
     */
    fun cleanupStaleSignals(project: ProjectMetadata, maxAgeMinutes: Long = 5) {
        val signalsDir = getSignalsDirectory(project)
        if (!signalsDir.exists()) return

        signalsDir.toFile().listFiles()?.forEach { file ->
            try {
                val signal = json.decodeFromString<StopRequestSignal>(file.readText())
                val signalTime = Instant.parse(signal.timestamp)
                val ageMinutes = (Instant.now().toEpochMilli() - signalTime.toEpochMilli()) / 1000 / 60

                if (ageMinutes > maxAgeMinutes) {
                    file.delete()
                }
            } catch (e: Exception) {
                // Corrupted file, delete it
                file.delete()
            }
        }
    }

    /**
     * Gets the signals directory for the specified project ID.
     */
    private fun getSignalsDirectory(project: ProjectMetadata): Path {
        return DirectoryManager.getInstance(configRepository).getProjectRoot(project).resolve("signals")
    }

    /**
     * Attempts to acquire a sync lock for the specified project.
     * This prevents concurrent sync operations on the same project.
     *
     * @param project Project
     * @return A SyncLock if acquired, null if already locked
     */
    suspend fun acquireSyncLock(project: ProjectMetadata): SyncLock? = withContext(Dispatchers.IO) {
        try {
            val signalsDir = getSignalsDirectory(project)
            Files.createDirectories(signalsDir)
            val lockFile = signalsDir.resolve("sync-in-progress.lock")

            val raf = java.io.RandomAccessFile(lockFile.toFile(), "rw")
            val lock = raf.channel.tryLock()

            if (lock != null) {
                return@withContext SyncLock(raf, lock, lockFile)
            } else {
                raf.close()
                return@withContext null
            }
        } catch (e: Exception) {
            return@withContext null
        }
    }
}

/**
 * Signal file format for stop requests.
 */
@Serializable
data class StopRequestSignal(
    val requestId: String,
    val timestamp: String,
    val requestedBy: String,
    val autoRestart: Boolean,
    val syncTypes: List<String>,
    val timeoutSeconds: Int
)

/**
 * Represents a sync lock that prevents concurrent sync operations.
 * Automatically releases the lock when closed.
 */
class SyncLock(
    private val raf: java.io.RandomAccessFile,
    private val lock: FileLock,
    private val lockFile: Path
) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
            raf.close()
            Files.deleteIfExists(lockFile)
        } catch (e: Exception) {
            // Log but don't throw - cleanup is best effort
        }
    }
}
