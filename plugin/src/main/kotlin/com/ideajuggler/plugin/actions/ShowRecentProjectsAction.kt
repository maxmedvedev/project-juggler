package com.ideajuggler.plugin.actions

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.plugin.IdeaJugglerBundle
import com.ideajuggler.plugin.ui.RecentProjectsPopup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Load recent projects in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val configRepository = ConfigRepository.create()
                val recentIndex = RecentProjectsIndex.getInstance(configRepository)
                val recentProjects = recentIndex.getRecent(20)

                if (recentProjects.isEmpty()) {
                    // Show notification if no recent projects
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("idea-juggler.notifications")
                            .createNotification(
                                IdeaJugglerBundle.message("notification.recent.projects.empty"),
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                    }
                    return@executeOnPooledThread
                }

                RecentProjectsPopup(project, configRepository).show()
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("idea-juggler.notifications")
                        .createNotification(
                            "Failed to load recent projects: ${ex.message}",
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
                ex.printStackTrace()
            }
        }
    }
}
