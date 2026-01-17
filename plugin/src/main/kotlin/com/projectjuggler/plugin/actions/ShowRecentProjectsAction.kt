package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.util.application
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.actions.recent.RecentProjectPopupBuilder
import com.projectjuggler.plugin.di.pluginModule

internal class ShowRecentProjectsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        application.executeOnPooledThread {
            KoinInit.init(pluginModule)
            RecentProjectPopupBuilder(project).show()
        }
    }
}
