package com.projectjuggler.plugin.actions

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.*
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

/**
 * Handles launching or focusing a project of [repository]'s IDE installation (the current one by default) based on
 * whether it's already open.
 * If main project is not set and running in main instance, prompts user to set this as main (only for the current
 * installation's repository).
 * If project is open, attempts to focus the window.
 * If project is closed, launches it normally.
 * Reads the repository, so call it on a pooled thread; the prompt hops to EDT by itself.
 */
@RequiresBackgroundThread
fun launchOrFocusProject(
    project: Project?,
    projectPath: ProjectPath,
    repository: IdeConfigRepository = IdeInstallationService.currentIdeConfigRepository,
) {
    val currentConfigDir = PathManager.getConfigDir()

    if (shouldPromptForMainProject(repository, IdeInstallationService.currentIdeConfigRepository, currentConfigDir)) {
        askToOpenAsMainProject(projectPath, repository, project)
    } else {
        launchOrFocusProjectImpl(project, projectPath, repository)
    }
}

private fun askToOpenAsMainProject(
    projectPath: ProjectPath,
    repository: IdeConfigRepository,
    project: Project?
) {
    application.invokeLater {
        val result = showMainProjectPrompt(projectPath, repository)
        application.executeOnPooledThread {
            when (result) {
                is MainProjectPromptResult.SetAsMain -> {
                    setMainProject(projectPath, IntelliJNotificationHandler(project), repository)
                    openProjectInCurrentIde(projectPath, project)
                }

                is MainProjectPromptResult.OpenIsolated -> {
                    // this is main IDE, so we
                    launchOrFocusProjectImpl(project, projectPath, repository)
                }

                is MainProjectPromptResult.Cancelled -> {
                    // User canceled, do nothing
                }
            }
        }
    }
}

private fun launchOrFocusProjectImpl(project: Project?, projectPath: ProjectPath, repository: IdeConfigRepository) {
    val task = object : Task.Backgroundable(
        project,
        ProjectJugglerBundle.message("progress.opening.project", projectPath.name),
        false
    ) {
        override fun run(indicator: ProgressIndicator) = launchOrFocusUnderProgress(project, projectPath, repository)
    }
    task.queue()
}

private fun launchOrFocusUnderProgress(project: Project?, projectPath: ProjectPath, repository: IdeConfigRepository) {
    val notificationHandler = IntelliJNotificationHandler(project)

    when (planLaunch(repository, projectPath, PathManager.getConfigDir())) {
        LaunchStep.OPEN_IN_CURRENT_IDE -> openProjectInCurrentIde(projectPath, project)
        LaunchStep.FOCUS -> focusExistingProject(
            projectPath = projectPath,
            ideConfigRepository = repository,
            notificationHandler = notificationHandler
        )

        LaunchStep.LAUNCH -> launchProject(
            projectPath = projectPath,
            ideConfigRepository = repository,
            notificationHandler = notificationHandler
        )
    }
}

/**
 * Opens a project in the current IDE instance using standard IntelliJ APIs.
 * If the project is already open here, its frame is focused; otherwise it opens in a new window within the same
 * IDE process (`forceOpenInNewFrame` skips the platform's own "already open" check, hence the explicit lookup).
 */
private fun openProjectInCurrentIde(projectPath: ProjectPath, currentProject: Project?) {
    application.executeOnPooledThread {
        ProjectUtil.findProject(projectPath.path)
            ?.let { application.invokeLater { ProjectUtil.focusProjectWindow(it) } }
            ?: ProjectUtil.openOrImport(projectPath.path, currentProject, true)
    }
}
