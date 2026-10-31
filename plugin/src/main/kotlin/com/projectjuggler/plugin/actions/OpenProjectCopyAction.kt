package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.util.application
import com.projectjuggler.plugin.services.openProjectCopy
import com.projectjuggler.plugin.util.IntelliJNotificationHandler

internal class OpenProjectCopyAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val dialog = OpenProjectCopyDialog(project)
        if (!dialog.showAndGet()) return
        val input = dialog.input
        application.executeOnPooledThread { openProjectCopy(input, IntelliJNotificationHandler(project)) }
    }
}
