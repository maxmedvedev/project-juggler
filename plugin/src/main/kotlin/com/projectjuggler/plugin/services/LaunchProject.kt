package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.util.IdeJuggler
import com.projectjuggler.process.ProjectLauncher

fun launchProject(
    projectPath: ProjectPath,
    ideConfigRepository: IdeConfigRepository,
    notificationHandler: NotificationHandler
) {
    try {
        IdeInstallationService.getInstance().autoPopulateIfNeeded(ideConfigRepository)
        val launcher = ProjectLauncher.getInstance(ideConfigRepository)
        launcher.launch(projectPath)
    } catch (ex: Exception) {
        val message = ProjectJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error")
        notificationHandler.showErrorNotification(message)
        logger<IdeJuggler>().error(message, ex)
    }
}