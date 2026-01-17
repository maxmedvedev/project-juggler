package com.projectjuggler.plugin.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.focusExistingProject
import com.projectjuggler.plugin.services.launchProject
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import com.projectjuggler.util.ProjectLockUtils

/**
 * Handles launching or focusing a project based on whether it's already open.
 * If project is open, attempts to focus the window.
 * If project is closed, launches it normally.
 */
fun launchOrFocusProject(
    project: Project?,
    projectPath: ProjectPath
) {
    val task = object : Task.Backgroundable(
        project,
        ProjectJugglerBundle.message("progress.opening.project", projectPath.name),
        false
    ) {
        override fun run(indicator: ProgressIndicator) = launchOrFocusUnderProgress(project, projectPath)
    }
    task.queue()
}

private fun launchOrFocusUnderProgress(project: Project?, projectPath: ProjectPath) {
    val repository = IdeInstallationService.currentIdeConfigRepository
    val isOpen = ProjectLockUtils.isProjectOpen(repository, projectPath)

    val notificationHandler = IntelliJNotificationHandler(project)
    if (isOpen) {
        focusExistingProject(
            projectPath = projectPath,
            ideConfigRepository = repository,
            notificationHandler = notificationHandler
        )
    } else {
        launchProject(
            projectPath = projectPath,
            ideConfigRepository = repository,
            notificationHandler = notificationHandler
        )
    }
}
