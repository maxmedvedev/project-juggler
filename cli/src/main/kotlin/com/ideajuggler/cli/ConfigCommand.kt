package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.BaseVMOptionsTracker
import com.ideajuggler.platform.ConfigLocator
import com.ideajuggler.platform.PluginLocator
import java.nio.file.Paths
import kotlin.io.path.exists

class ConfigCommand : Command(
    name = "config",
    help = "Configure global settings"
) {
    private val intellijPathOpt = PathOption(
        shortName = null,
        longName = "intellij-path",
        help = "Path to IntelliJ executable"
    ).also { options.add(it) }

    private val baseVmOptionsOpt = PathOption(
        shortName = null,
        longName = "base-vmoptions",
        help = "Path to base VM options file"
    ).also { options.add(it) }

    private val basePluginsOpt = PathOption(
        shortName = null,
        longName = "base-plugins",
        help = "Path to base IntelliJ plugins directory"
    ).also { options.add(it) }

    private val baseConfigOpt = PathOption(
        shortName = null,
        longName = "base-config",
        help = "Path to base IntelliJ config directory"
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
                           baseConfigOpt.getValueOrNull() != null

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
        echo("  Max recent projects: ${config.maxRecentProjects}")
        echo()
        echo("Configuration file: ${Paths.get(System.getProperty("user.home"), ".idea-juggler", "config.json")}")
    }

    private fun updateConfig(configRepository: ConfigRepository) {
        // Validate paths before updating
        intellijPathOpt.getValueOrNull()?.let { path ->
            if (!path.exists()) {
                echo("Error: IntelliJ path does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        baseVmOptionsOpt.getValueOrNull()?.let { path ->
            if (!path.exists()) {
                echo("Error: Base VM options file does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        basePluginsOpt.getValueOrNull()?.let { path ->
            if (!path.exists()) {
                echo("Error: Base plugins path does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        baseConfigOpt.getValueOrNull()?.let { path ->
            if (!path.exists()) {
                echo("Error: Base config path does not exist: $path", err = true)
                throw ExitException(1)
            }
        }

        configRepository.update { config ->
            var updated = config

            intellijPathOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(intellijPath = path.toString())
                echo("IntelliJ path updated: $path")
            }

            baseVmOptionsOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(baseVmOptionsPath = path.toString())
                echo("Base VM options path updated: $path")
                BaseVMOptionsTracker.getInstance(configRepository).updateHash()
                echo("Base VM options hash calculated and stored")
            }

            basePluginsOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(basePluginsPath = path.toString())
                echo("Base plugins path updated: $path")
            }

            baseConfigOpt.getValueOrNull()?.let { path ->
                updated = updated.copy(baseConfigPath = path.toString())
                echo("Base config path updated: $path")
            }

            updated
        }

        echo()
        echo("Configuration updated successfully.")
    }
}
