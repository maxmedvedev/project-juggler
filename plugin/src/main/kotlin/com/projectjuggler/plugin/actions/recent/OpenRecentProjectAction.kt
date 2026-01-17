package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.actions.launchOrFocusProject

data class OpenRecentProjectAction(
    val projectPath: ProjectPath,
    val gitBranch: String?, // null if not a git repo or error occurred
    val isOpen: Boolean = false, // true if project has running instance
    val isMainProject: Boolean = false, // true if this is the configured main project
) : RecentProjectPopupAction {
    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        launchOrFocusProject(project, projectPath)
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String = buildString {
        // Format: "ProjectName - [branch] - /path/to/project"
        val name = projectPath.name
        val path = projectPath.pathString
        append(name)
        if (gitBranch != null) {
            append(" - [")
            append(gitBranch)
            append("]")
        }
        append(" - ")
        append(path)
    }

    override fun getIndexedString(): String = buildString {
        append(projectPath.name)
        append(" ")
        gitBranch?.let {
            append(it)
            append(" ")
        }
        append(projectPath.pathString)
    }
}
