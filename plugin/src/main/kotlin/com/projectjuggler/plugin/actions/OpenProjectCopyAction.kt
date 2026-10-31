package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.util.application
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.createAndLaunchProjectCopy
import com.projectjuggler.plugin.services.validateProjectCopy
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

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

        KoinInit.init(pluginModule) // todo move to BGT

        // Validate paths before starting background operation
        val repository = IdeInstallationService.currentIdeConfigRepository
        val projectManager = ProjectManager.getInstance(repository)

        val sourcePath = projectManager.resolvePath(sourceFile.path)
        val destPath = projectManager.resolvePath(destFile.path)

        validateProjectCopy(sourcePath, destPath)?.let { error ->
            showErrorNotification(error, project)
            return
        }

        // Show info notification that worktree creation is starting
        showInfoNotification(ProjectJugglerBundle.message("notification.info.worktree.started", sourceFile.name), project)

        // Perform worktree creation and launch in background thread
        application.executeOnPooledThread {
            createAndLaunchProjectCopy(repository, sourcePath, destPath, branchName, IntelliJNotificationHandler(project))
        }
    }
}
