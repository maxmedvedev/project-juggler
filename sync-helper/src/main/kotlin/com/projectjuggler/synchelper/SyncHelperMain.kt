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
import com.projectjuggler.process.ProjectLauncher
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Sync helper for self-shutdown syncs: the plugin starts it to sync the isolated project the running IDE belongs
 * to from outside. The helper stops that IDE through the stop request, syncs and relaunches it, and leaves a
 * [SelfSyncResult] for the relaunched IDE to report. See [USAGE].
 */
fun main(args: Array<String>) {
    KoinInit.init()
    exitProcess(run(args))
}

internal const val EXIT_OK = 0
internal const val EXIT_FAILURE = 1
internal const val EXIT_USAGE = 2

/**
 * Runs one sync for [args] and returns the exit code: [EXIT_OK] when synced, [EXIT_FAILURE] when anything after
 * argument parsing fails, [EXIT_USAGE] for invalid arguments (usage printed to stderr). Once the project is known,
 * the outcome is written to its [SelfSyncResultStore].
 */
internal fun run(
    args: Array<String>,
    registry: IdeRegistry = IdeRegistry.getInstance(),
    launcherFor: (IdeConfigRepository) -> ProjectLauncher = { ProjectLauncher.getInstance(it) },
): Int {
    val parsed = try {
        parseArgs(args)
    } catch (e: UsageException) {
        System.err.println("sync-helper: ${e.message}")
        System.err.println(USAGE)
        return EXIT_USAGE
    }
    println("[sync-helper] base dir ${IdeRegistry.getDefaultBaseDir()}, args ${args.joinToString(" ")}")

    val repository = registry.findRepository(parsed.idePath)
        ?: return fail("No Project Juggler data for IDE ${parsed.idePath}")
    val projectManager = ProjectManager.getInstance(repository)
    val project = projectManager.get(projectManager.resolvePath(parsed.projectPath))
        ?: return fail("Project not found: ${parsed.projectPath}")

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
    val syncTypes = SyncTypeNames.of(parsed.syncVmOptions, parsed.syncConfig, parsed.syncPlugins)

    return try {
        launcherFor(repository).syncProject(
            project, parsed.syncVmOptions, parsed.syncConfig, parsed.syncPlugins, options,
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
