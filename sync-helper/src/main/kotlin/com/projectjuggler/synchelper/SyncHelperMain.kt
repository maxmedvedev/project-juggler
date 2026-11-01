package com.projectjuggler.synchelper

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SelfSyncResult
import com.projectjuggler.core.SelfSyncResultStore
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.core.SyncTypeNames
import com.projectjuggler.di.KoinInit
import com.projectjuggler.process.OpenStep
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.WindowFocuser
import com.projectjuggler.process.WindowFocuser.FocusResult
import com.projectjuggler.process.focusRunningProject
import com.projectjuggler.process.planOpen
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Project Juggler's out-of-process helper (see [USAGE]).
 *
 * - `sync`: the plugin starts it for self-shutdown syncs, to sync the isolated project the running IDE belongs to
 *   from outside. The helper stops that IDE through the stop request, syncs and relaunches it, and leaves a
 *   [SelfSyncResult] for the relaunched IDE to report.
 * - `open`: lets other apps launch a project, or focus its window when its IDE already runs, like the plugin's
 *   Recent Projects popup does.
 *
 * Either command runs in the helper the `--ide` IDE recorded for itself when that is another one ([HelperDispatcher]).
 */
fun main(args: Array<String>) {
    KoinInit.init()
    exitProcess(run(args))
}

internal const val EXIT_OK = 0
internal const val EXIT_FAILURE = 1
internal const val EXIT_USAGE = 2

/**
 * Runs the command of [args] and returns the exit code: [EXIT_OK] when done, [EXIT_FAILURE] when anything after
 * argument parsing fails, [EXIT_USAGE] for invalid arguments (usage printed to stderr). Once a sync's project is
 * known, its outcome is written to the project's [SelfSyncResultStore]. A command [dispatcher] hands over to another
 * helper returns that helper's exit code.
 */
internal fun run(
    args: Array<String>,
    registry: IdeRegistry = IdeRegistry.getInstance(),
    launcherFor: (IdeConfigRepository) -> ProjectLauncher = { ProjectLauncher.getInstance(it) },
    focus: (Int) -> FocusResult = WindowFocuser::focus,
    dispatcher: HelperDispatcher = HelperDispatcher(),
): Int {
    val command = try {
        parseArgs(args)
    } catch (e: UsageException) {
        System.err.println("sync-helper: ${e.message}")
        System.err.println(USAGE)
        return EXIT_USAGE
    }
    println("[sync-helper] base dir ${IdeRegistry.getDefaultBaseDir()}, args ${args.joinToString(" ")}")

    val repository = registry.findRepository(command.idePath)
        ?: return fail("No Project Juggler data for IDE ${command.idePath}")
    dispatcher.dispatch(repository, args)?.let { return it }
    return when (command) {
        is HelperCommand.Sync -> runSync(command, repository, launcherFor(repository))
        is HelperCommand.Open -> runOpen(command, repository, launcherFor, focus)
    }
}

private fun runSync(command: HelperCommand.Sync, repository: IdeConfigRepository, launcher: ProjectLauncher): Int {
    val projectManager = ProjectManager.getInstance(repository)
    val project = projectManager.get(projectManager.resolvePath(command.projectPath))
        ?: return fail("Project not found: ${command.projectPath}")

    var restartError: String? = null
    val options = SyncOptions(
        stopIfRunning = true,
        autoRestart = true,
        shutdownTimeout = 60,
        onProgress = { progress ->
            println("[sync-helper] ${describe(progress)}")
            if (progress is SyncProgress.Error) restartError = progress.message
        },
    )
    val syncTypes = SyncTypeNames.of(command.syncVmOptions, command.syncConfig, command.syncPlugins)

    return try {
        launcher.syncProject(
            project, command.syncVmOptions, command.syncConfig, command.syncPlugins, options,
        )
        println("[sync-helper] Synced ${syncTypes.joinToString(", ")} for ${project.name}")
        writeResult(repository, project, SelfSyncResult(now(), syncTypes, succeeded = true, restartError = restartError))
        EXIT_OK
    } catch (e: Throwable) {
        e.printStackTrace()
        val message = e.message ?: e.javaClass.name
        writeResult(
            repository, project,
            SelfSyncResult(now(), syncTypes, succeeded = false, message = message, restartError = restartError),
        )
        EXIT_FAILURE
    }
}

/** Focuses the project's running IDE, or launches the project ([planOpen]); writes no result file. */
private fun runOpen(
    command: HelperCommand.Open,
    repository: IdeConfigRepository,
    launcherFor: (IdeConfigRepository) -> ProjectLauncher,
    focus: (Int) -> FocusResult,
): Int {
    val projectManager = ProjectManager.getInstance(repository)
    if (!projectManager.validatePathExists(command.projectPath)) {
        return fail("Path does not exist: ${command.projectPath}")
    }
    val projectPath = projectManager.resolvePath(command.projectPath)

    return try {
        when (planOpen(repository, projectPath)) {
            OpenStep.FOCUS -> {
                val error = describeFocusFailure(focusRunningProject(repository, projectPath, focus))
                if (error != null) return fail("Cannot focus ${projectPath.name}: $error")
                println("[sync-helper] Focused ${projectPath.name}")
            }
            OpenStep.LAUNCH -> {
                launcherFor(repository).launch(projectPath)
                println("[sync-helper] Launched ${projectPath.name}")
            }
        }
        EXIT_OK
    } catch (e: Throwable) {
        e.printStackTrace()
        fail("Failed to open ${projectPath.name}: ${e.message ?: e.javaClass.name}")
    }
}

/** Why focusing failed, or `null` on [FocusResult.Success]. */
private fun describeFocusFailure(result: FocusResult): String? = when (result) {
    FocusResult.Success -> null
    FocusResult.LockNotFound -> "its lock file holds no process id"
    is FocusResult.ProcessNotFound -> "process ${result.pid} is not running"
    is FocusResult.WindowNotFound -> "process ${result.pid} has no window"
    is FocusResult.CommandFailed -> result.error
    is FocusResult.ToolNotInstalled -> "${result.toolName} is not installed"
}

private fun fail(message: String): Int {
    System.err.println("sync-helper: $message")
    return EXIT_FAILURE
}

/** Writes [result] for [project]; a failure is printed and does not change the exit code. */
private fun writeResult(repository: IdeConfigRepository, project: ProjectMetadata, result: SelfSyncResult) {
    try {
        SelfSyncResultStore.getInstance(repository).write(project, result)
    } catch (e: Throwable) {
        System.err.println("sync-helper: failed to write the sync result for ${project.name}")
        e.printStackTrace()
    }
}

private fun describe(progress: SyncProgress): String = when (progress) {
    is SyncProgress.Stopping -> "Waiting for the IDE to stop (${progress.secondsElapsed}s)"
    SyncProgress.Syncing -> "Syncing"
    SyncProgress.Restarting -> "Restarting the IDE"
    is SyncProgress.Error -> "Error: ${progress.message}"
}

private fun now(): String = Instant.now().toString()
