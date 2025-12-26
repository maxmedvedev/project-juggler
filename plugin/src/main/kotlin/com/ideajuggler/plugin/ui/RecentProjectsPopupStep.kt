package com.ideajuggler.plugin.ui

import com.ideajuggler.plugin.IdeaJugglerBundle
import com.ideajuggler.plugin.model.RecentProjectItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import javax.swing.Icon

internal class RecentProjectsPopupStep(
    private val projects: List<RecentProjectItem>,
    private val onSelect: (RecentProjectItem) -> Unit
) : BaseListPopupStep<RecentProjectItem>(
    IdeaJugglerBundle.message("popup.recent.projects.title"),
    projects
) {
    override fun getTextFor(value: RecentProjectItem): String {
        return value.displayText
    }

    override fun getIconFor(value: RecentProjectItem): Icon {
        return AllIcons.Nodes.Module
    }

    override fun onChosen(selectedValue: RecentProjectItem, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) {
            onSelect(selectedValue)
        }
        return PopupStep.FINAL_CHOICE
    }

    override fun isSpeedSearchEnabled(): Boolean = true

    override fun isAutoSelectionEnabled(): Boolean = false
}
