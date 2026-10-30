package com.projectjuggler.plugin.startup

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.core.ProjectCleaner
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.services.IdeInstallationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes the stale isolated entry of the main project, if any, when the main IDE starts.
 * Such an entry is left behind when a project is opened isolated first and made the main
 * project later; see [ProjectCleaner.cleanStaleMainProjectEntry].
 */
internal class StaleMainProjectCleanupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        try {
            KoinInit.init(pluginModule)

            val repository = IdeInstallationService.currentIdeConfigRepository

            // Only in the main IDE instance: an isolated instance has no business touching other entries
            if (!MainProjectService.isRunningInMainInstance(repository, PathManager.getConfigDir())) return

            withContext(Dispatchers.IO) {
                val removed = ProjectCleaner.getInstance(repository).cleanStaleMainProjectEntry()
                if (removed != null) {
                    LOG.info("Removed stale isolated entry of main project '${removed.name}' (${removed.id.id})")
                }
            }
        } catch (t: Throwable) {
            if (t is ControlFlowException) throw t
            LOG.error("Stale main project cleanup failed", t)
        }
    }
}

private val LOG = logger<StaleMainProjectCleanupActivity>()
