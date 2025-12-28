package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.platform.Platform
import com.projectjuggler.util.ProjectLockUtils
import java.nio.file.Path
import kotlin.io.path.exists

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
    fun waitForShutdown(
        configRepository: ConfigRepository,
        projectPath: ProjectPath,
        timeoutSeconds: Int = 60,
        onProgress: (Int) -> Unit = {}
    ): ShutdownResult {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000L
        var lastProgressUpdate = 0

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            // Check if lock file still exists
            if (!ProjectLockUtils.isProjectOpen(configRepository, projectPath)) {
                return ShutdownResult.Success
            }

            // Report progress every second
            val secondsElapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            if (secondsElapsed > lastProgressUpdate) {
                lastProgressUpdate = secondsElapsed
                onProgress(secondsElapsed)
            }

            // Poll every second
            Thread.sleep(1000)
        }

        // Timeout occurred - check if process is actually still running
        return if (ProjectLockUtils.isProjectOpen(configRepository, projectPath)) {
            // Check if process is really alive or just stale lock file
            if (isProcessActuallyRunning(configRepository, projectPath)) {
                ShutdownResult.Timeout
            } else {
                // Process crashed, clean up stale lock file
                cleanupStaleLock(configRepository, projectPath)
                ShutdownResult.Success
            }
        } else {
            ShutdownResult.Success
        }
    }

    /**
     * Checks if the IntelliJ process for the given project is actually running.
     * This helps detect crashed processes that left stale lock files.
     *
     * @param configRepository Config repository
     * @param projectPath Project path
     * @return true if process is running, false if it crashed or doesn't exist
     */
    private fun isProcessActuallyRunning(
        configRepository: ConfigRepository,
        projectPath: ProjectPath
    ): Boolean {
        return try {
            val configDir = getConfigPath(configRepository, projectPath) ?: return false
            val configPathString = configDir.toString()

            when (Platform.current()) {
                Platform.MACOS, Platform.LINUX -> {
                    // Use ps to check if process with this config path is running
                    val processBuilder = ProcessBuilder("ps", "aux")
                    val process = processBuilder.start()
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor()

                    // Look for process with idea.config.path pointing to this project
                    output.contains(configPathString)
                }
                Platform.WINDOWS -> {
                    // On Windows, this is more complex - for now return true
                    // TODO: Implement Windows process checking if needed
                    true
                }
            }
        } catch (e: Exception) {
            // If we can't check, assume it's running to be safe
            true
        }
    }

    /**
     * Cleans up stale lock file for a crashed process.
     */
    private fun cleanupStaleLock(
        configRepository: ConfigRepository,
        projectPath: ProjectPath
    ) {
        try {
            val configDir = getConfigPath(configRepository, projectPath) ?: return
            val lockFile = configDir.resolve(".lock")
            if (lockFile.exists()) {
                lockFile.toFile().delete()
            }
        } catch (e: Exception) {
            // Best effort cleanup
        }
    }

    /**
     * Gets the config directory path for the project.
     */
    private fun getConfigPath(
        configRepository: ConfigRepository,
        projectPath: ProjectPath
    ): Path? {
        return try {
            val config = configRepository.load()
            val mainProjectPath = config.mainProjectPath

            if (mainProjectPath != null && projectPath.pathString == mainProjectPath) {
                // Main project uses base config
                config.baseConfigPath?.let { Path.of(it) }
            } else {
                // Regular project uses isolated config
                configRepository.baseDir
                    .resolve("projects")
                    .resolve(projectPath.id.id)
                    .resolve("config")
            }
        } catch (e: Exception) {
            null
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
