package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.describe
import com.projectjuggler.util.ProjectLockUtils
import java.nio.file.Path

internal enum class LaunchStep { OPEN_IN_CURRENT_IDE, FOCUS, LAUNCH }

/**
 * The single step `launchOrFocusProject` performs for [projectPath].
 *
 * - [LaunchStep.OPEN_IN_CURRENT_IDE] when the project belongs to the current IDE ([belongsToCurrentIde]): the main
 *   project in the main instance, or the project this isolated IDE was started for.
 * - [LaunchStep.LAUNCH] for the main project otherwise: a running main IDE opens or focuses it, which a lock-based
 *   focus could not do.
 * - [LaunchStep.FOCUS] when the project's IDE is running, [LaunchStep.LAUNCH] when it is not.
 *
 * Planning creates no directories.
 */
internal fun planLaunch(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    currentConfigDir: Path,
): LaunchStep = when {
    belongsToCurrentIde(repository, projectPath, currentConfigDir) -> LaunchStep.OPEN_IN_CURRENT_IDE
    MainProjectService.isMainProject(repository, projectPath) -> LaunchStep.LAUNCH
    ProjectLockUtils.isProjectOpen(repository, projectPath) -> LaunchStep.FOCUS
    else -> LaunchStep.LAUNCH
}

internal fun launchProject(
    projectPath: ProjectPath,
    ideConfigRepository: IdeConfigRepository,
    notificationHandler: NotificationHandler,
    launcher: ProjectLauncher = ProjectLauncher.getInstance(ideConfigRepository),
) {
    try {
        launcher.launch(projectPath) { failure ->
            val message = ProjectJugglerBundle.message(
                "notification.error.launch.crashed",
                projectPath.name,
                failure.exitCode,
                failure.describe().ifBlank { "&lt;No output&gt;" }
            )
            notificationHandler.showErrorNotification(message)
        }
    } catch (ex: Throwable) {
        val message =
            ProjectJugglerBundle.message("notification.error.launch.failed", ex.message ?: "Unknown error")
        notificationHandler.showErrorNotification(message)
    }
}
