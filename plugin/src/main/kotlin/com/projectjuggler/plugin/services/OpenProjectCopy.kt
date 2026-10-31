package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.util.GitWorktreeManager
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private val LOG = fileLogger()

/**
 * Checks that a copy of [source] can be created at [destination].
 *
 * @return the error message for the first failing check (source is not a directory, source is not a
 * git repository, destination exists), or null when the copy can be created
 */
internal fun validateProjectCopy(source: ProjectPath, destination: ProjectPath): String? = when {
    !source.path.isDirectory() ->
        ProjectJugglerBundle.message("notification.error.not.directory", source.pathString)
    !GitWorktreeManager.isGitRepository(source.path) ->
        ProjectJugglerBundle.message("notification.error.not.git.repository", source.pathString)
    destination.path.exists() ->
        ProjectJugglerBundle.message("notification.error.destination.exists", destination.pathString)
    else -> null
}

/**
 * Creates a git worktree of [source] at [destination] on the new branch [branchName] and launches it.
 * Reports success or failure through [notificationHandler].
 */
internal fun createAndLaunchProjectCopy(
    repository: IdeConfigRepository,
    source: ProjectPath,
    destination: ProjectPath,
    branchName: String,
    notificationHandler: NotificationHandler,
    launcher: ProjectLauncher = ProjectLauncher.getInstance(repository),
) {
    try {
        // Create git worktree (no progress messages in plugin context)
        GitWorktreeManager.createWorktree(
            source = source.path,
            destination = destination.path,
            branchName = branchName,
            messageOutput = null
        )

        launcher.launch(destination)

        notificationHandler.showInfoNotification(
            ProjectJugglerBundle.message("notification.success.worktree.launched", destination.fileName, branchName)
        )
    } catch (t: Throwable) {
        if (t is ControlFlowException) throw t
        val message = ProjectJugglerBundle.message("notification.error.worktree.failed", t.userMessage())
        notificationHandler.showErrorNotification(message)
        LOG.error(message, t)
    }
}
