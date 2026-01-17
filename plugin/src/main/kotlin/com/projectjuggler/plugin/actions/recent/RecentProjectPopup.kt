package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.popup.list.PopupListElementRenderer
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdeInstallationService
import java.awt.Component
import javax.swing.JList

@Suppress("jol")
internal class RecentProjectPopup(
    itemsList: List<RecentProjectPopupAction>,
    project: Project?
) :
    ListPopupImpl(project, RecentProjectPopupStep(itemsList, project)) {
    override fun getListElementRenderer(): PopupListElementRenderer<*> {
        @Suppress("UNCHECKED_CAST")
        return ProjectItemRenderer(this) as PopupListElementRenderer<*>
    }
}

private class ProjectItemRenderer(popup: ListPopupImpl) : PopupListElementRenderer<RecentProjectPopupAction>(popup) {
    private val customRenderer = RecentProjectPopupActionRenderer()

    override fun getListCellRendererComponent(
        list: JList<out RecentProjectPopupAction>,
        value: RecentProjectPopupAction?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component = customRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
}

private class RecentProjectPopupStep(
    items: List<RecentProjectPopupAction>,
    private val project: Project?
) : BaseListPopupStep<RecentProjectPopupAction>(
    ProjectJugglerBundle.message("popup.recent.projects.title"),
    items
) {
    override fun onChosen(selectedValue: RecentProjectPopupAction, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) {
            return selectedValue.onChosen(project, this)
        }
        if (selectedValue is OpenRecentProjectAction) {
            return createProjectSubmenu(selectedValue)
        }

        return FINAL_CHOICE
    }

    private fun createProjectSubmenu(item: OpenRecentProjectAction): PopupStep<RecentProjectSubAction> {
        val ideService = IdeInstallationService.getInstance()
        val allInstallations = ideService.getAllInstallations()
        val currentInstallation = ideService.currentInstallation

        // Build actions list with "Open with IDE" options for other IDEs
        val actions = mutableListOf(
            OpenRecentProjectSubAction,
            ToggleMainProjectSubAction,
        )

        // todo not implemented yet
        // Add "Open with IDE X" for each available IDE (except current)
//        allInstallations
//            .filter { it.executablePath != currentInstallation.executablePath }
//            .forEach { installation ->
//                actions.add(OpenWithIdeSubAction(installation))
//            }

        actions.addAll(listOf(
            SyncSettingsSubAction(SyncType.All),
            SyncSettingsSubAction(SyncType.VmOptions),
            SyncSettingsSubAction(SyncType.Config),
            SyncSettingsSubAction(SyncType.Plugins),
            RemoveProjectSubAction
        ))

        return object : BaseListPopupStep<RecentProjectSubAction>(null, actions) {
            override fun onChosen(selectedValue: RecentProjectSubAction, finalChoice: Boolean): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                return selectedValue.onChosen(item, this, project)
            }

            override fun getTextFor(value: RecentProjectSubAction): String =
                value.getTextFor(item)
        }
    }

    override fun hasSubstep(selectedValue: RecentProjectPopupAction): Boolean =
        selectedValue is OpenRecentProjectAction

    override fun getTextFor(value: RecentProjectPopupAction): String =
        value.getTextFor()

    override fun isSpeedSearchEnabled(): Boolean = true

    override fun getIndexedString(value: RecentProjectPopupAction): String =
        value.getIndexedString()
}
