package com.projectjuggler.plugin.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.SyncProgressSink
import com.projectjuggler.plugin.services.syncProjects
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.plugin.util.BundledSyncHelper
import com.projectjuggler.process.ProjectLauncher

private val LOG = Logger.getInstance("#com.projectjuggler.plugin.actions.Sync")

/**
 * Common logic for self-shutdown sync operations.
 * Starts the sync-helper process with [helperArgs] (see `SyncTarget.SelfShutdown`) in a background task,
 * then exits IntelliJ gracefully. On failure the IDE keeps running.
 */
fun performSelfShutdownSync(
    project: Project?,
    notificationMessage: String,
    helperArgs: List<String>
) {
    showInfoNotification(notificationMessage, project)
    ProgressManager.getInstance().run(object : Task.Backgroundable(
        project,
        ProjectJugglerBundle.message("progress.sync.helper.starting"),
        /* canBeCancelled = */ false
    ) {
        override fun run(indicator: ProgressIndicator) {
            BundledSyncHelper.start(helperArgs)
            Thread.sleep(100) // lets the helper start before the IDE exits, see sync-helper-spec gap 1
        }

        override fun onSuccess() = ApplicationManager.getApplication().exit()

        override fun onThrowable(error: Throwable) {
            LOG.warn("Failed to initiate self-shutdown sync", error)
            showErrorNotification(
                ProjectJugglerBundle.message("notification.error.sync.self.shutdown.failed", error.userMessage()),
                project
            )
        }
    })
}

/**
 * Common sync logic with progress indicator.
 * Performs sync for a list of projects with progress tracking and notifications.
 */
fun performSyncWithProgress(
    project: Project?,
    projects: List<ProjectMetadata>,
    syncType: SyncType,
    taskTitle: String,
    successMessage: (List<ProjectMetadata>) -> String,
    errorMessage: (Throwable) -> String
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val launcher = ProjectLauncher.getInstance(IdeInstallationService.currentIdeConfigRepository)
                syncProjects(launcher, projects, syncType, ProgressIndicatorSink(indicator))
                showInfoNotification(successMessage(projects), project)
            } catch (t: Throwable) {
                t.rethrowIfControlFlow()
                LOG.warn("Sync of ${projects.size} project(s) failed", t)
                showErrorNotification(errorMessage(t), project)
            }
        }
    })
}

/** Routes sync progress to a [ProgressIndicator] and cancellation checks to [ProgressManager]. */
private class ProgressIndicatorSink(private val indicator: ProgressIndicator) : SyncProgressSink {
    override fun setIndeterminate(value: Boolean) {
        indicator.isIndeterminate = value
    }

    override fun setText(text: String) {
        indicator.text = text
    }

    override fun setFraction(fraction: Double) {
        indicator.fraction = fraction
    }

    override fun checkCanceled() = ProgressManager.checkCanceled()
}
