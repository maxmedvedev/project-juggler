package com.projectjuggler.plugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.projectjuggler.plugin.ui.RecentProjectsPopup

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Load recent projects in background thread
        application.executeOnPooledThread {
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
}
