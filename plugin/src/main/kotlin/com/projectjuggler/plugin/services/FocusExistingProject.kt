package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.process.WindowFocuser.FocusResult
import com.projectjuggler.process.focusRunningProject

private val LOG = fileLogger()

fun focusExistingProject(
    projectPath: ProjectPath,
    ideConfigRepository: IdeConfigRepository,
    notificationHandler: NotificationHandler
) {
    try {
        val error = when (val result = focusRunningProject(ideConfigRepository, projectPath)) {
            is FocusResult.Success -> null
            is FocusResult.LockNotFound ->
                ProjectJugglerBundle.message("notification.error.focus.no.pid", projectPath.name)
            is FocusResult.ProcessNotFound ->
                ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, result.pid)
            is FocusResult.WindowNotFound ->
                ProjectJugglerBundle.message("notification.error.focus.window.not.found", projectPath.name)
            is FocusResult.CommandFailed ->
                ProjectJugglerBundle.message("notification.error.focus.failed", projectPath.name, result.error)
            is FocusResult.ToolNotInstalled ->
                ProjectJugglerBundle.message("notification.error.focus.tool.missing", result.toolName)
        }
        error?.let(notificationHandler::showErrorNotification)
    } catch (t: Throwable) {
        t.rethrowIfControlFlow()
        LOG.warn("Failed to focus project ${projectPath.name}", t)
        notificationHandler.showErrorNotification(
            ProjectJugglerBundle.message("notification.error.focus.exception", projectPath.name, t.userMessage()),
        )
    }
}
