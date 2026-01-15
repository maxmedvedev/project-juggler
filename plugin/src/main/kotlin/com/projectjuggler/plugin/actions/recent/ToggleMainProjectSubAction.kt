package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.MainProjectService
import com.projectjuggler.plugin.showInfoNotification

/** Toggle whether this is the main project */
object ToggleMainProjectSubAction : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        toggleMainProject(project, item.projectPath)
        return PopupStep.FINAL_CHOICE
    }

    private fun toggleMainProject(project: Project?, projectPath: ProjectPath) {
        val isMain = MainProjectService.isMainProject(currentIdeConfigRepository, projectPath)
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

            if (result == Messages.OK) {
                if (isMain) {
                    // todo move from EDT
                    MainProjectService.clearMainProject(currentIdeConfigRepository)
                    showInfoNotification(
                        ProjectJugglerBundle.message("notification.success.unset.main"),
                        project
                    )
                } else {
                    // todo move from EDT
                    MainProjectService.setMainProject(currentIdeConfigRepository, projectPath)
                    showInfoNotification(
                        ProjectJugglerBundle.message("notification.success.set.main", projectPath.name),
                        project
                    )
                }
            }
        }
    }

    override fun getTextFor(item: OpenRecentProjectAction): String {
        val isMain = MainProjectService.isMainProject(currentIdeConfigRepository, item.projectPath)
        return when {
            isMain -> ProjectJugglerBundle.message("popup.recent.projects.action.unset.main")
            else -> ProjectJugglerBundle.message("popup.recent.projects.action.set.main")
        }
    }
}
