package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.util.application
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
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
            showErrorNotification(ProjectJugglerBundle.message("notification.error.not.directory", sourceFile.path), project)
            return
        }

        // Validate source is a git repository
        if (!GitWorktreeManager.isGitRepository(sourcePath.path)) {
            showErrorNotification(ProjectJugglerBundle.message("notification.error.not.git.repository", sourceFile.path), project)
            return
        }

        // Validate destination doesn't exist
        if (destPath.path.exists()) {
            showErrorNotification(ProjectJugglerBundle.message("notification.error.destination.exists", destFile.path), project)
            return
        }

        // Show info notification that worktree creation is starting
        showInfoNotification(ProjectJugglerBundle.message("notification.info.worktree.started", sourceFile.name), project)

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
                launcher.launch(destPath)

                showInfoNotification(ProjectJugglerBundle.message(
                        "notification.success.worktree.launched",
                        destFile.name,
                        branchName
                    ), project)
            } catch (ex: IllegalArgumentException) {
                showErrorNotification(ProjectJugglerBundle.message("notification.error.worktree.failed", ex.message ?: "Unknown error"), project)
                ex.printStackTrace()
            } catch (ex: Exception) {
                showErrorNotification(ProjectJugglerBundle.message("notification.error.worktree.failed", ex.message ?: "Unknown error"), project)
                ex.printStackTrace()
            }
        }
    }
}
