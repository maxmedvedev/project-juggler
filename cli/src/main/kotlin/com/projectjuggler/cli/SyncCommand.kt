package com.projectjuggler.cli

import com.projectjuggler.cli.framework.*
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress

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

    private val noStopFlag = FlagOption(
        shortName = null,
        longName = "no-stop",
        help = "Don't stop running IntelliJ instances (default is to stop and restart)"
    ).also { options.add(it) }

    private val noRestartFlag = FlagOption(
        shortName = null,
        longName = "no-restart",
        help = "Don't restart IntelliJ after sync (project will remain closed)"
    ).also { options.add(it) }

    private val timeoutOption = IntOption(
        shortName = null,
        longName = "timeout",
        help = "Shutdown timeout in seconds (default: 60)",
        default = 60
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

        // Create sync options with stop/restart behavior
        val shouldStop = !noStopFlag.getValue()
        val shouldRestart = !noRestartFlag.getValue()
        val timeout = timeoutOption.getValue()

        val syncOptions = SyncOptions(
            stopIfRunning = shouldStop,
            autoRestart = shouldRestart,
            shutdownTimeout = timeout,
            onProgress = { progress ->
                when (progress) {
                    is SyncProgress.Stopping -> {
                        if (progress.secondsElapsed == 1) {
                            echo("  Waiting for IntelliJ to close...")
                        }
                    }
                    is SyncProgress.Syncing -> {
                        // Already showing sync details below
                    }
                    is SyncProgress.Restarting -> {
                        echo("  Restarting IntelliJ...")
                    }
                    is SyncProgress.Error -> {
                        echo("  Warning: ${progress.message}", err = true)
                    }
                }
            }
        )

        val projectLauncher = ProjectLauncher.getInstance(configRepository)
        val directoryManager = com.projectjuggler.core.DirectoryManager.getInstance(configRepository)
        val baseVMOptionsTracker = com.projectjuggler.core.BaseVMOptionsTracker.getInstance(configRepository)

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
                    shouldSyncPlugins,
                    syncOptions
                )

                echo("Successfully synchronized project settings.")
                successCount++
            } catch (e: IllegalStateException) {
                echo()
                echo("Error: ${e.message}", err = true)
                failureCount++
            } catch (e: com.projectjuggler.core.SyncException) {
                echo()
                echo("Sync failed:", err = true)
                echo(e.message ?: "Unknown error", err = true)
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
