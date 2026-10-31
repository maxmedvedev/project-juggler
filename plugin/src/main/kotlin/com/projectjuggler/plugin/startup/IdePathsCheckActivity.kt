package com.projectjuggler.plugin.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.application
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.CurrentIdePaths
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IdePathSyncDetector
import com.projectjuggler.plugin.services.IdePathsStartupCheck
import com.projectjuggler.plugin.services.joinDisplayNames
import com.projectjuggler.plugin.showNotification
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Warns once per IDE session, in the main instance, when the stored base paths no longer match the running IDE,
 * and offers to update them. An IDE that stays on the welcome screen runs no project activity; the popup item
 * covers that case.
 */
internal class IdePathsCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            KoinInit.init(pluginModule)

            val (repository, mismatches) = withContext(Dispatchers.IO) {
                val repository = IdeInstallationService.currentIdeConfigRepository
                repository to IdePathsStartupCheck.mismatchesToReport(repository, CurrentIdePaths.detect())
            }
            if (mismatches.isEmpty()) return

            val updateAction = NotificationAction.createSimpleExpiring(
                ProjectJugglerBundle.message("notification.action.update.paths")
            ) {
                application.executeOnPooledThread {
                    IdePathSyncDetector.updatePaths(repository, IntelliJNotificationHandler(project), CurrentIdePaths.detect())
                }
            }
            showNotification(
                ProjectJugglerBundle.message("notification.paths.out.of.sync", mismatches.joinDisplayNames()),
                project,
                NotificationType.WARNING,
                updateAction,
            )
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.error("IDE paths startup check failed", t)
        }
    }
}

private val LOG = logger<IdePathsCheckActivity>()
