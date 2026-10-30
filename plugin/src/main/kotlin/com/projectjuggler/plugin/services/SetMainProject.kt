package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.ProjectCleaner
import com.projectjuggler.plugin.ProjectJugglerBundle

fun setMainProject(
    projectPath: ProjectPath,
    notificationHandler: NotificationHandler,
    repository: IdeConfigRepository
) {
    MainProjectService.setMainProject(repository, projectPath)
    // The project may have been tracked as an isolated project before; that entry is now stale
    ProjectCleaner.getInstance(repository).cleanStaleMainProjectEntry()
    notificationHandler.showInfoNotification(
        ProjectJugglerBundle.message("notification.success.set.main", projectPath.name),
    )
}
