package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.clearMainProject
import com.projectjuggler.plugin.services.setMainProject
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

/**
 * Toggle whether this is the main project. The current state is [OpenRecentProjectAction.isMainProject], computed on
 * a background thread when the popup was built, so neither the label nor the confirmation reads the config.
 */
object ToggleMainProjectSubAction : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        toggleMainProject(project, item)
        return PopupStep.FINAL_CHOICE
    }

    private fun toggleMainProject(project: Project?, item: OpenRecentProjectAction) {
        val projectPath = item.projectPath
        val isMain = item.isMainProject
        application.invokeLater {
            val message = if (isMain) {
                ProjectJugglerBundle.message("dialog.confirm.unset.main.message", projectPath.name)
            } else {
                ProjectJugglerBundle.message("dialog.confirm.set.main.message", projectPath.name)
            }

            val title = if (isMain) {
                ProjectJugglerBundle.message("dialog.confirm.unset.main.title")
            } else {
                ProjectJugglerBundle.message("dialog.confirm.set.main.title")
            }

            val result = Messages.showOkCancelDialog(
                project,
                message,
                title,
                Messages.getOkButton(),
                Messages.getCancelButton(),
                Messages.getQuestionIcon()
            )

            if (result != Messages.OK) return@invokeLater

            application.executeOnPooledThread {
                if (isMain) {
                    clearMainProject(
                        IntelliJNotificationHandler(project),
                        IdeInstallationService.currentIdeConfigRepository
                    )
                } else {
                    setMainProject(
                        projectPath,
                        IntelliJNotificationHandler(project),
                        IdeInstallationService.currentIdeConfigRepository
                    )
                }
            }
        }
    }

    override fun getTextFor(item: OpenRecentProjectAction): String = when {
        item.isMainProject -> ProjectJugglerBundle.message("popup.recent.projects.action.unset.main")
        else -> ProjectJugglerBundle.message("popup.recent.projects.action.set.main")
    }
}
