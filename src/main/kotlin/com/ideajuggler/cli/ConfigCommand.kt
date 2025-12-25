package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.BaseVMOptionsTracker
import com.ideajuggler.platform.PluginLocator
import java.nio.file.Paths
import kotlin.io.path.exists

class ConfigCommand : CliktCommand(
    name = "config",
    help = "Configure global settings"
) {
    private val intellijPath by option("--intellij-path", help = "Path to IntelliJ executable")
        .path(mustExist = true)
    private val baseVmOptionsPath by option("--base-vmoptions", help = "Path to base VM options file")
        .path(mustExist = true, mustBeReadable = true, canBeDir = false)
    private val basePluginsPath by option("--base-plugins", help = "Path to base IntelliJ plugins directory")
        .path(mustExist = true, mustBeReadable = true, canBeDir = true)
    private val show by option("--show", help = "Show current configuration").flag()

    override fun run() {
        val configRepository = ConfigRepository.create()

        when {
            show -> showConfig(configRepository)
            intellijPath != null || baseVmOptionsPath != null || basePluginsPath != null -> updateConfig(configRepository)
            else -> showConfig(configRepository)
        }
    }

    private fun showConfig(configRepository: ConfigRepository) {
        val config = configRepository.load()

        // Auto-detect base plugins path for display
        val detectedPluginsPath = PluginLocator.findDefaultPluginsDirectory()
        val pluginsPathDisplay = config.basePluginsPath
            ?: detectedPluginsPath?.toString()
            ?: "(not set, will auto-detect)"

        echo("Current configuration:")
        echo()
        echo("  IntelliJ path:       ${config.intellijPath ?: "(not set, will auto-detect)"}")
        echo("  Base VM options:     ${config.baseVmOptionsPath ?: "(not set)"}")
        echo("  Base plugins:        $pluginsPathDisplay")
        echo("  Max recent projects: ${config.maxRecentProjects}")
        echo()
        echo("Configuration file: ${Paths.get(System.getProperty("user.home"), ".idea-juggler", "config.json")}")
    }

    private fun updateConfig(configRepository: ConfigRepository) {
        // Validate paths before updating
        intellijPath?.let { path ->
            if (!path.exists()) {
                echo("Error: IntelliJ path does not exist: $path", err = true)
                return
            }
        }

        baseVmOptionsPath?.let { path ->
            if (!path.exists()) {
                echo("Error: Base VM options file does not exist: $path", err = true)
                return
            }
        }

        basePluginsPath?.let { path ->
            if (!path.exists()) {
                echo("Error: Base plugins path does not exist: $path", err = true)
                return
            }
        }

        // Update configuration
        configRepository.update { config ->
            var updated = config

            intellijPath?.let { path ->
                updated = updated.copy(intellijPath = path.toString())
                echo("IntelliJ path updated: $path")
            }

            baseVmOptionsPath?.let { path ->
                updated = updated.copy(baseVmOptionsPath = path.toString())
                echo("Base VM options path updated: $path")

                // Calculate and store initial hash
                BaseVMOptionsTracker.getInstance(configRepository).updateHash()
                echo("Base VM options hash calculated and stored")
            }

            basePluginsPath?.let { path ->
                updated = updated.copy(basePluginsPath = path.toString())
                echo("Base plugins path updated: $path")
            }

            updated
        }

        echo()
        echo("Configuration updated successfully.")
    }
}
