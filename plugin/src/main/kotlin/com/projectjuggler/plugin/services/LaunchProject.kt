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
        ProjectLauncher.getInstance(ideConfigRepository).launch(projectPath)
    } catch (ex: Throwable) {
        val message =
            ProjectJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error")
        notificationHandler.showErrorNotification(message)
    }
}
