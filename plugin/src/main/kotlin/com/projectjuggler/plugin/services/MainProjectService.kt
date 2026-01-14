package com.projectjuggler.plugin.services

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath

/**
 * Service for managing the main project configuration.
 * The main project is a special project that uses base VM options directly
 * instead of isolated configurations.
 */
internal object MainProjectService {

    /**
     * Checks if the given project is currently set as the main project.
     */
    fun isMainProject(configRepository: ConfigRepository, projectPath: ProjectPath): Boolean {
        val mainProjectPath = configRepository.load().mainProjectPath ?: return false
        return projectPath.pathString == mainProjectPath
    }

    /**
     * Sets the given project as the main project.
     */
    fun setMainProject(configRepository: ConfigRepository, projectPath: ProjectPath) {
        configRepository.update { config ->
            config.copy(mainProjectPath = projectPath.pathString)
        }
    }

    /**
     * Clears the main project configuration.
     */
    fun clearMainProject(configRepository: ConfigRepository) {
        configRepository.update { config ->
            config.copy(mainProjectPath = null)
        }
    }
}