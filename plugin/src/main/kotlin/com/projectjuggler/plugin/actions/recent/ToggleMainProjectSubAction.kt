package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.currentIdeConfigRepository
import com.projectjuggler.plugin.services.MainProjectService
import com.projectjuggler.plugin.services.clearMainProject
import com.projectjuggler.plugin.services.setMainProject
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

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

            if (result != Messages.OK) return@invokeLater

            // todo move from EDT
            if (isMain) {
                clearMainProject(IntelliJNotificationHandler(project), currentIdeConfigRepository)
            } else {
                setMainProject(projectPath, IntelliJNotificationHandler(project), currentIdeConfigRepository)
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
