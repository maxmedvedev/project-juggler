package com.projectjuggler.plugin.actions.recent

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.importProjects
import com.projectjuggler.plugin.services.importableProjects

object ImportRecentProjectsAction : RecentProjectPopupAction {
    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        step.doFinalStep {
            importRecentProjects(project)
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String =
        ProjectJugglerBundle.message("popup.import.recent.projects.label")

    private fun importRecentProjects(project: Project?) {
        // Get IntelliJ's recent projects
        val manager = RecentProjectsManager.getInstance() as RecentProjectsManagerBase
        val projects = importableProjects(manager.getRecentPaths())

        if (projects.isEmpty()) {
            Messages.showInfoMessage(
                project,
                ProjectJugglerBundle.message("dialog.import.projects.no.projects"),
                ProjectJugglerBundle.message("dialog.import.projects.title")
            )
            return
        }

        // Ask for confirmation
        val result = Messages.showYesNoDialog(
            project,
            ProjectJugglerBundle.message("dialog.import.projects.confirm", projects.size),
            ProjectJugglerBundle.message("dialog.import.projects.title"),
            Messages.getQuestionIcon()
        )

        if (result != Messages.YES) {
            return
        }

        application.executeOnPooledThread {
            importProjects(IdeInstallationService.currentIdeConfigRepository, projects)

            RecentProjectPopupBuilder(project).show()
        }
    }
}
