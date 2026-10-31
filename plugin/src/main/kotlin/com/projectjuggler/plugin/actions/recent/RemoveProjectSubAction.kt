package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.removeRecentProject
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

/** Remove project from tracking, after confirmation. Not offered for the main project. */
object RemoveProjectSubAction : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? = step.doFinalStep { confirmAndRemove(project, item.projectPath) }

    /** Runs on EDT after the popup closed; the deletion and the popup reshow run in a background task. */
    private fun confirmAndRemove(project: Project?, projectPath: ProjectPath) {
        val repository = IdeInstallationService.currentIdeConfigRepository
        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(projectPath)

        val result = Messages.showOkCancelDialog(
            project,
            ProjectJugglerBundle.message("dialog.confirm.remove.project.message", projectPath.name, projectRoot),
            ProjectJugglerBundle.message("dialog.confirm.remove.project.title"),
            ProjectJugglerBundle.message("dialog.confirm.remove.project.ok"),
            Messages.getCancelButton(),
            Messages.getWarningIcon()
        )
        if (result != Messages.OK) return

        val task = object : Task.Backgroundable(
            project,
            ProjectJugglerBundle.message("progress.removing.project", projectPath.name),
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                removeRecentProject(projectPath, repository, IntelliJNotificationHandler(project))
                RecentProjectPopupBuilder(project).show()
            }
        }
        task.queue()
    }

    override fun getTextFor(item: OpenRecentProjectAction): String =
        ProjectJugglerBundle.message("popup.recent.projects.action.remove")
}
