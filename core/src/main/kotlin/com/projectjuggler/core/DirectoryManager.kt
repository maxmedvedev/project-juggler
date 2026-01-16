package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.locators.ConfigLocator
import com.projectjuggler.locators.PluginLocator
import com.projectjuggler.util.DirectoryCopier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class DirectoryManager(private val configRepository: IdeConfigRepository) {

    fun ensureProjectDirectories(project: ProjectMetadata): ProjectDirectories {
        val root = getProjectRoot(project)

        val directories = ProjectDirectories(
            root = root,
            config = root.resolve("config"),
            system = root.resolve("system"),
            logs = root.resolve("logs"),
            plugins = root.resolve("plugins")
        )

        // Create all directories
        Files.createDirectories(directories.config)
        Files.createDirectories(directories.system)
        Files.createDirectories(directories.logs)
        Files.createDirectories(directories.plugins)

        // Copy base config on first open (plugins subdirectory is excluded)
        copyBaseConfigIfNeeded(directories.config)

        // Copy base plugins to separate plugins directory on first open
        copyBasePluginsIfNeeded(directories.plugins)

        return directories
    }

    /**
     * Copy plugins from base location if configured and this is first open.
     */
    private fun copyBasePluginsIfNeeded(pluginsDir: Path) {
        val basePluginsPath = getBasePluginsPath() ?: return
        DirectoryCopier.copyIfFirstOpen(basePluginsPath, pluginsDir)
    }

    /**
     * Get the base plugins directory from config or auto-detect.
     */
    fun getBasePluginsPath(): Path? {
        val config = configRepository.load()

        if (config.basePluginsPath != null) {
            return Paths.get(config.basePluginsPath)
        }

        // Otherwise, try to auto-detect
        return PluginLocator.findDefaultPluginsDirectory()
    }

    /**
     * Copy config files from base location if configured and this is first open.
     */
    private fun copyBaseConfigIfNeeded(configDir: Path) {
        val baseConfigPath = getBaseConfigPath() ?: return
        DirectoryCopier.copyIfFirstOpen(
            baseConfigPath,
            configDir,
            EXCLUDED_DIRECTORIES,
            EXCLUDED_FILES,
            EXCLUDED_PATTERNS,
        )
    }

    /**
     * Get the base config directory from config or auto-detect.
     */
    fun getBaseConfigPath(): Path? {
        val config = configRepository.load()

        // Use configured path if available
        if (config.baseConfigPath != null) {
            return Paths.get(config.baseConfigPath)
        }

        // Otherwise, try to auto-detect
        return ConfigLocator.findDefaultConfigDirectory()
    }

    /**
     * Force sync config from base location, overwriting existing config.
     * Note: plugins subdirectory is excluded (plugins are stored separately).
     */
    fun syncConfigFromBase(project: ProjectMetadata) {
        val baseConfigPath = getBaseConfigPath() ?: throw IllegalStateException(
            "Base config path not found. Either configure it using 'project-juggler config --base-config <path>' or ensure IntelliJ is installed with default paths."
        )
        val projectDirs = ensureProjectDirectories(project)
        DirectoryCopier.copy(baseConfigPath, projectDirs.config, EXCLUDED_DIRECTORIES, EXCLUDED_FILES, EXCLUDED_PATTERNS)
    }

    /**
     * Force sync plugins from base location, overwriting existing plugins
     */
    fun syncPluginsFromBase(project: ProjectMetadata) {
        val basePluginsPath = getBasePluginsPath() ?: throw IllegalStateException(
            "Base plugins path not found. Either configure it using 'project-juggler config --base-plugins <path>' or ensure IntelliJ is installed with default paths."
        )
        val projectDirs = ensureProjectDirectories(project)
        DirectoryCopier.copy(basePluginsPath, projectDirs.plugins)
    }

    fun cleanProject(project: ProjectMetadata) {
        val projectRoot = getProjectRoot(project)
        if (!Files.exists(projectRoot)) return

        @OptIn(ExperimentalPathApi::class)
        projectRoot.deleteRecursively()
    }

    fun getProjectRoot(project: ProjectMetadata): Path {
        return getProjectRoot(project.path)
    }

    fun getProjectRoot(project: ProjectPath): Path {
        return configRepository.baseDir.resolve("projects").resolve(project.id.id)
    }

    companion object {
        fun getInstance(configRepository: IdeConfigRepository) =
            DirectoryManager(configRepository)
    }
}

// Files to exclude when copying IntelliJ config directories
private val EXCLUDED_FILES = setOf(
    ".lock",                           // Running instance lock
    "recentProjects.xml",              // Legacy recent projects
    "recentProjectDirectories.xml",    // Legacy recent directories
)


// Path patterns to exclude (relative to source, normalized with forward slashes)
private val EXCLUDED_PATTERNS = setOf(
    "options/recentProjects.xml",      // Modern recent projects location
)

// Directory names to exclude (plugins are stored separately in project-juggler)
private val EXCLUDED_DIRECTORIES = setOf(
    "plugins"
)

