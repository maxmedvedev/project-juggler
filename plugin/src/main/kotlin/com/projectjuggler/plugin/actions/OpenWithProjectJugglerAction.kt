package com.projectjuggler.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

internal class OpenWithProjectJugglerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) =
        showFileChooserAndLaunch(e.project)

    override fun update(e: AnActionEvent) {
        // Always enable the action - don't require a project to be open
        e.presentation.isEnabledAndVisible = true
    }
}
