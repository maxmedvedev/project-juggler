package com.projectjuggler.plugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.application
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.MessageOutput
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.util.GitWorktreeManager
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal class OpenProjectCopyAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Step 1: Choose source directory
        val sourceDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = ProjectJugglerBundle.message("file.chooser.source.title")
            description = ProjectJugglerBundle.message("file.chooser.source.description")
        }

        val sourceFile = FileChooser.chooseFile(sourceDescriptor, project, null)
            ?: return // User cancelled

        // Step 2: Choose destination directory
        val destDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = ProjectJugglerBundle.message("file.chooser.destination.title")
            description = ProjectJugglerBundle.message("file.chooser.destination.description")
        }

        val destFile = FileChooser.chooseFile(destDescriptor, project, null)
            ?: return // User cancelled

        // Step 3: Get branch name from user
        val branchName = Messages.showInputDialog(
            project,
            ProjectJugglerBundle.message("input.dialog.branch.message"),
            ProjectJugglerBundle.message("input.dialog.branch.title"),
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
                ProjectJugglerBundle.message("notification.error.not.directory", sourceFile.path)
            )
            return
        }

        // Validate source is a git repository
        if (!GitWorktreeManager.isGitRepository(sourcePath.path)) {
            showErrorNotification(
                project,
                ProjectJugglerBundle.message("notification.error.not.git.repository", sourceFile.path)
            )
            return
        }

        // Validate destination doesn't exist
        if (destPath.path.exists()) {
            showErrorNotification(
                project,
                ProjectJugglerBundle.message("notification.error.destination.exists", destFile.path)
            )
            return
        }

        // Show info notification that worktree creation is starting
        showInfoNotification(
            project,
            ProjectJugglerBundle.message("notification.info.worktree.started", sourceFile.name)
        )

        // Perform worktree creation and launch in background thread
        application.executeOnPooledThread {
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
                    ProjectJugglerBundle.message(
                        "notification.success.worktree.launched",
                        destFile.name,
                        branchName
                    )
                )
            } catch (ex: IllegalArgumentException) {
                showErrorNotification(
                    project,
                    ProjectJugglerBundle.message(
                        "notification.error.worktree.failed",
                        ex.message ?: "Unknown error"
                    )
                )
                ex.printStackTrace()
            } catch (ex: Exception) {
                showErrorNotification(
                    project,
                    ProjectJugglerBundle.message(
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
            .getNotificationGroup("project-juggler.notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun showErrorNotification(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("project-juggler.notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
