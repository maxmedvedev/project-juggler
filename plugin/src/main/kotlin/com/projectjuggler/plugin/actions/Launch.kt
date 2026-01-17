package com.projectjuggler.plugin.actions

import com.intellij.openapi.project.Project
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.util.IdeJuggler
import com.projectjuggler.util.ProjectLockUtils

/**
 * Handles launching or focusing a project based on whether it's already open.
 * If project is open, attempts to focus the window.
 * If project is closed, launches it normally.
 */
fun launchOrFocusProject(
    project: Project?,
    projectPath: ProjectPath,
    repository: IdeConfigRepository
) {
    val isOpen = ProjectLockUtils.isProjectOpen(repository, projectPath)

    if (isOpen) {
        // Try to focus the existing window
        IdeJuggler.focusExistingProject(project, repository, projectPath)
    } else {
        // Launch new instance
        IdeJuggler.launchProject(project, repository, projectPath)
    }
}
