package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.util.application
import com.projectjuggler.plugin.actions.recent.RecentProjectsPopup
import com.projectjuggler.plugin.showErrorNotification

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        // Load recent projects in background thread
        application.executeOnPooledThread {
            try {
                RecentProjectsPopup(project).show()
            } catch (ex: Exception) {
                showErrorNotification("Failed to load recent projects: ${ex.message}", project)
                ex.printStackTrace()
            }
        }
    }
}
