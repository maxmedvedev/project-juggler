package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle

fun setMainProject(
    projectPath: ProjectPath,
    notificationHandler: NotificationHandler,
    repository: IdeConfigRepository
) {
    MainProjectService.setMainProject(repository, projectPath)
    notificationHandler.showInfoNotification(
        ProjectJugglerBundle.message("notification.success.set.main", projectPath.name),
    )
}
