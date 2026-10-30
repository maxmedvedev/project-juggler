package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.describe
import com.projectjuggler.util.ProjectLockUtils
import java.nio.file.Path

internal enum class LaunchStep { OPEN_IN_CURRENT_IDE, FOCUS, LAUNCH }

/**
 * Steps `launchOrFocusProject` performs for [projectPath], in order.
 *
 * [LaunchStep.OPEN_IN_CURRENT_IDE] is planned when the project's isolated `config` dir is [currentConfigDir],
 * i.e. the user reopens the project this isolated IDE was started for. It is always followed by
 * [LaunchStep.FOCUS] or [LaunchStep.LAUNCH] (plugin-spec gap 1). Checking a registered project creates its
 * isolated directories as a side effect.
 */
internal fun planLaunch(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    currentConfigDir: Path,
): List<LaunchStep> = buildList {
    if (isSameIdeInstance(repository, projectPath, currentConfigDir)) add(LaunchStep.OPEN_IN_CURRENT_IDE)
    add(if (ProjectLockUtils.isProjectOpen(repository, projectPath)) LaunchStep.FOCUS else LaunchStep.LAUNCH)
}

/**
 * Checks if the project corresponds to the current IDE instance.
 * This can happen if the project was closed and now user wants to reopen it.
 */
private fun isSameIdeInstance(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    currentConfigDir: Path,
): Boolean {
    val projectMetadata = ProjectManager.getInstance(repository).get(projectPath) ?: return false
    val directories = DirectoryManager.getInstance(repository).ensureProjectDirectories(projectMetadata)
    return directories.config == currentConfigDir
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
