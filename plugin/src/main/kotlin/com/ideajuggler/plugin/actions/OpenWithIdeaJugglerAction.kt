package com.ideajuggler.plugin.actions

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.MessageOutput
import com.ideajuggler.core.ProjectIdGenerator
import com.ideajuggler.core.ProjectLauncher
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class OpenWithIdeaJugglerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showErrorNotification(null, "No project is currently open")
            return
        }

        val projectBasePath = project.basePath ?: run {
            showErrorNotification(project, "Could not determine project path")
            return
        }

        val projectPath = Paths.get(projectBasePath)
        if (!projectPath.exists() || !projectPath.isDirectory()) {
            showErrorNotification(project, "Invalid project path: $projectBasePath")
            return
        }

        // Launch asynchronously in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val configRepository = ConfigRepository.create()
                val launcher = ProjectLauncher.getInstance(configRepository)
                val projectId = ProjectIdGenerator.generate(projectPath)

                // Silent message output for plugin context
                val messageOutput = object : MessageOutput {
                    override fun echo(message: String) {
                        // Suppress console output in plugin context
                        // Notifications are handled separately
                    }
                }

                launcher.launch(messageOutput, projectPath, projectId)

                showInfoNotification(
                    project,
                    "Launched new idea-juggler instance for: ${project.name}"
                )
            } catch (ex: Exception) {
                showErrorNotification(
                    project,
                    "Failed to launch idea-juggler: ${ex.message}"
                )
                ex.printStackTrace()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun showInfoNotification(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("idea-juggler.notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun showErrorNotification(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("idea-juggler.notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
