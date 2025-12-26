package com.ideajuggler.plugin.actions

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.MessageOutput
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.core.ProjectManager
import com.ideajuggler.plugin.IdeaJugglerBundle
import com.ideajuggler.util.GitWorktreeManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal class OpenProjectCopyAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Step 1: Choose source directory
        val sourceDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = IdeaJugglerBundle.message("file.chooser.source.title")
            description = IdeaJugglerBundle.message("file.chooser.source.description")
        }

        val sourceFile = FileChooser.chooseFile(sourceDescriptor, project, null)
            ?: return // User cancelled

        // Step 2: Choose destination directory
        val destDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = IdeaJugglerBundle.message("file.chooser.destination.title")
            description = IdeaJugglerBundle.message("file.chooser.destination.description")
        }

        val destFile = FileChooser.chooseFile(destDescriptor, project, null)
            ?: return // User cancelled

        // Step 3: Get branch name from user
        val branchName = Messages.showInputDialog(
            project,
            IdeaJugglerBundle.message("input.dialog.branch.message"),
            IdeaJugglerBundle.message("input.dialog.branch.title"),
            Messages.getQuestionIcon()
        )

        if (branchName.isNullOrBlank()) {
            return // User cancelled or entered empty branch name
        }

        // Validate paths before starting background operation
        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)

        val sourcePath = projectManager.resolvePath(sourceFile.path)
        val destPath = projectManager.resolvePath(destFile.path)

        // Validate source is a directory
        if (!sourcePath.path.isDirectory()) {
            showErrorNotification(
                project,
                IdeaJugglerBundle.message("notification.error.not.directory", sourceFile.path)
            )
            return
        }

        // Validate source is a git repository
        if (!GitWorktreeManager.isGitRepository(sourcePath.path)) {
            showErrorNotification(
                project,
                IdeaJugglerBundle.message("notification.error.not.git.repository", sourceFile.path)
            )
            return
        }

        // Validate destination doesn't exist
        if (destPath.path.exists()) {
            showErrorNotification(
                project,
                IdeaJugglerBundle.message("notification.error.destination.exists", destFile.path)
            )
            return
        }

        // Show info notification that worktree creation is starting
        showInfoNotification(
            project,
            IdeaJugglerBundle.message("notification.info.worktree.started", sourceFile.name)
        )

        // Perform worktree creation and launch in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Create git worktree (no progress messages in plugin context)
                GitWorktreeManager.createWorktree(
                    source = sourcePath.path,
                    destination = destPath.path,
                    branchName = branchName,
                    messageOutput = null
                )

                // Launch the worktree
                val launcher = ProjectLauncher.getInstance(configRepository)
                val messageOutput = object : MessageOutput {
                    override fun echo(message: String) {
                        // Suppress console output in plugin context
                    }
                }
                launcher.launch(messageOutput, destPath)

                showInfoNotification(
                    project,
                    IdeaJugglerBundle.message(
                        "notification.success.worktree.launched",
                        destFile.name,
                        branchName
                    )
                )
            } catch (ex: IllegalArgumentException) {
                showErrorNotification(
                    project,
                    IdeaJugglerBundle.message(
                        "notification.error.worktree.failed",
                        ex.message ?: "Unknown error"
                    )
                )
                ex.printStackTrace()
            } catch (ex: Exception) {
                showErrorNotification(
                    project,
                    IdeaJugglerBundle.message(
                        "notification.error.worktree.failed",
                        ex.message ?: "Unknown error"
                    )
                )
                ex.printStackTrace()
            }
        }
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
