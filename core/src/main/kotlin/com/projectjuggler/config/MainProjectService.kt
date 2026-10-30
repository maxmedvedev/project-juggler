package com.projectjuggler.config

import com.projectjuggler.core.DirectoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
     *
     * A config dir under `<ideDir>/projects/` always belongs to an isolated instance, so the result is false there.
     * Otherwise the instance is main when `baseConfigPath` is unset or names the same directory as
     * [currentConfigDir] after normalization (real paths when both exist, so symlinks match).
     */
    fun isRunningInMainInstance(ideConfigRepository: IdeConfigRepository, currentConfigDir: Path): Boolean {
        if (DirectoryManager.getInstance(ideConfigRepository).isIsolatedConfigDir(currentConfigDir)) return false
        val baseConfigPath = ideConfigRepository.load().baseConfigPath ?: return true
        return samePath(Paths.get(baseConfigPath), currentConfigDir)
    }

    private fun samePath(a: Path, b: Path): Boolean {
        val normalizedA = a.toAbsolutePath().normalize()
        val normalizedB = b.toAbsolutePath().normalize()
        if (normalizedA == normalizedB) return true
        if (!Files.exists(normalizedA) || !Files.exists(normalizedB)) return false
        return try {
            normalizedA.toRealPath() == normalizedB.toRealPath()
        } catch (e: Throwable) {
            System.err.println("Failed to resolve real paths of $normalizedA and $normalizedB: ${e.message}")
            false
        }
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