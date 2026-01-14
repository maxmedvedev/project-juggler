package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.util.application
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.plugin.actions.recent.RecentProjectsPopup
import com.projectjuggler.plugin.services.AutoConfigPopulator
import com.projectjuggler.plugin.showErrorNotification
import kotlin.io.path.exists

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Load recent projects in background thread
        application.executeOnPooledThread {
            try {
                // Launch async config auto-population if needed (non-blocking)
                val configRepository = ConfigRepository.create()
                ensureConfigExistsAsync(configRepository)

                // Show popup immediately (doesn't wait for config detection)
                RecentProjectsPopup(project).show()
            } catch (ex: Exception) {
                showErrorNotification("Failed to load recent projects: ${ex.message}", project)
                ex.printStackTrace()
            }
        }
    }

    private fun ensureConfigExistsAsync(configRepository: ConfigRepository) {
        val configFile = configRepository.getConfigFile()
        if (configFile.exists()) return

        application.executeOnPooledThread {
            AutoConfigPopulator.populate(configRepository)
        }
    }
}
