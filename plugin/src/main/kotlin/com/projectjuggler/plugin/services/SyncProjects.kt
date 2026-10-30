package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.process.ProjectLauncher

/** The part of a progress indicator that [syncProjects] drives. */
internal interface SyncProgressSink {
    fun setIndeterminate(value: Boolean)
    fun setText(text: String)
    fun setFraction(fraction: Double)
    fun checkCanceled()
}

/**
 * Syncs [projects] one after another, stopping and restarting running ones, and reports progress to [sink].
 * The first failure propagates and aborts the remaining projects.
 */
internal fun syncProjects(
    launcher: ProjectLauncher,
    projects: List<ProjectMetadata>,
    syncType: SyncType,
    sink: SyncProgressSink,
) {
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
                is SyncProgress.Error -> Unit // the exception reaches the caller
            }
        }
    )

    projects.forEachIndexed { index, projectMetadata ->
        sink.checkCanceled()
        sink.setText(
            ProjectJugglerBundle.message("progress.sync.project.type", syncType.displayName, projectMetadata.path.name)
        )
        if (projects.size > 1) {
            sink.setFraction(index.toDouble() / projects.size)
        }
        launcher.syncProject(
            projectMetadata,
            syncVmOptions = syncType.syncVmOptions,
            syncConfig = syncType.syncConfig,
            syncPlugins = syncType.syncPlugins,
            options = syncOptions,
        )
    }
}
