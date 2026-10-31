package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.process.ProjectLauncher
import java.nio.file.Path

private val LOG = fileLogger()

/**
 * Re-syncs plugins into every isolated project once the IDE build changes.
 *
 * Runs only in the main instance. The current build is always stored, so only the first call after an
 * update syncs. Per-project failures are logged and skipped; any other failure is reported.
 */
internal fun syncPluginsOnIdeUpdate(
    repository: IdeConfigRepository,
    currentConfigDir: Path,
    currentBuild: String,
    notificationHandler: NotificationHandler,
    launcher: ProjectLauncher = ProjectLauncher.getInstance(repository),
) {
    try {
        // Only run in the main IDE instance, not isolated projects
        if (!MainProjectService.isRunningInMainInstance(repository, currentConfigDir)) return

        val config = repository.load()
        val previousBuild = config.lastKnownBuildNumber

        // Always update stored build number
        repository.update { it.copy(lastKnownBuildNumber = currentBuild) }

        // Skip if first run (no previous build) or build unchanged
        if (previousBuild == null || previousBuild == currentBuild) return

        // Skip if setting is disabled
        if (!config.syncPluginsOnIdeUpdate) return

        val allProjects = ProjectManager.getInstance(repository).listSyncable()
        if (allProjects.isEmpty()) return

        notificationHandler.showInfoNotification(
            ProjectJugglerBundle.message("notification.sync.on.update.started", currentBuild, allProjects.size)
        )

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
                t.rethrowIfControlFlow()
                LOG.warn("Failed to sync plugins for project '${metadata.name}': ${t.message}", t)
            }
        }

        notificationHandler.showInfoNotification(
            ProjectJugglerBundle.message("notification.sync.on.update.complete", allProjects.size)
        )
    } catch (t: Throwable) {
        t.rethrowIfControlFlow()
        LOG.error("Plugin sync on IDE update failed", t)
        notificationHandler.showErrorNotification(
            ProjectJugglerBundle.message("notification.sync.on.update.failed", t.userMessage())
        )
    }
}
