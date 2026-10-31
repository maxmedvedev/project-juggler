package com.projectjuggler.plugin.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.application
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.CurrentIdePaths
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IdePathProblem
import com.projectjuggler.plugin.services.IdePathSyncDetector
import com.projectjuggler.plugin.services.IdePathsStartupCheck
import com.projectjuggler.plugin.services.joinDisplayNames
import com.projectjuggler.plugin.settings.ProjectJugglerConfigurable
import com.projectjuggler.plugin.showNotification
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Warns once per IDE session, in the main instance, when stored base paths no longer exist or no longer match
 * the running IDE, and offers to update them or open the settings page. Missing paths are reported first.
 * An IDE that stays on the welcome screen runs no project activity; the popup item covers drift in that case.
 */
internal class IdePathsCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            val (repository, problems) = withContext(Dispatchers.IO) {
                val repository = IdeInstallationService.currentIdeConfigRepository
                repository to IdePathsStartupCheck.problemsToReport(repository, CurrentIdePaths.detect())
            }
            val missing = problems.filterValues { it == IdePathProblem.MISSING }.keys.toList()
            val (message, updateText) = when {
                missing.isNotEmpty() -> Pair(
                    ProjectJugglerBundle.message("notification.paths.missing", missing.joinDisplayNames()),
                    ProjectJugglerBundle.message("notification.action.use.current.paths"),
                )
                problems.isNotEmpty() -> Pair(
                    ProjectJugglerBundle.message("notification.paths.out.of.sync", problems.keys.toList().joinDisplayNames()),
                    ProjectJugglerBundle.message("notification.action.update.paths"),
                )
                else -> return
            }

            showNotification(
                message,
                project,
                NotificationType.WARNING,
                updatePathsAction(updateText, repository, project),
                NotificationAction.createSimpleExpiring(ProjectJugglerBundle.message("notification.action.open.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectJugglerConfigurable::class.java)
                },
            )
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.error("IDE paths startup check failed", t)
        }
    }

    private fun updatePathsAction(text: String, repository: IdeConfigRepository, project: Project) =
        NotificationAction.createSimpleExpiring(text) {
            application.executeOnPooledThread {
                IdePathSyncDetector.updatePaths(repository, IntelliJNotificationHandler(project), CurrentIdePaths.detect())
            }
        }
}

private val LOG = logger<IdePathsCheckActivity>()
