package com.projectjuggler.plugin.util

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.focusExistingProject

internal object IdeJuggler {
    /**
     * Launches a project with Project Juggler in a background thread and shows notifications.
     *
     * @param project The current IDE project (for notifications, can be null)
     * @param ideConfigRepository The IDE-specific configuration repository
     * @param projectPath The path to the project to launch
     */
    fun launchProject(
        project: Project?,
        ideConfigRepository: IdeConfigRepository,
        projectPath: ProjectPath,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Launching ${projectPath.name}...") {
            override fun run(indicator: ProgressIndicator) {
                com.projectjuggler.plugin.services.launchProject(projectPath, ideConfigRepository, IntelliJNotificationHandler(project))
            }
        })
    }

    /**
     * Attempts to focus an already-open project window.
     * Shows error notification if focus fails.
     */
    fun focusExistingProject(
        project: Project?,
        ideConfigRepository: IdeConfigRepository,
        projectPath: ProjectPath
    ) {
        val task = object : Task.Backgroundable(
            project,
            ProjectJugglerBundle.message("progress.focusing.project", projectPath.name),
            false
        ) {
            override fun run(indicator: ProgressIndicator) =
                focusExistingProject(projectPath, ideConfigRepository, IntelliJNotificationHandler(project))
        }
        task.queue()
    }
}
