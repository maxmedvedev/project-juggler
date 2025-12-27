package com.projectjuggler.cli

import com.projectjuggler.cli.framework.*
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.platform.ConfigLocator
import com.projectjuggler.platform.PluginLocator
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

    override fun run() {
        val configRepository = ConfigRepository.create()

        val show = showOpt.getValue()
        val hasAnyOption = intellijPathOpt.getValueOrNull() != null ||
                           baseVmOptionsOpt.getValueOrNull() != null ||
                           basePluginsOpt.getValueOrNull() != null ||
                           baseConfigOpt.getValueOrNull() != null ||
                           mainProjectOpt.getValueOrNull() != null

        when {
            show -> showConfig(configRepository)
            hasAnyOption -> updateConfig(configRepository)
            else -> showConfig(configRepository)
        }
    }

    private fun showConfig(configRepository: ConfigRepository) {
        val config = configRepository.load()

        val detectedPluginsPath = PluginLocator.findDefaultPluginsDirectory()
        val pluginsPathDisplay = config.basePluginsPath
            ?: detectedPluginsPath?.toString()
            ?: "(not set, will auto-detect)"

        val detectedConfigPath = ConfigLocator.findDefaultConfigDirectory()
        val configPathDisplay = config.baseConfigPath
            ?: detectedConfigPath?.toString()
            ?: "(not set, will auto-detect)"

        echo("Current configuration:")
        echo()
        echo("  IntelliJ path:       ${config.intellijPath ?: "(not set, will auto-detect)"}")
        echo("  Base VM options:     ${config.baseVmOptionsPath ?: "(not set)"}")
        echo("  Base config:         $configPathDisplay")
        echo("  Base plugins:        $pluginsPathDisplay")
        echo("  Main project:        ${config.mainProjectPath ?: "(not set)"}")
        echo("  Max recent projects: ${config.maxRecentProjects}")
        echo()
        echo("Configuration file: ${Paths.get(System.getProperty("user.home"), ".project-juggler", "config.json")}")
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
}
