package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.plugin.services.IdeInstallationService

/** Open project with a specific IDE installation */
data class OpenWithIdeSubAction(val installation: IdeInstallation) : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        val repository = IdeInstallationService.getInstance().getRepository(installation)
        launchOrFocusProject(project, item.projectPath, repository)
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(item: OpenRecentProjectAction): String =
        "Open with ${installation.displayName}"
}