package com.projectjuggler.plugin.startup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.util.BundledSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records this IDE's sync-helper and writes the launcher other apps use to open projects
 * ([BundledSyncHelper.publishLauncher]), once per IDE session. A failure is logged as an error; it only costs the
 * external entry point.
 */
internal class PublishSyncHelperActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!published.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                BundledSyncHelper.publishLauncher(IdeInstallationService.currentIdeConfigRepository)
            }
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.error("Failed to publish the sync-helper launcher", t)
        }
    }

    private companion object {
        val published = AtomicBoolean(false)
    }
}

private val LOG = logger<PublishSyncHelperActivity>()
