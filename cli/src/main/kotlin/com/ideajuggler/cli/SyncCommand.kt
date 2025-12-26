package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectLauncher

class SyncCommand : Command(
    name = "sync",
    help = "Synchronize project settings with base settings"
) {

    private val projectPath = StringOption(
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

    private val allProjectsFlag = FlagOption(
        shortName = null,
        longName = "all-projects",
        help = "Sync all tracked projects"
    ).also { options.add(it) }

    override fun run() {
        val projectPath = projectPath.getValueOrNull()
        val syncVmOptions = vmOptionsFlag.getValue()
        val syncConfig = configFlag.getValue()
        val syncPlugins = pluginsFlag.getValue()
        val syncAll = allFlag.getValue()
        val syncAllProjects = allProjectsFlag.getValue()

        val configRepository = ConfigRepository.create()

        // Validate mutually exclusive options
        if (syncAllProjects && projectPath != null) {
            echo("Error: Cannot specify both --all-projects and --path", err = true)
            throw ExitException(1)
        }

        if (!syncAllProjects && projectPath == null) {
            echo("Error: Either --all-projects or --path must be specified", err = true)
            echo("Usage: $name --path <project-path> [options]", err = true)
            echo("   or: $name --all-projects [options]", err = true)
            throw ExitException(1)
        }

        // Get projects to sync
        val projects = if (syncAllProjects) {
            val allProjects = configRepository.loadAllProjects()
            if (allProjects.isEmpty()) {
                echo("No tracked projects found.", err = true)
                throw ExitException(1)
            }
            echo("Synchronizing ${allProjects.size} project(s)...")
            echo()
            allProjects
        } else {
            listOf(resolveProject(projectPath))
        }

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

        val projectLauncher = ProjectLauncher.getInstance(configRepository)
        val directoryManager = com.ideajuggler.core.DirectoryManager.getInstance(configRepository)
        val baseVMOptionsTracker = com.ideajuggler.core.BaseVMOptionsTracker.getInstance(configRepository)

        // Sync each project
        var successCount = 0
        var failureCount = 0

        for (project in projects) {
            echo("Synchronizing project: ${project.name}")
            echo()

            try {
                // Show what will be synced and from where
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
                    project,
                    shouldSyncVmOptions,
                    shouldSyncConfig,
                    shouldSyncPlugins
                )

                echo("Successfully synchronized project settings.")
                successCount++
            } catch (e: IllegalStateException) {
                echo()
                echo("Error: ${e.message}", err = true)
                failureCount++
            } catch (e: Exception) {
                echo()
                echo("Error syncing project: ${e.message}", err = true)
                failureCount++
            }

            if (syncAllProjects && project != projects.last()) {
                echo()
                echo("---")
                echo()
            }
        }

        if (syncAllProjects) {
            echo()
            echo("Summary: $successCount succeeded, $failureCount failed")
            if (failureCount > 0) {
                throw ExitException(1)
            }
        } else if (failureCount > 0) {
            throw ExitException(1)
        }
    }
}
