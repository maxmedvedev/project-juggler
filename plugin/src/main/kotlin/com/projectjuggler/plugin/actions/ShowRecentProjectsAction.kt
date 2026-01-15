package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.util.application
import com.projectjuggler.plugin.actions.recent.RecentProjectPopupBuilder

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        application.executeOnPooledThread {
            RecentProjectPopupBuilder(project).show()
        }
    }
}
