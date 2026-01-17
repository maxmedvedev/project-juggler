package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class ShutdownSignalServiceStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<ShutdownSignalService>()
    }
}