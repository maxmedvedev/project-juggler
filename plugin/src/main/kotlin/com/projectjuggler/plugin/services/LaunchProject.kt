package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.process.OpenStep
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.describe
import com.projectjuggler.process.planOpen
import java.nio.file.Path

private val LOG = fileLogger()

internal enum class LaunchStep { OPEN_IN_CURRENT_IDE, FOCUS, LAUNCH }

/**
 * The single step `launchOrFocusProject` performs for [projectPath]: [LaunchStep.OPEN_IN_CURRENT_IDE] when the
 * project belongs to the current IDE ([belongsToCurrentIde]), the main project in the main instance or the project
 * this isolated IDE was started for; otherwise the step [planOpen] picks.
 *
 * Planning creates no directories.
 */
internal fun planLaunch(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    currentConfigDir: Path,
): LaunchStep =
    if (belongsToCurrentIde(repository, projectPath, currentConfigDir)) LaunchStep.OPEN_IN_CURRENT_IDE
    else when (planOpen(repository, projectPath)) {
        OpenStep.FOCUS -> LaunchStep.FOCUS
        OpenStep.LAUNCH -> LaunchStep.LAUNCH
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
                failure.describe().ifBlank { ProjectJugglerBundle.message("notification.error.launch.no.output") }
            )
            notificationHandler.showErrorNotification(message)
        }
    } catch (ex: Throwable) {
        ex.rethrowIfControlFlow()
        LOG.warn("Failed to launch ${projectPath.name}", ex)
        val message =
            ProjectJugglerBundle.message("notification.error.launch.failed", ex.userMessage())
        notificationHandler.showErrorNotification(message)
    }
}
