package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Fallback for dynamic plugin loading, where [ShutdownSignalAppListener] never fires: starts [ShutdownSignalService]
 * when a project opens. [ShutdownSignalService.start] is idempotent.
 */
internal class ShutdownSignalServiceStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<ShutdownSignalService>().start()
    }
}
