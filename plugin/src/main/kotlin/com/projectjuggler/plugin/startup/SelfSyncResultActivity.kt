package com.projectjuggler.plugin.startup

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IsolatedInstance
import com.projectjuggler.plugin.services.reportSelfSyncResult
import com.projectjuggler.plugin.util.BundledSyncHelper
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the result of a self-sync that the sync-helper ran while this isolated instance was closed, when the
 * relaunched IDE starts; see [reportSelfSyncResult]. Does nothing in the main instance.
 */
internal class SelfSyncResultActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            val projectId = IsolatedInstance.currentProjectId() ?: return
            val repository = IdeInstallationService.currentIdeConfigRepository

            withContext(Dispatchers.IO) {
                reportSelfSyncResult(
                    repository,
                    projectId,
                    exitCode = null,
                    logFile = PathManager.getLogDir().resolve(BundledSyncHelper.LOG_FILE_NAME),
                    notifications = IntelliJNotificationHandler(project),
                )
            }
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.error("Self-sync result report failed", t)
        }
    }
}

private val LOG = logger<SelfSyncResultActivity>()
