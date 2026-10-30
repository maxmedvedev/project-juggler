package com.projectjuggler.plugin.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.SyncProgressSink
import com.projectjuggler.plugin.services.syncProjects
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.util.BundledSyncHelper
import com.projectjuggler.process.ProjectLauncher

/**
 * Common logic for self-shutdown sync operations.
 * Spawns sync-helper process with [helperArgs] (see `SyncTarget.SelfShutdown`) and exits IntelliJ gracefully.
 */
fun performSelfShutdownSync(
    project: Project?,
    notificationMessage: String,
    helperArgs: List<String>
) {
    try {
        showInfoNotification(notificationMessage, project)

        // Get bundled sync-helper executable
        val executable = BundledSyncHelper.getExecutable()

        // Spawn sync-helper process
        ProcessBuilder(executable.toString(), *helperArgs.toTypedArray())
            .inheritIO()
            .start()

        // Wait briefly to ensure sync-helper started
        Thread.sleep(100)

        // Exit IntelliJ
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().exit()
        }
    } catch (e: Exception) {
        showErrorNotification(
            "Failed to initiate self-shutdown sync: ${e.message}",
            project
        )
    }
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
    errorMessage: (Exception) -> String
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val launcher = ProjectLauncher.getInstance(IdeInstallationService.currentIdeConfigRepository)
                syncProjects(launcher, projects, syncType, ProgressIndicatorSink(indicator))
                showInfoNotification(successMessage(projects), project)
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                showErrorNotification(errorMessage(e), project)
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
