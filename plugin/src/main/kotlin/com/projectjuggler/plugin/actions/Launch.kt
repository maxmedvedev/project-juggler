package com.projectjuggler.plugin.actions

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.application
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.*
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import com.projectjuggler.util.ProjectLockUtils

/**
 * Handles launching or focusing a project based on whether it's already open.
 * If main project is not set and running in main instance, prompts user to set this as main.
 * If project is open, attempts to focus the window.
 * If project is closed, launches it normally.
 */
fun launchOrFocusProject(
    project: Project?,
    projectPath: ProjectPath
) {
    val repository = IdeInstallationService.currentIdeConfigRepository
    val currentConfigDir = PathManager.getConfigDir()

    if (MainProjectService.shouldPromptForMainProject(repository, currentConfigDir)) {
        askToOpenAsMainProject(projectPath, repository, project)
    } else {
        launchOrFocusProjectImpl(project, projectPath)
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
                    launchOrFocusProjectImpl(project, projectPath)
                }

                is MainProjectPromptResult.Cancelled -> {
                    // User canceled, do nothing
                }
            }
        }
    }
}

private fun launchOrFocusProjectImpl(project: Project?, projectPath: ProjectPath) {
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

    if (isSameIdeInstance(repository, projectPath)) {
        openProjectInCurrentIde(projectPath, project)
    }

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

/**
 * Checks if the project corresponds to the current IDE instance.
 * This can happen if the project was closed and now user wants to reopen it.
 */
private fun isSameIdeInstance(
    repository: IdeConfigRepository,
    projectPath: ProjectPath
): Boolean {
    val currentConfigDir = PathManager.getConfigDir()
    val projectMetadata = ProjectManager.getInstance(repository).get(projectPath) ?: return false
    val directories = DirectoryManager.getInstance(repository).ensureProjectDirectories(projectMetadata)
    return directories.config == currentConfigDir
}

/**
 * Opens a project using standard IntelliJ APIs in the current IDE instance.
 * This opens the project in a new window within the same IDE process.
 */
private fun openProjectInCurrentIde(projectPath: ProjectPath, currentProject: Project?) {
    application.executeOnPooledThread {
        ProjectUtil.openOrImport(projectPath.path, currentProject, true)
    }
}
