package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle

fun clearMainProject(
    notificationHandler: NotificationHandler,
    repository: IdeConfigRepository,
) {
    MainProjectService.clearMainProject(repository)
    notificationHandler.showInfoNotification(
        ProjectJugglerBundle.message("notification.success.unset.main"),
    )
}
