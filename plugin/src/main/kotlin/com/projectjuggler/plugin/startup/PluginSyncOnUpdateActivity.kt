package com.projectjuggler.plugin.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.performSyncWithProgress
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.pluginSyncTargetsAfterUpdate
import com.projectjuggler.plugin.showNotification
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * After an IDE update, offers to sync plugins into the isolated projects. Nothing is stopped or synced until the
 * user clicks the balloon's action; ignoring it skips this build.
 */
internal class PluginSyncOnUpdateActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val repository = IdeInstallationService.currentIdeConfigRepository
        val currentBuild = ApplicationInfo.getInstance().build.asString()
        val handler = IntelliJNotificationHandler(project)

        val targets = withContext(Dispatchers.IO) {
            pluginSyncTargetsAfterUpdate(repository, PathManager.getConfigDir(), currentBuild, handler)
        }
        if (targets.isNotEmpty()) offerPluginSync(project, currentBuild, targets)
    }

    private fun offerPluginSync(project: Project, build: String, targets: List<ProjectMetadata>) {
        val syncAction = NotificationAction.createSimpleExpiring(
            ProjectJugglerBundle.message("notification.sync.on.update.action")
        ) {
            performSyncWithProgress(
                project = project,
                projects = targets,
                syncType = SyncType.Plugins,
                taskTitle = ProjectJugglerBundle.message("progress.sync.on.update"),
                successMessage = { ProjectJugglerBundle.message("notification.sync.on.update.complete", it.size) },
                errorMessage = { ProjectJugglerBundle.message("notification.sync.on.update.failed", it) },
            )
        }
        showNotification(
            ProjectJugglerBundle.message("notification.sync.on.update.available", build, targets.size),
            project,
            NotificationType.INFORMATION,
            syncAction,
        )
    }
}
