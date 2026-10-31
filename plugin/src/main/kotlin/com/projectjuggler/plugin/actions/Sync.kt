package com.projectjuggler.plugin.actions

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IsolatedInstance
import com.projectjuggler.plugin.services.SyncProgressSink
import com.projectjuggler.plugin.services.describeFailures
import com.projectjuggler.plugin.services.reportSelfSyncResult
import com.projectjuggler.plugin.services.syncProjects
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.plugin.util.BundledSyncHelper
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import com.projectjuggler.process.ProjectLauncher
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = Logger.getInstance("#com.projectjuggler.plugin.actions.Sync")

/**
 * Common logic for self-shutdown sync operations.
 * Starts the sync-helper process with [helperArgs] (see `SyncTarget.SelfShutdown`) in a background task. The IDE does
 * not exit by itself: the helper finds it running, writes a stop request, and `ShutdownSignalService` closes it; the
 * helper then syncs and relaunches it. If the helper exits while the IDE still runs (it failed before stopping it),
 * [reportHelperExit] reports that. If the helper cannot be started, the IDE keeps running.
 */
@RequiresBackgroundThread
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
            val logFile = PathManager.getLogDir().resolve(BundledSyncHelper.LOG_FILE_NAME)
            val appClosing = AtomicBoolean(false)
            val connection = ApplicationManager.getApplication().messageBus.connect()
            connection.subscribe(AppLifecycleListener.TOPIC, object : AppLifecycleListener {
                override fun appWillBeClosed(isRestart: Boolean) = appClosing.set(true)
            })
            val process = try {
                BundledSyncHelper.start(helperArgs, logFile)
            } catch (t: Throwable) {
                connection.disconnect()
                throw t
            }
            process.onExit().thenAccept {
                connection.disconnect()
                reportHelperExit(project, it.exitValue(), logFile, appClosing.get())
            }
        }

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
 * Reports a sync-helper that exited while this IDE is still running, i.e. before it stopped the IDE or after a
 * shutdown timeout, through [reportSelfSyncResult] for the current isolated project. When the IDE is exiting (the
 * helper stopped it), the relaunched IDE reports at startup instead (`SelfSyncResultActivity`).
 */
private fun reportHelperExit(project: Project?, exitCode: Int, logFile: Path, appClosing: Boolean) {
    try {
        if (appClosing || ApplicationManager.getApplication().isDisposed) return
        LOG.info("sync-helper exited with code $exitCode while the IDE is running; output in $logFile")
        val notifications = IntelliJNotificationHandler(project?.takeUnless { it.isDisposed })
        val projectId = IsolatedInstance.currentProjectId()
        if (projectId == null) {
            // Self-sync only runs in an isolated instance; without a project id there is no result file to read.
            if (exitCode != 0) {
                notifications.showErrorNotification(
                    ProjectJugglerBundle.message("notification.self.sync.helper.exited", exitCode, logFile.toString())
                )
            }
            return
        }
        reportSelfSyncResult(
            IdeInstallationService.currentIdeConfigRepository, projectId, exitCode, logFile, notifications
        )
    } catch (t: Throwable) {
        t.rethrowIfControlFlow()
        LOG.warn("Failed to report the sync-helper exit", t)
    }
}

/**
 * Common sync logic with progress indicator.
 * Syncs [projects] with progress tracking. Shows [successMessage] when every project synced, else one error
 * notification built by [errorMessage] from the failure detail (see [describeFailures]). Then calls [onFinished] on
 * the task's pooled thread, also when some projects failed, but not when the user cancelled.
 */
fun performSyncWithProgress(
    project: Project?,
    projects: List<ProjectMetadata>,
    syncType: SyncType,
    taskTitle: String,
    successMessage: (List<ProjectMetadata>) -> String,
    errorMessage: (detail: String) -> String,
    onFinished: () -> Unit = {}
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle) {
        override fun run(indicator: ProgressIndicator) {
            val finished = try {
                val launcher = ProjectLauncher.getInstance(IdeInstallationService.currentIdeConfigRepository)
                val result = syncProjects(launcher, projects, syncType, ProgressIndicatorSink(indicator))
                if (result.succeeded) {
                    showInfoNotification(successMessage(result.synced), project)
                } else {
                    showErrorNotification(errorMessage(describeFailures(result, projects.size)), project)
                }
                true
            } catch (t: Throwable) {
                t.rethrowIfControlFlow()
                LOG.warn("Sync of ${projects.size} project(s) failed", t)
                showErrorNotification(errorMessage(t.userMessage()), project)
                false
            }
            if (finished) onFinished()
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
