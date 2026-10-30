package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.di.getScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
class ShutdownSignalManager internal constructor(private val configRepository: IdeConfigRepository) {

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
        getSignalsDirectory(project).resolve(STOP_REQUEST_FILE_NAME)

    /**
     * Cleans up all signal files for the specified project.
     * The sync lock file is permanent and is never deleted.
     *
     * @param project Project
     */
    fun cleanup(project: ProjectMetadata) {
        forEachSignalFile(project) { Files.deleteIfExists(it) }
    }

    /**
     * Cleans up stale signal files older than the specified age, and any file that is not a valid stop request.
     * The sync lock file is permanent and is never deleted.
     *
     * @param project Project
     * @param maxAgeMinutes Maximum age in minutes
     */
    fun cleanupStaleSignals(project: ProjectMetadata, maxAgeMinutes: Long = 5) {
        forEachSignalFile(project) { file ->
            try {
                val signal = json.decodeFromString<StopRequestSignal>(file.readText())
                val signalTime = Instant.parse(signal.timestamp)
                val ageMinutes = (Instant.now().toEpochMilli() - signalTime.toEpochMilli()) / 1000 / 60

                if (ageMinutes > maxAgeMinutes) {
                    Files.deleteIfExists(file)
                }
            } catch (e: Throwable) {
                System.err.println("[ShutdownSignalManager] Deleting unreadable signal file $file: $e")
                Files.deleteIfExists(file)
            }
        }
    }

    /**
     * Runs [action] on every file in the signals directory except the sync lock file.
     */
    private fun forEachSignalFile(project: ProjectMetadata, action: (Path) -> Unit) {
        val signalsDir = getSignalsDirectory(project)
        if (!signalsDir.exists()) return

        Files.list(signalsDir).use { files ->
            files.filter { !it.isSyncLock() }.forEach(action)
        }
    }

    private fun Path.isSyncLock() = fileName.toString() == SYNC_LOCK_FILE_NAME

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
    fun acquireSyncLock(project: ProjectMetadata): SyncLock? {
        val signalsDir = getSignalsDirectory(project)
        Files.createDirectories(signalsDir)
        val lockFile = signalsDir.resolve(SYNC_LOCK_FILE_NAME)

        return try {
            val raf = java.io.RandomAccessFile(lockFile.toFile(), "rw")
            val lock = raf.channel.tryLock()

            if (lock != null) {
                SyncLock(raf, lock)
            } else {
                raf.close()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val SYNC_LOCK_FILE_NAME = "sync-in-progress.lock"
        const val STOP_REQUEST_FILE_NAME = "stop-request.json"

        fun getInstance(ideConfigRepository: IdeConfigRepository): ShutdownSignalManager =
            ideConfigRepository.getScope().get()
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
 * Automatically releases the lock when closed. The lock file itself is permanent: deleting it while
 * another process holds or is acquiring the lock would let two processes lock different inodes.
 */
class SyncLock(
    private val raf: java.io.RandomAccessFile,
    private val lock: FileLock
) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
            raf.close()
        } catch (e: Exception) {
            // Log but don't throw - cleanup is best effort
        }
    }
}
