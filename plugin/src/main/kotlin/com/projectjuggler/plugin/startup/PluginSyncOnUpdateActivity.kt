package com.projectjuggler.plugin.startup

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.syncPluginsOnIdeUpdate
import com.projectjuggler.plugin.util.IntelliJNotificationHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PluginSyncOnUpdateActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        KoinInit.init(pluginModule)

        val repository = IdeInstallationService.currentIdeConfigRepository
        val currentBuild = ApplicationInfo.getInstance().build.asString()
        val handler = IntelliJNotificationHandler(project)

        withContext(Dispatchers.IO) {
            syncPluginsOnIdeUpdate(repository, PathManager.getConfigDir(), currentBuild, handler)
        }
    }
}
