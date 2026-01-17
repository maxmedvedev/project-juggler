package com.projectjuggler.config

/**
 * Service for managing the main project configuration.
 * The main project is a special project that uses base VM options directly
 * instead of isolated configurations.
 */
object MainProjectService {

    /**
     * Checks if the given project is currently set as the main project.
     */
    fun isMainProject(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Boolean {
        val mainProjectPath = ideConfigRepository.load().mainProjectPath ?: return false
        return projectPath.pathString == mainProjectPath
    }

    /**
     * Sets the given project as the main project.
     */
    fun setMainProject(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath) {
        ideConfigRepository.update { config ->
            config.copy(mainProjectPath = projectPath.pathString)
        }
    }

    /**
     * Clears the main project configuration.
     */
    fun clearMainProject(ideConfigRepository: IdeConfigRepository) {
        ideConfigRepository.update { config ->
            config.copy(mainProjectPath = null)
        }
    }
}