package com.projectjuggler.plugin.startup

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.core.ProjectCleaner
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.cleanStaleMainProjectEntryIfMainInstance
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

            withContext(Dispatchers.IO) {
                val removed = cleanStaleMainProjectEntryIfMainInstance(repository, PathManager.getConfigDir())
                if (removed != null) {
                    LOG.info("Removed stale isolated entry of main project '${removed.name}' (${removed.id.id})")
                }
            }
        } catch (t: Throwable) {
            t.rethrowIfControlFlow()
            LOG.error("Stale main project cleanup failed", t)
        }
    }
}

private val LOG = logger<StaleMainProjectCleanupActivity>()
