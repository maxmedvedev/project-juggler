package com.ideajuggler.plugin.ui

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectMetadata
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.MessageOutput
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.plugin.IdeaJugglerBundle
import com.ideajuggler.plugin.model.RecentProjectItem
import com.ideajuggler.util.GitUtils
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.nio.file.Files

internal class RecentProjectsPopup(
    private val project: Project?,
    private val configRepository: ConfigRepository
) {
    @RequiresBackgroundThread
    fun show() {
        computeDataOnBGT()
    }

    @RequiresEdt
    private fun createAndShowItems(items: List<RecentProjectItem>) {
        val step = RecentProjectsPopupStep(items, ::launchProject)
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        popup.showInFocusCenter()
    }

    @RequiresBackgroundThread
    private fun computeDataOnBGT() {
        try {
            // Load recent projects
            val recentIndex = RecentProjectsIndex.getInstance(configRepository)
            val recentMetadata = recentIndex.getRecent(20)

            // Filter out non-existent paths (deleted projects)
            val validProjects = recentMetadata.filter { metadata ->
                Files.exists(metadata.path.path)
            }

            if (validProjects.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        IdeaJugglerBundle.message("notification.recent.projects.empty"),
                        NotificationType.INFORMATION
                    )
                }
                return
            }

            // Create items with git branch info
            val items = validProjects.map { metadata ->
                createRecentProjectItem(metadata)
            }

            // Show popup on EDT
            ApplicationManager.getApplication().invokeLater {
                createAndShowItems(items)
            }
        } catch (ex: Exception) {
            ApplicationManager.getApplication().invokeLater {
                showNotification(
                    "Failed to load recent projects: ${ex.message}",
                    NotificationType.ERROR
                )
            }
            ex.printStackTrace()
        }
    }

    private fun createRecentProjectItem(metadata: ProjectMetadata): RecentProjectItem {
        val gitBranch = GitUtils.detectGitBranch(metadata.path.path)
        val displayText = formatDisplayText(metadata, gitBranch)
        return RecentProjectItem(metadata, gitBranch, displayText)
    }

    private fun formatDisplayText(metadata: ProjectMetadata, gitBranch: String?): String {
        val name = metadata.name
        val path = metadata.path.pathString

        // Format: "ProjectName - [branch] - /path/to/project"
        return buildString {
            append(name)
            if (gitBranch != null) {
                append(" - [")
                append(gitBranch)
                append("]")
            }
            append(" - ")
            append(path)
        }
    }

    private fun launchProject(item: RecentProjectItem) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val launcher = ProjectLauncher.getInstance(configRepository)

                // Silent message output for plugin context
                val messageOutput = object : MessageOutput {
                    override fun echo(message: String) {
                        // Suppress console output in plugin context
                    }
                }

                launcher.launch(messageOutput, item.metadata.path)

                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        IdeaJugglerBundle.message("notification.success.launched", item.metadata.name),
                        NotificationType.INFORMATION
                    )
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    showNotification(
                        IdeaJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error"),
                        NotificationType.ERROR
                    )
                }
                ex.printStackTrace()
            }
        }
    }

    private fun showNotification(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("idea-juggler.notifications")
            .createNotification(message, type)
            .notify(project)
    }
}
