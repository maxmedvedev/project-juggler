package com.projectjuggler.plugin.actions

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService

/** Shows the chooser on EDT; resolving the path and launching run on a pooled thread (they touch the repository). */
internal fun showFileChooserAndLaunch(project: Project?) {
    val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().apply {
        title = ProjectJugglerBundle.message("file.chooser.title")
        description = ProjectJugglerBundle.message("file.chooser.description")
    }

    val selectedFile = FileChooser.chooseFile(descriptor, project, null) ?: return

    application.executeOnPooledThread {
        val repository = IdeInstallationService.currentIdeConfigRepository
        launchOrFocusProject(project, ProjectManager.getInstance(repository).resolvePath(selectedFile.path))
    }
}
