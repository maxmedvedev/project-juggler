package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.process.ProjectLauncher

internal fun launchProject(
    projectPath: ProjectPath,
    ideConfigRepository: IdeConfigRepository,
    notificationHandler: NotificationHandler,
) {
    try {
        ProjectLauncher.getInstance(ideConfigRepository).launch(projectPath) { failure ->
            val errorOutput = failure.stderr.takeIf { it.isNotBlank() } ?: failure.stdout.takeIf { it.isNotBlank() } ?: "&lt;No output&gt;"
            val message = ProjectJugglerBundle.message(
                "notification.error.launch.crashed",
                projectPath.name,
                failure.exitCode,
                errorOutput.take(500) // Limit error message length
            )
            notificationHandler.showErrorNotification(message)
        }
    } catch (ex: Throwable) {
        val message =
            ProjectJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error")
        notificationHandler.showErrorNotification(message)
    }
}
