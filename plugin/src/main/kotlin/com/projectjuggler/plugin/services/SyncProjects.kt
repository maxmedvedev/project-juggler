package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.fileLogger
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.process.ProjectLauncher

private val LOG = fileLogger()

/** The part of a progress indicator that [syncProjects] drives. */
internal interface SyncProgressSink {
    fun setIndeterminate(value: Boolean)
    fun setText(text: String)
    fun setFraction(fraction: Double)
    fun checkCanceled()
}

/** A project whose sync threw [error]. */
internal data class SyncFailure(val project: ProjectMetadata, val error: Throwable)

/** The outcome of [syncProjects]: the projects that synced and the ones that failed, each in input order. */
internal data class SyncBatchResult(val synced: List<ProjectMetadata>, val failures: List<SyncFailure>) {
    val succeeded: Boolean get() = failures.isEmpty()
}

/**
 * Syncs [projects] one after another, stopping and restarting running ones, and reports progress to [sink].
 * A failing project is logged and recorded, and the remaining projects are still synced. Cancellation is checked
 * before each project only: cancelling during the stop wait would delete the stop request while the target may
 * already be exiting, leaving it stopped and never restarted. Control-flow exceptions propagate.
 */
internal fun syncProjects(
    launcher: ProjectLauncher,
    projects: List<ProjectMetadata>,
    syncType: SyncType,
    sink: SyncProgressSink,
): SyncBatchResult {
    sink.setIndeterminate(projects.size == 1)

    val syncOptions = SyncOptions(
        stopIfRunning = true,
        autoRestart = true,
        shutdownTimeout = 60,
        onProgress = { progress ->
            when (progress) {
                is SyncProgress.Stopping -> sink.setText(ProjectJugglerBundle.message("progress.sync.stopping.ide"))
                is SyncProgress.Syncing -> sink.setText(
                    ProjectJugglerBundle.message("progress.sync.syncing.type", syncType.displayName)
                )
                is SyncProgress.Restarting -> sink.setText(ProjectJugglerBundle.message("progress.sync.restarting.ide"))
                is SyncProgress.Error -> Unit // the exception is recorded as a failure
            }
        }
    )

    val synced = mutableListOf<ProjectMetadata>()
    val failures = mutableListOf<SyncFailure>()
    projects.forEachIndexed { index, projectMetadata ->
        sink.checkCanceled()
        sink.setText(
            ProjectJugglerBundle.message("progress.sync.project.type", syncType.displayName, projectMetadata.path.name)
        )
        if (projects.size > 1) {
            sink.setFraction(index.toDouble() / projects.size)
        }
        try {
            launcher.syncProject(
                projectMetadata,
                syncVmOptions = syncType.syncVmOptions,
                syncConfig = syncType.syncConfig,
                syncPlugins = syncType.syncPlugins,
                options = syncOptions,
            )
            synced += projectMetadata
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.warn("Failed to sync ${syncType.displayName} for project '${projectMetadata.name}'", t)
            failures += SyncFailure(projectMetadata, t)
        }
    }
    return SyncBatchResult(synced, failures)
}

/**
 * The failure detail for a notification. A batch of one project gives that project's message; a larger batch gives
 * `sync.failure.summary` with "<name>: <message>" per failed project, joined by "; ".
 */
internal fun describeFailures(result: SyncBatchResult, total: Int): String {
    if (total == 1 && result.failures.size == 1) return result.failures.single().error.userMessage()
    val details = result.failures.joinToString("; ") { "${it.project.name}: ${it.error.userMessage()}" }
    return ProjectJugglerBundle.message("sync.failure.summary", result.failures.size, total, details)
}
