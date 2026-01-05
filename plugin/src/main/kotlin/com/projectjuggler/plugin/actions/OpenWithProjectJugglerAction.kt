package com.projectjuggler.plugin.actions

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.util.IdeJuggler
import kotlin.io.path.isDirectory

internal class OpenWithProjectJugglerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Show directory chooser dialog
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = ProjectJugglerBundle.message("file.chooser.title")
            description = ProjectJugglerBundle.message("file.chooser.description")
        }

        val selectedFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        val configRepository = ConfigRepository.create()
        val projectPath = ProjectManager.getInstance(configRepository).resolvePath(selectedFile.path)
        if (!projectPath.path.isDirectory()) {
            showErrorNotification(ProjectJugglerBundle.message("notification.error.not.directory", selectedFile.path), project)
            return
        }

        // Launch project using shared helper
        IdeJuggler.launchProject(project, configRepository, projectPath)
    }

    override fun update(e: AnActionEvent) {
        // Always enable the action - don't require a project to be open
        e.presentation.isEnabledAndVisible = true
    }
}
