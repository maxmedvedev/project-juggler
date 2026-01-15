package com.projectjuggler.util

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ProjectLockUtils {
    /**
     * Checks if a project is currently open (running IntelliJ instance).
     *
     * For regular projects: checks ~/.project-juggler/v2/<ide>/projects/<project-id>/config/.lock
     * For main project: checks the base-config directory's .lock file
     *
     * @param ideConfigRepository The IDE-specific config repository
     * @param projectPath The project to check
     * @return true if project is currently open, false otherwise
     */
    fun isProjectOpen(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Boolean {
        try {
            val config = ideConfigRepository.load()
            val configDir = if (isMainProject(config.mainProjectPath, projectPath)) {
                // Main project uses base-config directly
                config.baseConfigPath?.let { Paths.get(it) } ?: return false
            } else {
                // Regular project uses isolated config
                getIsolatedConfigPath(ideConfigRepository.baseDir, projectPath)
            }

            val lockFile = configDir.resolve(".lock")
            return lockFile.exists() && lockFile.isRegularFile()
        } catch (e: Exception) {
            return false
        }
    }

    private fun isMainProject(mainProjectPath: String?, projectPath: ProjectPath): Boolean {
        mainProjectPath ?: return false
        // Simple comparison - both should be normalized by ProjectPath
        return projectPath.pathString == mainProjectPath
    }

    private fun getIsolatedConfigPath(baseDir: Path, projectPath: ProjectPath): Path {
        return baseDir
            .resolve("projects")
            .resolve(projectPath.id.id)
            .resolve("config")
    }

    /**
     * Reads the process ID (PID) from the project's lock file.
     * IntelliJ stores the PID as plain text in the .lock file.
     *
     * @param ideConfigRepository The IDE-specific config repository
     * @param projectPath The project to check
     * @return The PID as an integer, or null if lock file doesn't exist or can't be read
     */
    fun readPidFromLock(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Int? {
        try {
            val config = ideConfigRepository.load()
            val configDir = if (isMainProject(config.mainProjectPath, projectPath)) {
                config.baseConfigPath?.let { Paths.get(it) } ?: return null
            } else {
                getIsolatedConfigPath(ideConfigRepository.baseDir, projectPath)
            }

            val lockFile = configDir.resolve(".lock")
            if (!lockFile.exists() || !lockFile.isRegularFile()) {
                return null
            }

            val content = lockFile.readText().trim()
            return content.toIntOrNull()
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Checks if a process with the given PID is currently running.
     * Uses platform-specific commands to verify process existence.
     *
     * @param pid The process ID to check
     * @return true if the process is running, false otherwise
     */
    fun isProcessRunning(pid: Int): Boolean {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            val isWindows = osName.contains("win")

            val process = if (isWindows) {
                // Windows: Use tasklist to check if PID exists
                ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/NH")
                    .redirectErrorStream(true)
                    .start()
            } else {
                // Unix-like (macOS, Linux): Use ps to check if PID exists
                ProcessBuilder("ps", "-p", pid.toString())
                    .redirectErrorStream(true)
                    .start()
            }

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}
