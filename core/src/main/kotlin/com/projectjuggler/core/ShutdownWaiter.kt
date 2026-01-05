package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.util.ProjectLockUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Waits for an IntelliJ instance to shut down by monitoring its lock file.
 */
object ShutdownWaiter {

    /**
     * Waits for the specified project's IntelliJ instance to shut down.
     *
     * @param configRepository Config repository for accessing project metadata
     * @param projectPath Path to the project
     * @param timeoutSeconds Maximum time to wait in seconds
     * @param onProgress Callback for progress updates (seconds elapsed)
     * @return Result indicating success, timeout, or cancellation
     */
    suspend fun waitForShutdown(
        configRepository: ConfigRepository,
        projectPath: ProjectPath,
        timeoutSeconds: Int = 60,
        onProgress: (Int) -> Unit = {}
    ): ShutdownResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000L
        var lastProgressUpdate = 0

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            // Check if lock file still exists
            if (!ProjectLockUtils.isProjectOpen(configRepository, projectPath)) {
                return@withContext ShutdownResult.Success
            }

            // Report progress every second
            val secondsElapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            if (secondsElapsed > lastProgressUpdate) {
                lastProgressUpdate = secondsElapsed
                onProgress(secondsElapsed)
            }

            // Poll every second
            delay(1000)
        }

        // Timeout occurred - check if process is actually still running
        return@withContext if (ProjectLockUtils.isProjectOpen(configRepository, projectPath)) {
            // Check if process is really alive or just stale lock file
            ShutdownResult.Timeout
        } else {
            ShutdownResult.Success
        }
    }
}

/**
 * Result of waiting for shutdown.
 */
sealed class ShutdownResult {
    /**
     * Shutdown completed successfully.
     */
    object Success : ShutdownResult()

    /**
     * Timeout occurred - process did not shut down in time.
     */
    object Timeout : ShutdownResult()
}
