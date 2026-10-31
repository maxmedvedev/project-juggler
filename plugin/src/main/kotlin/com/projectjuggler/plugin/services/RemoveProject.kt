package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.ProjectCleaner
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.util.ProjectLockUtils

private val LOG = fileLogger()

/**
 * Removes the project's isolated data and recent entry. Refuses while its isolated IDE runs, because deleting the
 * isolated directories under a live instance corrupts it.
 *
 * @return true if the project was removed, false if it was unknown, running, or deletion failed
 */
internal fun removeRecentProject(
    projectPath: ProjectPath,
    repository: IdeConfigRepository,
    notificationHandler: NotificationHandler,
): Boolean {
    val metadata = ProjectManager.getInstance(repository).get(projectPath) ?: return false

    if (ProjectLockUtils.isIsolatedInstanceOpen(repository, projectPath)) {
        notificationHandler.showErrorNotification(
            ProjectJugglerBundle.message("notification.error.remove.project.running", projectPath.name)
        )
        return false
    }

    return try {
        // Removes the recent entry first, so a failed deletion still drops the project from the popup
        ProjectCleaner.getInstance(repository).cleanProject(metadata)
        true
    } catch (e: Throwable) {
        e.rethrowIfControlFlow()
        LOG.warn("Failed to remove project ${projectPath.name}", e)
        notificationHandler.showErrorNotification(
            ProjectJugglerBundle.message("notification.error.remove.project.failed", projectPath.name, e.userMessage())
        )
        false
    }
}
