package com.projectjuggler.util

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ProjectLockUtils {
    /**
     * Checks if a project is currently open (running IntelliJ instance).
     *
     * For regular projects: checks ~/.project-juggler/v2/<ide>/projects/<project-id>/config/.lock
     * For main project: checks the `.lock` in the base config directory (configured or auto-detected)
     *
     * The lock must exist and, when it holds a PID, that process must be alive.
     *
     * @param ideConfigRepository The IDE-specific config repository
     * @param projectPath The project to check
     * @return true if project is currently open, false otherwise
     */
    fun isProjectOpen(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Boolean {
        return try {
            val lockFile = lockFile(ideConfigRepository, projectPath) ?: return false
            isLockLive(lockFile)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks whether an isolated IntelliJ instance of the project is running, regardless of
     * whether the project is currently the main project. Used to detect a still-running isolated
     * instance of a project that has since been made the main project.
     */
    fun isIsolatedInstanceOpen(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Boolean {
        return try {
            isLockLive(isolatedLockFile(ideConfigRepository, projectPath))
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Whether any project of the repository has an isolated `config/.lock` file, live or not. Used before
     * moving an ide dir, where even a stale lock is a reason to leave it alone.
     */
    fun hasIsolatedInstanceLock(ideConfigRepository: IdeConfigRepository): Boolean {
        val projectsDir = ideConfigRepository.baseDir.resolve("projects")
        if (!projectsDir.isDirectory()) return false
        return Files.list(projectsDir).use { stream ->
            stream.anyMatch { it.resolve("config").resolve(LOCK_FILE).exists() }
        }
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
        return try {
            val lockFile = lockFile(ideConfigRepository, projectPath) ?: return null
            readPid(lockFile)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Checks if a process with the given PID is currently running, using [ProcessHandle].
     *
     * @param pid The process ID to check
     * @return true if the process is running, false otherwise
     */
    fun isProcessRunning(pid: Int): Boolean {
        return try {
            ProcessHandle.of(pid.toLong()).map { it.isAlive }.orElse(false)
        } catch (e: Throwable) {
            false
        }
    }

    private fun lockFile(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Path? {
        if (!MainProjectService.isMainProject(ideConfigRepository, projectPath)) {
            return isolatedLockFile(ideConfigRepository, projectPath)
        }
        return DirectoryManager.getInstance(ideConfigRepository).getBaseConfigPath()?.resolve(LOCK_FILE)
    }

    private fun isolatedLockFile(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Path =
        DirectoryManager.getInstance(ideConfigRepository)
            .getProjectRoot(projectPath)
            .resolve("config")
            .resolve(LOCK_FILE)

    /**
     * A lock is live when it exists and, if it holds a PID, that process is alive.
     * Locks with other content fall back to an existence check.
     */
    private fun isLockLive(lockFile: Path): Boolean {
        if (!lockFile.isRegularFile()) return false
        val pid = readPid(lockFile) ?: return true
        return isProcessRunning(pid)
    }

    private fun readPid(lockFile: Path): Int? {
        if (!lockFile.isRegularFile()) return null
        return lockFile.readText().trim().toIntOrNull()
    }

    private const val LOCK_FILE = ".lock"
}
