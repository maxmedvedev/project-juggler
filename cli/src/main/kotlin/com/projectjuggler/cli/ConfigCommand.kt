package com.projectjuggler.cli

import com.projectjuggler.cli.framework.*
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.platform.ConfigLocator
import com.projectjuggler.platform.IntelliJLocator
import com.projectjuggler.platform.PluginLocator
import com.projectjuggler.platform.VMOptionsLocator
import com.projectjuggler.util.PathUtils.expandTilde
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.exists

class ConfigCommand : Command(
    name = "config",
    help = "Configure global settings"
) {
    private val intellijPathOpt = StringOption(
        shortName = null,
        longName = "intellij-path",
        help = "Path to IntelliJ executable"
    ).also { options.add(it) }

    private val baseVmOptionsOpt = StringOption(
        shortName = null,
        longName = "base-vmoptions",
        help = "Path to base VM options file"
    ).also { options.add(it) }

    private val basePluginsOpt = StringOption(
        shortName = null,
        longName = "base-plugins",
        help = "Path to base IntelliJ plugins directory"
    ).also { options.add(it) }

    private val baseConfigOpt = StringOption(
        shortName = null,
        longName = "base-config",
        help = "Path to base IntelliJ config directory"
    ).also { options.add(it) }

    private val mainProjectOpt = StringOption(
        shortName = null,
        longName = "main-project",
        help = "Path to main project (for quick access via 'project-juggler main')"
    ).also { options.add(it) }

    private val showOpt = FlagOption(
        shortName = null,
        longName = "show",
        help = "Show current configuration"
    ).also { options.add(it) }

    private val autoDetectOpt = FlagOption(
        shortName = null,
        longName = "auto-detect",
        help = "Auto-detect and save IntelliJ path and base VM options"
    ).also { options.add(it) }

    override fun run() {
        val configRepository = ConfigRepository.create()

        val show = showOpt.getValue()
        val autoDetect = autoDetectOpt.getValue()
        val hasAnyOption = intellijPathOpt.getValueOrNull() != null ||
                           baseVmOptionsOpt.getValueOrNull() != null ||
                           basePluginsOpt.getValueOrNull() != null ||
                           baseConfigOpt.getValueOrNull() != null ||
                           mainProjectOpt.getValueOrNull() != null

        when {
            autoDetect -> autoDetectAndSave(configRepository)
            show -> showConfig(configRepository)
            hasAnyOption -> updateConfig(configRepository)
            else -> showConfig(configRepository)
        }
    }

    private fun showConfig(configRepository: ConfigRepository) {
        val config = configRepository.load()

        // Detect paths that aren't configured
        val detectedIntelliJPath = IntelliJLocator.findIntelliJ()
        val intellijPathDisplay = config.intellijPath
            ?: detectedIntelliJPath?.toString()?.let { "$it (detected, not saved)" }
            ?: "(not set)"

        val detectedVmOptions = VMOptionsLocator.findDefaultVMOptions()
        val vmOptionsDisplay = config.baseVmOptionsPath
            ?: detectedVmOptions?.toString()?.let { "$it (detected, not saved)" }
            ?: "(not set)"

        val detectedPluginsPath = PluginLocator.findDefaultPluginsDirectory()
        val pluginsPathDisplay = config.basePluginsPath
            ?: detectedPluginsPath?.toString()?.let { "$it (detected, not saved)" }
            ?: "(not set)"

        val detectedConfigPath = ConfigLocator.findDefaultConfigDirectory()
        val configPathDisplay = config.baseConfigPath
            ?: detectedConfigPath?.toString()?.let { "$it (detected, not saved)" }
            ?: "(not set)"

        echo("Current configuration:")
        echo()
        echo("  IntelliJ path:       $intellijPathDisplay")
        echo("  Base VM options:     $vmOptionsDisplay")
        echo("  Base config:         $configPathDisplay")
        echo("  Base plugins:        $pluginsPathDisplay")
        echo("  Main project:        ${config.mainProjectPath ?: "(not set)"}")
        echo("  Max recent projects: ${config.maxRecentProjects}")
        echo()
        echo("Configuration file: ${Paths.get(System.getProperty("user.home"), ".project-juggler", "config.json")}")

        // Show tip if settings could be auto-detected but aren't saved
        if (config.intellijPath == null && detectedIntelliJPath != null ||
            config.baseVmOptionsPath == null && detectedVmOptions != null ||
            config.baseConfigPath == null && detectedConfigPath != null ||
            config.basePluginsPath == null && detectedPluginsPath != null) {
            echo()
            echo("Tip: Run 'project-juggler config --auto-detect' to save detected settings.")
        }
    }

    private fun updateConfig(configRepository: ConfigRepository) {
        // Validate paths before updating
        intellijPathOpt.getValueOrNull()?.let { path ->
            if (!expandTilde(Path(path)).exists()) {
                echo("Error: IntelliJ path does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        baseVmOptionsOpt.getValueOrNull()?.let { path ->
            if (!expandTilde(Path(path)).exists()) {
                echo("Error: Base VM options file does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        basePluginsOpt.getValueOrNull()?.let { path ->
            if (!expandTilde(Path(path)).exists()) {
                echo("Error: Base plugins path does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        baseConfigOpt.getValueOrNull()?.let { path ->
            if (!expandTilde(Path(path)).exists()) {
                echo("Error: Base config path does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        mainProjectOpt.getValueOrNull()?.let { path ->
            val expandedPath = expandTilde(Path(path))
            if (!expandedPath.exists()) {
                echo("Error: Main project path does not exist: $path", err = true)
                throw ExitException(1)
            }
            if (!Files.isDirectory(expandedPath)) {
                echo("Error: Main project path is not a directory: $path", err = true)
                throw ExitException(1)
            }
        }

        configRepository.update { config ->
            var updated = config

            intellijPathOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(intellijPath = expandTilde(Path(path)).toString())
                echo("IntelliJ path updated: $path")
            }

            baseVmOptionsOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(baseVmOptionsPath = expandTilde(Path(path)).toString())
                echo("Base VM options path updated: $path")
                BaseVMOptionsTracker.getInstance(configRepository).updateHash()
                echo("Base VM options hash calculated and stored")
            }

            basePluginsOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(basePluginsPath = expandTilde(Path(path)).toString())
                echo("Base plugins path updated: $path")
            }

            baseConfigOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(baseConfigPath = expandTilde(Path(path)).toString())
                echo("Base config path updated: $path")
            }

            mainProjectOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(mainProjectPath = expandTilde(Path(path)).toString())
                echo("Main project path updated: $path")
            }

            updated
        }

        echo()
        echo("Configuration updated successfully.")
    }

    private fun autoDetectAndSave(configRepository: ConfigRepository) {
        echo("Auto-detecting settings...")
        echo()

        // Detect IntelliJ path
        val intellijPath = IntelliJLocator.findIntelliJ()
        if (intellijPath != null) {
            echo("\u2713 IntelliJ IDEA found: $intellijPath")
        } else {
            echo("\u2717 IntelliJ IDEA not found in standard locations", err = true)
        }

        // Detect base VM options
        val vmOptionsPath = VMOptionsLocator.findDefaultVMOptions()
        if (vmOptionsPath != null) {
            echo("\u2713 Base VM options found: $vmOptionsPath")
        } else {
            echo("\u2717 Base VM options file not found in standard locations", err = true)
        }

        // Detect base config directory
        val configPath = ConfigLocator.findDefaultConfigDirectory()
        if (configPath != null) {
            echo("\u2713 Base config directory found: $configPath")
        } else {
            echo("\u2717 Base config directory not found in standard locations", err = true)
        }

        // Detect base plugins directory
        val pluginsPath = PluginLocator.findDefaultPluginsDirectory()
        if (pluginsPath != null) {
            echo("\u2713 Base plugins directory found: $pluginsPath")
        } else {
            echo("\u2717 Base plugins directory not found in standard locations", err = true)
        }

        // Check if anything was found
        if (intellijPath == null && vmOptionsPath == null && configPath == null && pluginsPath == null) {
            echo()
            echo("No settings could be auto-detected.", err = true)
            echo("Please configure manually using:", err = true)
            echo("  project-juggler config --intellij-path <path>", err = true)
            echo("  project-juggler config --base-vmoptions <path>", err = true)
            echo("  project-juggler config --base-config <path>", err = true)
            echo("  project-juggler config --base-plugins <path>", err = true)
            throw ExitException(1)
        }

        echo()
        echo("Saving detected settings to config...")

        // Update config with detected values
        configRepository.update { config ->
            var updated = config

            if (intellijPath != null) {
                updated = updated.copy(intellijPath = intellijPath.toString())
            }

            if (vmOptionsPath != null) {
                updated = updated.copy(baseVmOptionsPath = vmOptionsPath.toString())
                // Calculate and store hash for the vmoptions file
                BaseVMOptionsTracker.getInstance(configRepository).updateHash()
            }

            if (configPath != null) {
                updated = updated.copy(baseConfigPath = configPath.toString())
            }

            if (pluginsPath != null) {
                updated = updated.copy(basePluginsPath = pluginsPath.toString())
            }

            updated
        }

        echo()
        echo("Settings saved successfully.")
    }
}
