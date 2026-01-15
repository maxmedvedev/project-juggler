package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.util.IdeJuggler
import kotlin.io.path.isDirectory

object OpenFileChooserAction : RecentProjectPopupAction {
    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        showFileChooserAndLaunch(project)
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String =
        ProjectJugglerBundle.message("popup.open.file.chooser.label")

    private fun showFileChooserAndLaunch(project: Project?) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = ProjectJugglerBundle.message("file.chooser.title")
            description = ProjectJugglerBundle.message("file.chooser.description")
        }

        val selectedFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        val projectPath = ProjectManager.Companion.getInstance(currentIdeConfigRepository).resolvePath(selectedFile.path)
        if (!projectPath.path.isDirectory()) {
            showErrorNotification(
                ProjectJugglerBundle.message("notification.error.not.directory", selectedFile.path),
                project
            )
            return
        }

        IdeJuggler.launchProject(project, currentIdeConfigRepository, projectPath)
    }
}