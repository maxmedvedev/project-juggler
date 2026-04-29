package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService

internal class ToggleSyncOnUpdateAction(
    private val enabled: Boolean,
) : RecentProjectPopupAction {

    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        step.doFinalStep {
            val repository = IdeInstallationService.currentIdeConfigRepository
            repository.update { it.copy(syncPluginsOnIdeUpdate = !enabled) }
            application.executeOnPooledThread {
                RecentProjectPopupBuilder(project).show()
            }
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String = if (enabled) {
        ProjectJugglerBundle.message("popup.toggle.sync.on.update.enabled")
    } else {
        ProjectJugglerBundle.message("popup.toggle.sync.on.update.disabled")
    }
}
