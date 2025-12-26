package com.projectjuggler.plugin.actions

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.ui.RecentProjectsPopup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Load recent projects in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                RecentProjectsPopup(project).show()
            } catch (ex: Exception) {
                showErrorNotification(ex, project)
                ex.printStackTrace()
            }
        }
    }

    private fun showErrorNotification(ex: Exception, project: Project?) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("project-juggler.notifications")
            .createNotification(
                "Failed to load recent projects: ${ex.message}",
                NotificationType.ERROR
            )
            .notify(project)
    }

    private fun showNotificationNoRecentProjets(project: Project?) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("project-juggler.notifications")
            .createNotification(
                ProjectJugglerBundle.message("notification.recent.projects.empty"),
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
