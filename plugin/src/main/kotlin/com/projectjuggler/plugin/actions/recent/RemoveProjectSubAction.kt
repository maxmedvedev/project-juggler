package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.removeRecentProject

/** Remove project from tracking */
object RemoveProjectSubAction : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        application.executeOnPooledThread {
            removeRecentProject(item.projectPath, IdeInstallationService.currentIdeConfigRepository)
        }
        return step.doFinalStep {
            RecentProjectPopupBuilder(project).show()
        }
    }

    override fun getTextFor(item: OpenRecentProjectAction): String =
        ProjectJugglerBundle.message("popup.recent.projects.action.remove")
}
