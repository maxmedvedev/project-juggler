package com.projectjuggler.plugin.services

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle

/**
 * Result of the main project prompt dialog.
 */
sealed class MainProjectPromptResult {
    /** User wants to set as main project and launch in main instance */
    data object SetAsMain : MainProjectPromptResult()

    /** User wants to open isolated (normal behavior) */
    data object OpenIsolated : MainProjectPromptResult()

    /** User cancelled the dialog */
    data object Cancelled : MainProjectPromptResult()
}

/**
 * Shows a dialog asking if the user wants to set the project as main.
 * Returns the user's choice and handles "don't ask again" preference.
 *
 * MUST be called on EDT.
 */
fun showMainProjectPrompt(
    projectPath: ProjectPath,
    ideConfigRepository: IdeConfigRepository
): MainProjectPromptResult {
    var dontAskAgain = false

    val result = MessageDialogBuilder.yesNoCancel(
        ProjectJugglerBundle.message("dialog.main.project.prompt.title"),
        ProjectJugglerBundle.message("dialog.main.project.prompt.message", projectPath.name)
    )
        .yesText(ProjectJugglerBundle.message("dialog.main.project.prompt.yes"))
        .noText(ProjectJugglerBundle.message("dialog.main.project.prompt.no"))
        .cancelText(ProjectJugglerBundle.message("dialog.main.project.prompt.cancel"))
        .icon(Messages.getQuestionIcon())
        .doNotAsk(object : com.intellij.openapi.ui.DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (isSelected && exitCode != Messages.CANCEL) {
                    dontAskAgain = true
                }
            }

            override fun getDoNotShowMessage(): String {
                return ProjectJugglerBundle.message("dialog.main.project.prompt.dont.ask")
            }
        })
        .show(project = null)

    if (dontAskAgain) {
        MainProjectService.setDontAskAboutMainProject(ideConfigRepository, true)
    }

    return when (result) {
        Messages.YES -> MainProjectPromptResult.SetAsMain
        Messages.NO -> MainProjectPromptResult.OpenIsolated
        else -> MainProjectPromptResult.Cancelled
    }
}
