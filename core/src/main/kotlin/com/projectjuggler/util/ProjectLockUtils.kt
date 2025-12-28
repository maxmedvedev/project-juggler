package com.projectjuggler.util

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

object ProjectLockUtils {
    /**
     * Checks if a project is currently open (running IntelliJ instance).
     *
     * For regular projects: checks ~/.project-juggler/projects/<project-id>/config/.lock
     * For main project: checks the base-config directory's .lock file
     *
     * @param configRepository The config repository
     * @param projectPath The project to check
     * @return true if project is currently open, false otherwise
     */
    fun isProjectOpen(configRepository: ConfigRepository, projectPath: ProjectPath): Boolean {
        try {
            val configDir = if (isMainProject(configRepository, projectPath)) {
                // Main project uses base-config directly
                getBaseConfigPath(configRepository) ?: return false
            } else {
                // Regular project uses isolated config
                getIsolatedConfigPath(configRepository, projectPath)
            }

            val lockFile = configDir.resolve(".lock")
            return lockFile.exists() && lockFile.isRegularFile()
        } catch (e: Exception) {
            return false
        }
    }

    private fun isMainProject(configRepository: ConfigRepository, projectPath: ProjectPath): Boolean {
        val config = configRepository.load()
        val mainProjectPath = config.mainProjectPath ?: return false
        // Simple comparison - both should be normalized by ProjectPath
        return projectPath.pathString == mainProjectPath
    }

    private fun getBaseConfigPath(configRepository: ConfigRepository): Path? {
        val config = configRepository.load()
        return config.baseConfigPath?.let { Paths.get(it) }
    }

    private fun getIsolatedConfigPath(configRepository: ConfigRepository, projectPath: ProjectPath): Path {
        return configRepository.baseDir
            .resolve("projects")
            .resolve(projectPath.id.id)
            .resolve("config")
    }
}
