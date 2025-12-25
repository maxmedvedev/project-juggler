package com.ideajuggler.plugin.actions

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.MessageOutput
import com.ideajuggler.core.ProjectIdGenerator
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.plugin.IdeaJugglerBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.project.Project
import java.nio.file.Paths
import kotlin.io.path.isDirectory

internal class OpenWithIdeaJugglerAction : AnAction() {
    init {
        templatePresentation.text = IdeaJugglerBundle.message("action.OpenWithIdeaJuggler.text")
        templatePresentation.description = IdeaJugglerBundle.message("action.OpenWithIdeaJuggler.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Show directory chooser dialog
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = IdeaJugglerBundle.message("file.chooser.title")
            description = IdeaJugglerBundle.message("file.chooser.description")
        }

        val selectedFile = FileChooser.chooseFile(descriptor, project, null)
            ?: return // User cancelled the dialog


        val projectPath = Paths.get(selectedFile.path)
        if (!projectPath.isDirectory()) {
            showErrorNotification(
                project,
                IdeaJugglerBundle.message("notification.error.not.directory", selectedFile.path)
            )
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
                    IdeaJugglerBundle.message("notification.success.launched", selectedFile.name)
                )
            } catch (ex: Exception) {
                showErrorNotification(
                    project,
                    IdeaJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error")
                )
                ex.printStackTrace()
            }
        }
    }

    override fun update(e: AnActionEvent) {
        // Always enable the action - don't require a project to be open
        e.presentation.isEnabledAndVisible = true
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
