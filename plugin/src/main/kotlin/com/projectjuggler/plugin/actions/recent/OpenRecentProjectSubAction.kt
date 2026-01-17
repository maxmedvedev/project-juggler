package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.launchOrFocusProject
import com.projectjuggler.plugin.services.IdeInstallationService

/** Open project with the current IDE */
object OpenRecentProjectSubAction : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        launchOrFocusProject(project, item.projectPath, IdeInstallationService.currentIdeConfigRepository)
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(item: OpenRecentProjectAction): String =
        ProjectJugglerBundle.message("popup.recent.projects.action.open.project")
}