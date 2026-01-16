package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.ControlFlowException
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.process.WindowFocuser
import com.projectjuggler.util.ProjectLockUtils

fun focusExistingProject(
    projectPath: ProjectPath,
    ideConfigRepository: IdeConfigRepository,
    notificationHandler: NotificationHandler
) {
    try {
        // Read PID from lock file
        val pid = ProjectLockUtils.readPidFromLock(ideConfigRepository, projectPath)
        if (pid == null) {
            notificationHandler.showErrorNotification(
                ProjectJugglerBundle.message("notification.error.focus.no.pid", projectPath.name),
            )
            return
        }

        // Verify process is still running
        if (!ProjectLockUtils.isProcessRunning(pid)) {
            notificationHandler.showErrorNotification(
                ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, pid),
            )
            return
        }

        // Attempt to focus window
        when (val result = WindowFocuser.focus(pid)) {
            is WindowFocuser.FocusResult.Success -> {}
            is WindowFocuser.FocusResult.ProcessNotFound -> {
                notificationHandler.showErrorNotification(
                    ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, result.pid),
                )
            }

            is WindowFocuser.FocusResult.WindowNotFound -> {
                notificationHandler.showErrorNotification(
                    ProjectJugglerBundle.message("notification.error.focus.window.not.found", projectPath.name),
                )
            }

            is WindowFocuser.FocusResult.CommandFailed -> {
                notificationHandler.showErrorNotification(
                    ProjectJugglerBundle.message("notification.error.focus.failed", projectPath.name, result.error),
                )
            }

            is WindowFocuser.FocusResult.ToolNotInstalled -> {
                notificationHandler.showErrorNotification(
                    ProjectJugglerBundle.message("notification.error.focus.tool.missing", result.toolName),
                )
            }
        }
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e

        notificationHandler.showErrorNotification(
            ProjectJugglerBundle.message("notification.error.focus.exception", projectPath.name, e.message ?: "Unknown error"),
        )
    }
}
