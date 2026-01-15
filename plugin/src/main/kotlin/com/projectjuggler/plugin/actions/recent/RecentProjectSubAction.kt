package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.NlsContexts

/**
 * Represents actions that can be performed on a specific recent project.
 */
sealed interface RecentProjectSubAction {
    fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>?

    fun getTextFor(item: OpenRecentProjectAction): @NlsContexts.ListItem String
}