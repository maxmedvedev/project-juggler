package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IdePathSyncDetector

/**
 * Popup action shown when IDE paths are out of sync.
 * Clicking it updates the stored paths to match the current IDE.
 */
internal class UpdatePathsAction(
    val mismatches: List<String>,
) : RecentProjectPopupAction {

    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        step.doFinalStep {
            IdePathSyncDetector.updatePaths(IdeInstallationService.currentIdeConfigRepository, project)
            application.executeOnPooledThread {
                RecentProjectPopupBuilder(project).show()
            }
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String =
        ProjectJugglerBundle.message("popup.update.paths", mismatches.joinToString())
}
