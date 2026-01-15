package com.projectjuggler.plugin.util

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.WindowFocuser
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.util.ProjectLockUtils

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
                try {
                    IdeInstallationService.getInstance().autoPopulateIfNeeded(ideConfigRepository)
                    val launcher = ProjectLauncher.getInstance(ideConfigRepository)
                    launcher.launch(projectPath)
                } catch (ex: Exception) {
                    val message = ProjectJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error")
                    showErrorNotification(message, project)
                    logger<IdeJuggler>().error(message, ex)
                }
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
                focusExistingProjectUnderProgress(project, ideConfigRepository, projectPath)
        }
        task.queue()
    }

    private fun focusExistingProjectUnderProgress(
        project: Project?,
        ideConfigRepository: IdeConfigRepository,
        projectPath: ProjectPath
    ) {
        try {
            // Read PID from lock file
            val pid = ProjectLockUtils.readPidFromLock(ideConfigRepository, projectPath)
            if (pid == null) {
                showErrorNotification(
                    ProjectJugglerBundle.message("notification.error.focus.no.pid", projectPath.name),
                    project
                )
                return
            }

            // Verify process is still running
            if (!ProjectLockUtils.isProcessRunning(pid)) {
                showErrorNotification(
                    ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, pid),
                    project
                )
                return
            }

            // Attempt to focus window
            when (val result = WindowFocuser.focus(pid)) {
                is WindowFocuser.FocusResult.Success -> {}
                is WindowFocuser.FocusResult.ProcessNotFound -> {
                    showErrorNotification(
                        ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, result.pid),
                        project
                    )
                }

                is WindowFocuser.FocusResult.WindowNotFound -> {
                    showErrorNotification(
                        ProjectJugglerBundle.message("notification.error.focus.window.not.found", projectPath.name),
                        project
                    )
                }

                is WindowFocuser.FocusResult.CommandFailed -> {
                    showErrorNotification(
                        ProjectJugglerBundle.message("notification.error.focus.failed", projectPath.name, result.error),
                        project
                    )
                }

                is WindowFocuser.FocusResult.ToolNotInstalled -> {
                    showErrorNotification(
                        ProjectJugglerBundle.message("notification.error.focus.tool.missing", result.toolName),
                        project
                    )
                }
            }
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e

            showErrorNotification(
                ProjectJugglerBundle.message("notification.error.focus.exception", projectPath.name, e.message ?: "Unknown error"),
                project
            )
        }
    }
}