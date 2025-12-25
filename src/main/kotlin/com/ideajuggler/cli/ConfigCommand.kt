package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.BaseVMOptionsTracker
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
    private val show by option("--show", help = "Show current configuration").flag()

    override fun run() {
        val baseDir = Paths.get(System.getProperty("user.home"), ".idea-juggler")
        val configRepository = ConfigRepository(baseDir)
        val baseVMOptionsTracker = BaseVMOptionsTracker(configRepository)

        when {
            show -> showConfig(configRepository)
            intellijPath != null || baseVmOptionsPath != null -> updateConfig(configRepository, baseVMOptionsTracker)
            else -> showConfig(configRepository)
        }
    }

    private fun showConfig(configRepository: ConfigRepository) {
        val config = configRepository.load()

        echo("Current configuration:")
        echo()
        echo("  IntelliJ path:     ${config.intellijPath ?: "(not set, will auto-detect)"}")
        echo("  Base VM options:   ${config.baseVmOptionsPath ?: "(not set)"}")
        echo("  Max recent projects: ${config.maxRecentProjects}")
        echo()
        echo("Configuration file: ${Paths.get(System.getProperty("user.home"), ".idea-juggler", "config.json")}")
    }

    private fun updateConfig(configRepository: ConfigRepository, baseVMOptionsTracker: BaseVMOptionsTracker) {
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
                baseVMOptionsTracker.updateHash()
                echo("Base VM options hash calculated and stored")
            }

            updated
        }

        echo()
        echo("Configuration updated successfully.")
    }
}
