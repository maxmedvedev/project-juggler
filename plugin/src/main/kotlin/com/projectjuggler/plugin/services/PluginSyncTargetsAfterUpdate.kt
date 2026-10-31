package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import java.nio.file.Path

private val LOG = fileLogger()

/**
 * Decides whether to offer a plugin sync after the IDE build changed. Stores the current build and returns the
 * projects to offer the sync for; empty when there is nothing to do.
 *
 * Runs only in the main instance. The current build is always stored, so only the first call after an update
 * returns targets. It syncs nothing itself; any failure is reported and yields no targets.
 */
internal fun pluginSyncTargetsAfterUpdate(
    repository: IdeConfigRepository,
    currentConfigDir: Path,
    currentBuild: String,
    notificationHandler: NotificationHandler,
): List<ProjectMetadata> {
    try {
        // Only run in the main IDE instance, not isolated projects
        if (!MainProjectService.isRunningInMainInstance(repository, currentConfigDir)) return emptyList()

        val config = repository.load()
        val previousBuild = config.lastKnownBuildNumber

        // Always update stored build number
        repository.update { it.copy(lastKnownBuildNumber = currentBuild) }

        // Skip if first run (no previous build) or build unchanged
        if (previousBuild == null || previousBuild == currentBuild) return emptyList()

        // Skip if setting is disabled
        if (!config.syncPluginsOnIdeUpdate) return emptyList()

        return ProjectManager.getInstance(repository).listSyncable()
    } catch (t: Throwable) {
        t.rethrowIfControlFlow()
        LOG.error("Plugin sync on IDE update failed", t)
        notificationHandler.showErrorNotification(
            ProjectJugglerBundle.message("notification.sync.on.update.failed", t.userMessage())
        )
        return emptyList()
    }
}
