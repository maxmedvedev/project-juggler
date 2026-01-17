package com.projectjuggler.plugin.actions

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.showErrorNotification
import kotlin.io.path.isDirectory

internal fun showFileChooserAndLaunch(project: Project?) {
    val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
        title = ProjectJugglerBundle.message("file.chooser.title")
        description = ProjectJugglerBundle.message("file.chooser.description")
    }

    val selectedFile = FileChooser.chooseFile(descriptor, project, null) ?: return

    val repository = IdeInstallationService.currentIdeConfigRepository
    val projectPath = ProjectManager.getInstance(repository).resolvePath(selectedFile.path)
    if (!projectPath.path.isDirectory()) {
        showErrorNotification(
            ProjectJugglerBundle.message("notification.error.not.directory", selectedFile.path),
            project
        )
        return
    }

    launchOrFocusProject(project, projectPath)
}
