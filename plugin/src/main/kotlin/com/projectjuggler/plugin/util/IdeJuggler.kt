package com.projectjuggler.plugin.util

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.platform.WindowFocuser
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.util.ProjectLockUtils

internal object IdeJuggler {
    /**
     * Launches a project with Project Juggler in a background thread and shows notifications.
     *
     * @param project The current IDE project (for notifications, can be null)
     * @param configRepository The configuration repository
     * @param projectPath The path to the project to launch
     */
    fun launchProject(
        project: Project?,
        configRepository: ConfigRepository,
        projectPath: ProjectPath,
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Launching ${projectPath.name}...") {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val launcher = ProjectLauncher.getInstance(configRepository)
                    launcher.launch(projectPath)
                } catch (ex: Exception) {
                    showErrorNotification(
                        ProjectJugglerBundle.message(
                            "notification.error.launch.failed",
                            ex.message ?: "Unknown error"
                        ), project
                    )
                    ex.printStackTrace()
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
        configRepository: ConfigRepository,
        projectPath: ProjectPath
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            ProjectJugglerBundle.message("progress.focusing.project", projectPath.name),
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Read PID from lock file
                    val pid = ProjectLockUtils.readPidFromLock(configRepository, projectPath)
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
        })
    }
}