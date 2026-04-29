package com.projectjuggler.plugin.startup

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.process.ProjectLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PluginSyncOnUpdateActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            KoinInit.init(pluginModule)

            val repository = IdeInstallationService.currentIdeConfigRepository

            // Only run in the main IDE instance, not isolated projects
            val currentConfigDir = PathManager.getConfigDir()
            if (!MainProjectService.isRunningInMainInstance(repository, currentConfigDir)) return

            val currentBuild = ApplicationInfo.getInstance().build.asString()
            val config = repository.load()
            val previousBuild = config.lastKnownBuildNumber

            // Always update stored build number
            repository.update { it.copy(lastKnownBuildNumber = currentBuild) }

            // Skip if first run (no previous build) or build unchanged
            if (previousBuild == null || previousBuild == currentBuild) return

            // Skip if setting is disabled
            if (!config.syncPluginsOnIdeUpdate) return

            val allProjects = repository.loadAllProjects()
            if (allProjects.isEmpty()) return

            showInfoNotification(
                ProjectJugglerBundle.message("notification.sync.on.update.started", currentBuild, allProjects.size),
                project
            )

            withContext(Dispatchers.IO) {
                val launcher = ProjectLauncher.getInstance(repository)
                val syncOptions = SyncOptions(
                    stopIfRunning = true,
                    autoRestart = true,
                    shutdownTimeout = 60
                )

                for (metadata in allProjects) {
                    try {
                        launcher.syncProject(
                            metadata,
                            syncVmOptions = false,
                            syncConfig = false,
                            syncPlugins = true,
                            syncOptions
                        )
                    } catch (t: Throwable) {
                        if (t is ControlFlowException) throw t
                        LOG.warn("Failed to sync plugins for project '${metadata.name}': ${t.message}", t)
                    }
                }
            }

            showInfoNotification(
                ProjectJugglerBundle.message("notification.sync.on.update.complete", allProjects.size),
                project
            )
        } catch (t: Throwable) {
            if (t is ControlFlowException) throw t
            LOG.error("Plugin sync on IDE update failed", t)
            showErrorNotification(
                ProjectJugglerBundle.message("notification.sync.on.update.failed", t.message ?: "Unknown error"),
                project
            )
        }
    }
}

private val LOG = logger<PluginSyncOnUpdateActivity>()
