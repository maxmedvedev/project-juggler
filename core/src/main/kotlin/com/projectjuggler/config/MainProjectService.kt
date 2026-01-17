package com.projectjuggler.config

import java.nio.file.Path

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

    /**
     * Checks if the current IDE instance is running as the main instance.
     * Compares the current config directory with the stored base config path.
     */
    fun isRunningInMainInstance(ideConfigRepository: IdeConfigRepository, currentConfigDir: Path): Boolean {
        val baseConfigPath = ideConfigRepository.load().baseConfigPath ?: return true // assume main if not configured
        return currentConfigDir.toString() == baseConfigPath
    }

    /**
     * Returns true if the main project prompt should be shown.
     * Conditions: no main project is set AND user hasn't disabled the prompt AND running in main instance.
     */
    fun shouldPromptForMainProject(ideConfigRepository: IdeConfigRepository, currentConfigDir: Path): Boolean {
        val config = ideConfigRepository.load()
        return config.mainProjectPath == null
            && !config.dontAskAboutMainProject
            && isRunningInMainInstance(ideConfigRepository, currentConfigDir)
    }

    /**
     * Sets the "don't ask about main project" preference.
     */
    fun setDontAskAboutMainProject(ideConfigRepository: IdeConfigRepository, value: Boolean) {
        ideConfigRepository.update { config ->
            config.copy(dontAskAboutMainProject = value)
        }
    }
}