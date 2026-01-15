package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectCleaner
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle

/** Remove project from tracking */
object RemoveProjectSubAction : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        removeProject(item.projectPath)
        return step.doFinalStep {
            RecentProjectPopupBuilder(project).show()
        }
    }

    private fun removeProject(projectPath: ProjectPath) {
        val metadata = ProjectManager.Companion.getInstance(currentIdeConfigRepository).get(projectPath) ?: return

        // cleaning recent projects eagerly so that we can show a new recent popup right away
        RecentProjectsIndex.Companion.getInstance(currentIdeConfigRepository).remove(metadata.id)

        application.executeOnPooledThread {
            ProjectCleaner.Companion.getInstance(currentIdeConfigRepository).cleanProject(metadata)
        }
    }

    override fun getTextFor(item: OpenRecentProjectAction): String =
        ProjectJugglerBundle.message("popup.recent.projects.action.remove")
}
