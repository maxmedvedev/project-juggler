package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.showFileChooserAndLaunch

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
}