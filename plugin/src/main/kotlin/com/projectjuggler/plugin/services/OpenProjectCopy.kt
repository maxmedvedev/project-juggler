package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.util.GitWorktreeManager
import com.projectjuggler.util.PathUtils
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private val LOG = fileLogger()

/** The input fields of the Open a Project Copy dialog. */
internal enum class ProjectCopyField { SOURCE, BRANCH, DESTINATION }

/** Raw dialog text, untrimmed. */
internal data class ProjectCopyInput(val source: String, val branchName: String, val destination: String)

/** Validated input: resolved paths and the trimmed branch name. */
internal data class ProjectCopyRequest(val source: ProjectPath, val destination: ProjectPath, val branchName: String)

internal sealed interface ProjectCopyValidation {
    data class Valid(val request: ProjectCopyRequest) : ProjectCopyValidation
    data class Invalid(val field: ProjectCopyField, val message: String) : ProjectCopyValidation
}

private class InvalidInput(val field: ProjectCopyField, override val message: String) : Exception(message)

/**
 * Validates the dialog input. Pure apart from at most three local file stats; never runs a git process,
 * so it is cheap enough for EDT.
 *
 * Checks in field order: source (non-blank, valid path, directory, git repository), branch (non-blank),
 * destination (non-blank, valid path, does not exist). Paths resolve like `ProjectManager.resolvePath`.
 */
internal fun validateProjectCopyInput(input: ProjectCopyInput): ProjectCopyValidation = try {
    val sourceText = input.source.trim()
    val source = resolveField(ProjectCopyField.SOURCE, sourceText, "dialog.project.copy.error.blank.source")
    when {
        !source.isDirectory() ->
            throw InvalidInput(ProjectCopyField.SOURCE, ProjectJugglerBundle.message("notification.error.not.directory", sourceText))
        !GitWorktreeManager.isGitRepository(source) ->
            throw InvalidInput(ProjectCopyField.SOURCE, ProjectJugglerBundle.message("notification.error.not.git.repository", sourceText))
    }

    val branchName = input.branchName.trim()
    if (branchName.isEmpty()) {
        throw InvalidInput(ProjectCopyField.BRANCH, ProjectJugglerBundle.message("dialog.project.copy.error.blank.branch"))
    }

    val destinationText = input.destination.trim()
    val destination = resolveField(ProjectCopyField.DESTINATION, destinationText, "dialog.project.copy.error.blank.destination")
    if (destination.exists()) {
        throw InvalidInput(
            ProjectCopyField.DESTINATION,
            ProjectJugglerBundle.message("notification.error.destination.exists", destinationText)
        )
    }

    ProjectCopyValidation.Valid(
        ProjectCopyRequest(ProjectPath(source.toString()), ProjectPath(destination.toString()), branchName)
    )
} catch (e: InvalidInput) {
    ProjectCopyValidation.Invalid(e.field, e.message)
}

private fun resolveField(field: ProjectCopyField, text: String, blankKey: String): Path {
    if (text.isEmpty()) throw InvalidInput(field, ProjectJugglerBundle.message(blankKey))
    return try {
        PathUtils.resolveAbsolute(text)
    } catch (_: InvalidPathException) {
        throw InvalidInput(field, ProjectJugglerBundle.message("dialog.project.copy.error.invalid.path", text))
    }
}

/**
 * The default worktree folder: `<source parent>/<source name>-<branch with '/' replaced by '-'>`.
 *
 * @return null when the source or branch is blank, or the source is not a valid path with a parent
 */
internal fun suggestDestination(source: String, branchName: String): String? {
    val branch = branchName.trim().replace('/', '-')
    if (source.isBlank() || branch.isEmpty()) return null
    val sourcePath = try {
        PathUtils.resolveAbsolute(source.trim())
    } catch (_: InvalidPathException) {
        return null
    }
    val name = sourcePath.fileName ?: return null
    return sourcePath.resolveSibling("$name-$branch").toString()
}

/**
 * Runs the Open a Project Copy flow after the dialog closed. Call on a pooled thread.
 *
 * Re-validates [input] (authoritative; the filesystem may have changed since the dialog), then creates
 * the worktree and launches it through [createAndLaunchProjectCopy]. Reports through [notificationHandler].
 */
internal fun openProjectCopy(
    input: ProjectCopyInput,
    notificationHandler: NotificationHandler,
    repository: () -> IdeConfigRepository = ::currentRepository,
) {
    try {
        val request = when (val validation = validateProjectCopyInput(input)) {
            is ProjectCopyValidation.Invalid -> {
                notificationHandler.showErrorNotification(validation.message)
                return
            }
            is ProjectCopyValidation.Valid -> validation.request
        }
        val ideConfigRepository = repository()

        notificationHandler.showInfoNotification(
            ProjectJugglerBundle.message("notification.info.worktree.started", request.source.fileName)
        )
        createAndLaunchProjectCopy(ideConfigRepository, request, notificationHandler)
    } catch (t: Throwable) {
        t.rethrowIfControlFlow()
        reportWorktreeFailure(t, notificationHandler)
    }
}

private fun currentRepository(): IdeConfigRepository {
    KoinInit.init(pluginModule)
    return IdeInstallationService.currentIdeConfigRepository
}

/**
 * Creates a git worktree of the request's source at its destination on the new branch and launches it.
 * Reports success or failure through [notificationHandler].
 */
internal fun createAndLaunchProjectCopy(
    repository: IdeConfigRepository,
    request: ProjectCopyRequest,
    notificationHandler: NotificationHandler,
    launcher: ProjectLauncher = ProjectLauncher.getInstance(repository),
) {
    val (source, destination, branchName) = request
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
        t.rethrowIfControlFlow()
        reportWorktreeFailure(t, notificationHandler)
    }
}

private fun reportWorktreeFailure(t: Throwable, notificationHandler: NotificationHandler) {
    val message = ProjectJugglerBundle.message("notification.error.worktree.failed", t.userMessage())
    notificationHandler.showErrorNotification(message)
    LOG.error(message, t)
}
