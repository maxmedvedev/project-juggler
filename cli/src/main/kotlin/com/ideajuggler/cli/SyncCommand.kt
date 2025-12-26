package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectLauncher

class SyncCommand : Command(
    name = "sync",
    help = "Synchronize project settings with base settings"
) {
    private val projectIdOpt = StringOption(
        shortName = "i",
        longName = "id",
        help = "Project ID"
    ).also { options.add(it) }

    private val projectPathOpt = PathOption(
        shortName = "p",
        longName = "path",
        help = "Project path"
    ).also { options.add(it) }

    private val vmOptionsFlag = FlagOption(
        shortName = null,
        longName = "vmoptions",
        help = "Sync VM options from base-vmoptions"
    ).also { options.add(it) }

    private val configFlag = FlagOption(
        shortName = null,
        longName = "config",
        help = "Sync config from base-config"
    ).also { options.add(it) }

    private val pluginsFlag = FlagOption(
        shortName = null,
        longName = "plugins",
        help = "Sync plugins from base-plugins"
    ).also { options.add(it) }

    private val allFlag = FlagOption(
        shortName = "a",
        longName = "all",
        help = "Sync all settings (vmoptions, config, plugins)"
    ).also { options.add(it) }

    override fun run() {
        val projectId = projectIdOpt.getValueOrNull()
        val projectPath = projectPathOpt.getValueOrNull()
        val syncVmOptions = vmOptionsFlag.getValue()
        val syncConfig = configFlag.getValue()
        val syncPlugins = pluginsFlag.getValue()
        val syncAll = allFlag.getValue()

        // Resolve project using helper method
        val (resolvedProjectId, project) = resolveProject(projectId, projectPath)

        val configRepository = ConfigRepository.create()
        val projectLauncher = ProjectLauncher.getInstance(configRepository)

        // Determine what to sync
        val noFlagsSpecified = !syncAll && !syncVmOptions && !syncConfig && !syncPlugins

        val shouldSyncVmOptions = if (noFlagsSpecified) {
            // Default: only sync if configured
            configRepository.load().baseVmOptionsPath != null
        } else {
            syncAll || syncVmOptions
        }

        val shouldSyncConfig = if (noFlagsSpecified) {
            // Default: sync (can use auto-detection)
            true
        } else {
            syncAll || syncConfig
        }

        val shouldSyncPlugins = if (noFlagsSpecified) {
            // Default: sync (can use auto-detection)
            true
        } else {
            syncAll || syncPlugins
        }

        echo("Synchronizing project: ${project.name}")
        echo()

        try {
            // Show what will be synced and from where
            val directoryManager = com.ideajuggler.core.DirectoryManager.getInstance(configRepository)
            val baseVMOptionsTracker = com.ideajuggler.core.BaseVMOptionsTracker.getInstance(configRepository)

            if (shouldSyncVmOptions) {
                val vmPath = baseVMOptionsTracker.getBaseVmOptionsPath()
                if (vmPath != null) {
                    echo("  Syncing VM options from: $vmPath")
                } else {
                    echo("  Syncing VM options from: (not configured)", err = true)
                }
            }
            if (shouldSyncConfig) {
                val configPath = directoryManager.getBaseConfigPath()
                if (configPath != null) {
                    echo("  Syncing config from: $configPath")
                } else {
                    echo("  Syncing config from: (not found)", err = true)
                }
            }
            if (shouldSyncPlugins) {
                val pluginsPath = directoryManager.getBasePluginsPath()
                if (pluginsPath != null) {
                    echo("  Syncing plugins from: $pluginsPath")
                } else {
                    echo("  Syncing plugins from: (not found)", err = true)
                }
            }
            echo()

            projectLauncher.syncProject(
                resolvedProjectId,
                shouldSyncVmOptions,
                shouldSyncConfig,
                shouldSyncPlugins
            )

            echo("Successfully synchronized project settings.")
        } catch (e: IllegalStateException) {
            echo()
            echo("Error: ${e.message}", err = true)
            throw ExitException(1)
        } catch (e: Exception) {
            echo()
            echo("Error syncing project: ${e.message}", err = true)
            throw ExitException(1)
        }
    }
}
