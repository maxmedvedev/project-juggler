package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IdePathKind
import com.projectjuggler.plugin.services.IdePathSyncDetector
import com.projectjuggler.plugin.services.joinDisplayNames
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

/**
 * Popup action shown when IDE paths are out of sync.
 * Clicking it updates the stored paths to match the current IDE.
 */
internal class UpdatePathsAction(
    val mismatches: List<IdePathKind>,
) : RecentProjectPopupAction {

    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        step.doFinalStep {
            application.executeOnPooledThread {
                IdePathSyncDetector.updatePaths(
                    IdeInstallationService.currentIdeConfigRepository,
                    IntelliJNotificationHandler(project),
                )
                RecentProjectPopupBuilder(project).show()
            }
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String =
        ProjectJugglerBundle.message("popup.update.paths", mismatches.joinDisplayNames())
}
