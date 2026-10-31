package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.Logger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectId
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.SelfSyncResultStore
import com.projectjuggler.core.SyncTypeNames
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.rethrowIfControlFlow
import java.nio.file.Path

private val LOG = Logger.getInstance("#com.projectjuggler.plugin.services.SelfSyncReport")

/**
 * Shows the outcome of a self-sync of [projectId] that the sync-helper ran from outside. Consumes the project's
 * `SelfSyncResult`: a success gives an info notification (plus an error one when the relaunch reported an error), a
 * failure an error notification with the message and [logFile]. Without a result file, a non-zero [exitCode] gives
 * the generic "sync-helper exited" error; a null [exitCode] (at startup) or 0 shows nothing. When the project's
 * metadata is gone there is no result to read, so only that generic error can be shown.
 *
 * @param exitCode The helper's exit code when it exited while this IDE runs, null when called at startup
 */
internal fun reportSelfSyncResult(
    repository: IdeConfigRepository,
    projectId: ProjectId,
    exitCode: Int?,
    logFile: Path,
    notifications: NotificationHandler,
) {
    try {
        val project = repository.loadProjectMetadata(projectId)
        val result = project?.let { SelfSyncResultStore.getInstance(repository).consume(it) }
        if (project == null || result == null) {
            if (exitCode != null && exitCode != 0) {
                notifications.showErrorNotification(
                    ProjectJugglerBundle.message("notification.self.sync.helper.exited", exitCode, logFile.toString())
                )
            }
            return
        }

        val types = syncTypesDisplayName(result.syncTypes)
        if (result.succeeded) {
            notifications.showInfoNotification(
                ProjectJugglerBundle.message("notification.self.sync.succeeded", types, project.name)
            )
        } else {
            val message = result.message ?: ProjectJugglerBundle.message("notification.self.sync.unknown.error")
            notifications.showErrorNotification(
                ProjectJugglerBundle.message(
                    "notification.self.sync.failed", types, project.name, message, logFile.toString()
                )
            )
        }
        result.restartError?.let {
            notifications.showErrorNotification(
                ProjectJugglerBundle.message("notification.self.sync.restart.failed", project.name, it, logFile.toString())
            )
        }
    } catch (t: Throwable) {
        t.rethrowIfControlFlow()
        LOG.warn("Failed to report the self-sync result of ${projectId.id}", t)
    }
}

/** [SyncTypeNames] values as display names: all three give [SyncType.All], otherwise each one joined with ", ". */
private fun syncTypesDisplayName(syncTypes: List<String>): String {
    if (syncTypes.toSet() == ALL_SYNC_TYPE_NAMES) return SyncType.All.displayName
    return syncTypes.map { SYNC_TYPES_BY_NAME[it]?.displayName ?: it }.joinToString(", ")
}

private val SYNC_TYPES_BY_NAME: Map<String, SyncType> = mapOf(
    SyncTypeNames.VM_OPTIONS to SyncType.VmOptions,
    SyncTypeNames.CONFIG to SyncType.Config,
    SyncTypeNames.PLUGINS to SyncType.Plugins,
)

private val ALL_SYNC_TYPE_NAMES: Set<String> = SYNC_TYPES_BY_NAME.keys
