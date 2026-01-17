package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.currentIdeConfigRepository
import com.projectjuggler.plugin.actions.isCurrentProject
import com.projectjuggler.plugin.actions.performSelfShutdownSync
import com.projectjuggler.plugin.actions.performSyncWithProgress

/** Sync settings from base config */
data class SyncSettingsSubAction(val syncType: SyncType) : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        syncSingleProjectWithType(project, item.projectPath, syncType)
        return PopupStep.FINAL_CHOICE
    }

    private fun syncSingleProjectWithType(project: Project?, projectPath: ProjectPath, syncType: SyncType) {
        // Check if syncing current project (self-shutdown case)
        if (isCurrentProject(currentIdeConfigRepository, projectPath)) {
            handleSelfShutdownSync(project, projectPath, syncType)
            return
        }

        val metadata = ProjectManager.Companion.getInstance(currentIdeConfigRepository).get(projectPath) ?: return
        performSyncWithProgress(
            project = project,
            projects = listOf(metadata),
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message(
                "progress.sync.project.type",
                syncType.displayName,
                projectPath.name
            ),
            successMessage = { projects ->
                ProjectJugglerBundle.message(
                    "notification.success.sync.single.project.type",
                    syncType.displayName,
                    projects.first().path.name
                )
            },
            errorMessage = { e ->
                ProjectJugglerBundle.message("notification.error.sync.settings.failed", e.message ?: "")
            }
        )
    }

    /**
     * Handles syncing the current project by spawning sync-helper and shutting down.
     */
    private fun handleSelfShutdownSync(project: Project?, projectPath: ProjectPath, syncType: SyncType) {
        val idePath = currentIdeConfigRepository.installation.executablePath
        performSelfShutdownSync(
            project = project,
            notificationMessage = "IntelliJ will close to sync ${syncType.displayName} and reopen automatically...",
            helperArgs = listOf("--ide", idePath, "--path", projectPath.pathString),
            syncType = syncType
        )
    }


    override fun getTextFor(item: OpenRecentProjectAction): String = when (syncType) {
        SyncType.All -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.all")
        SyncType.VmOptions -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.vmoptions")
        SyncType.Config -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.config")
        SyncType.Plugins -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.plugins")
    }
}
