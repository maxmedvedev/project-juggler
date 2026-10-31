package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.PlatformRecentProjects
import com.projectjuggler.plugin.services.importProjects
import com.projectjuggler.plugin.services.importableProjects

object ImportRecentProjectsAction : RecentProjectPopupAction {
    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        step.doFinalStep {
            application.executeOnPooledThread {
                // Filtering stats every recent path, hence off the EDT
                val projects = importableProjects(PlatformRecentProjects.recentPaths())
                application.invokeLater { confirmAndImport(project, projects) }
            }
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String =
        ProjectJugglerBundle.message("popup.import.recent.projects.label")

    /** Runs on EDT: asks for confirmation, then imports on a pooled thread and reshows the popup. */
    private fun confirmAndImport(project: Project?, projects: List<ProjectPath>) {
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
