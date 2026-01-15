package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

/**
 * Represents items that can be displayed in the recent projects popup.
 */
sealed interface RecentProjectPopupAction {
    fun onChosen(project: Project?, step: BaseListPopupStep<RecentProjectPopupAction>): PopupStep<*>?

    fun getTextFor(): String

    fun getIndexedString(): String = getTextFor()
}
