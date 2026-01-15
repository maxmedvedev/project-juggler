package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.plugin.ProjectJugglerBundle

data class SyncAllProjectsAction(
    val syncType: SyncType
) : RecentProjectPopupAction {
    override fun onChosen(
        project: Project?,
        step: BaseListPopupStep<RecentProjectPopupAction>
    ): PopupStep<*>? {
        syncAllProjectsWithType(syncType, project)
        return PopupStep.FINAL_CHOICE
    }

    override fun getTextFor(): String = when (syncType) {
        SyncType.All -> ProjectJugglerBundle.message("popup.sync.all.projects.label")
        SyncType.VmOptions -> ProjectJugglerBundle.message("popup.sync.vmoptions.label")
        SyncType.Config -> ProjectJugglerBundle.message("popup.sync.config.label")
        SyncType.Plugins -> ProjectJugglerBundle.message("popup.sync.plugins.label")
    }

    private fun syncAllProjectsWithType(syncType: SyncType, project: Project?) {
        if (isSyncingAllIncludingMe()) {
            handleSelfShutdownSyncAll(syncType, project)
            return
        }

        val allProjects = currentIdeConfigRepository.loadAllProjects()
        performSyncWithProgress(
            project = project,
            projects = allProjects,
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message("progress.sync.all.projects.type", syncType.displayName),
            successMessage = {
                ProjectJugglerBundle.message(
                    "notification.success.sync.all.projects.type",
                    syncType.displayName,
                    it.size
                )
            },
            errorMessage = { e ->
                ProjectJugglerBundle.message("notification.error.sync.projects.failed", e.message ?: "")
            }
        )
    }

    private fun isSyncingAllIncludingMe(): Boolean {
        // Check if current project is in the list (self-shutdown case)
        val allProjects = currentIdeConfigRepository.loadAllProjects()
        val currentProjectInList = allProjects.any { isCurrentProject(currentIdeConfigRepository, it.path) }
        return currentProjectInList
    }

    /**
     * Handles syncing all projects when current project is in the list.
     * Spawns sync-helper with --all-projects and shuts down.
     */
    private fun handleSelfShutdownSyncAll(syncType: SyncType, project: Project?) {
        val idePath = currentIdeConfigRepository.installation.executablePath
        performSelfShutdownSync(
            project = project,
            notificationMessage = "IntelliJ will close to sync all projects and reopen automatically...",
            helperArgs = listOf("--ide", idePath, "--all-projects"),
            syncType = syncType
        )
    }
}
