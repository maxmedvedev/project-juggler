package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.di.getScope
import com.projectjuggler.util.FileLockUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Outcome of a sync the sync-helper ran for an IDE from outside, read by the relaunched (or still running) IDE.
 *
 * @param timestamp When the helper finished, ISO-8601
 * @param syncTypes The synced parts, [SyncTypeNames] values as in the stop request
 * @param succeeded Whether the sync itself succeeded
 * @param message The failure message when [succeeded] is false
 * @param restartError The last error reported while relaunching the IDE, if any
 */
@Serializable
data class SelfSyncResult(
    val timestamp: String,
    val syncTypes: List<String>,
    val succeeded: Boolean,
    val message: String? = null,
    val restartError: String? = null,
)

/**
 * Stores one [SelfSyncResult] per project in `<projRoot>/self-sync-result.json`. The file lives outside
 * `signals/` because [ShutdownSignalManager.cleanup] empties that dir at the end of every sync.
 */
class SelfSyncResultStore internal constructor(private val configRepository: IdeConfigRepository) {

    /** Writes [result] for [project], replacing an earlier unconsumed one. */
    fun write(project: ProjectMetadata, result: SelfSyncResult) {
        val file = getResultFile(project)
        FileLockUtils.withFileLock(file) {
            file.writeText(ShutdownSignalManager.json.encodeToString(result))
        }
    }

    /**
     * Reads and deletes the result for [project]. Returns null when there is none, and also for an empty or
     * corrupt file, which is deleted as well.
     */
    fun consume(project: ProjectMetadata): SelfSyncResult? {
        val file = getResultFile(project)
        if (!file.exists()) return null

        return FileLockUtils.withFileLock(file) {
            val text = file.readText()
            Files.deleteIfExists(file)
            if (text.isBlank()) return@withFileLock null
            try {
                ShutdownSignalManager.json.decodeFromString<SelfSyncResult>(text)
            } catch (e: Throwable) {
                System.err.println("[SelfSyncResultStore] Ignoring corrupt $file: $e")
                null
            }
        }
    }

    internal fun getResultFile(project: ProjectMetadata): Path =
        DirectoryManager.getInstance(configRepository).getProjectRoot(project).resolve(RESULT_FILE_NAME)

    companion object {
        const val RESULT_FILE_NAME = "self-sync-result.json"

        fun getInstance(ideConfigRepository: IdeConfigRepository): SelfSyncResultStore =
            ideConfigRepository.getScope().get()
    }
}
