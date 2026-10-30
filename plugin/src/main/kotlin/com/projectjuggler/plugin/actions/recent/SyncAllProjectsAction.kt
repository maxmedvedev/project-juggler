package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.performSelfShutdownSync
import com.projectjuggler.plugin.actions.performSyncWithProgress
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.SyncPlanner
import com.projectjuggler.plugin.services.SyncTarget

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
        val repository = IdeInstallationService.currentIdeConfigRepository
        when (val target = SyncPlanner.forAllProjects(repository, syncType)) {
            is SyncTarget.SelfShutdown -> handleSelfShutdownSyncAll(project, target.helperArgs)
            is SyncTarget.InProcess -> syncInProcess(project, target.projects, syncType)
            SyncTarget.NoTarget -> return
        }
    }

    private fun syncInProcess(project: Project?, projects: List<ProjectMetadata>, syncType: SyncType) {
        performSyncWithProgress(
            project = project,
            projects = projects,
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

    /**
     * Handles syncing all projects when current project is in the list.
     * Spawns sync-helper with --all-projects and shuts down.
     */
    private fun handleSelfShutdownSyncAll(project: Project?, helperArgs: List<String>) {
        performSelfShutdownSync(
            project = project,
            notificationMessage = "IntelliJ will close to sync all projects and reopen automatically...",
            helperArgs = helperArgs
        )
    }
}
