package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.performSelfShutdownSync
import com.projectjuggler.plugin.actions.performSyncWithProgress
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.SyncPlanner
import com.projectjuggler.plugin.services.SyncTarget

/** Sync settings from base config */
data class SyncSettingsSubAction(val syncType: SyncType) : RecentProjectSubAction {
    override fun onChosen(
        item: OpenRecentProjectAction,
        step: BaseListPopupStep<RecentProjectSubAction>,
        project: Project?
    ): PopupStep<*>? {
        // Planning reads metadata and the recent index; the self-shutdown path extracts the helper
        application.executeOnPooledThread { syncSingleProjectWithType(project, item.projectPath, syncType) }
        return PopupStep.FINAL_CHOICE
    }

    private fun syncSingleProjectWithType(project: Project?, projectPath: ProjectPath, syncType: SyncType) {
        val repository = IdeInstallationService.currentIdeConfigRepository
        when (val target = SyncPlanner.forProject(repository, projectPath, syncType)) {
            is SyncTarget.SelfShutdown -> handleSelfShutdownSync(project, target.helperArgs, syncType)
            is SyncTarget.InProcess -> syncInProcess(project, target.projects, projectPath, syncType)
            SyncTarget.NoTarget -> return
        }
    }

    private fun syncInProcess(
        project: Project?,
        projects: List<ProjectMetadata>,
        projectPath: ProjectPath,
        syncType: SyncType
    ) {
        performSyncWithProgress(
            project = project,
            projects = projects,
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message(
                "progress.sync.project.type",
                syncType.displayName,
                projectPath.name
            ),
            successMessage = { synced ->
                ProjectJugglerBundle.message(
                    "notification.success.sync.single.project.type",
                    syncType.displayName,
                    synced.first().path.name
                )
            },
            errorMessage = { detail ->
                ProjectJugglerBundle.message("notification.error.sync.settings.failed", detail)
            }
        )
    }

    /**
     * Handles syncing the current project by spawning sync-helper and shutting down.
     */
    private fun handleSelfShutdownSync(project: Project?, helperArgs: List<String>, syncType: SyncType) {
        performSelfShutdownSync(
            project = project,
            notificationMessage = ProjectJugglerBundle.message(
                "notification.info.sync.self.shutdown.project",
                syncType.displayName
            ),
            helperArgs = helperArgs
        )
    }

    override fun getTextFor(item: OpenRecentProjectAction): String = when (syncType) {
        SyncType.All -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.all")
        SyncType.VmOptions -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.vmoptions")
        SyncType.Config -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.config")
        SyncType.Plugins -> ProjectJugglerBundle.message("popup.recent.projects.action.sync.plugins")
    }
}
