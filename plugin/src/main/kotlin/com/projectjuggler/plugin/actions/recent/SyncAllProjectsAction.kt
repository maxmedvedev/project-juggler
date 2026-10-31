package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.application
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
        // Planning reads metadata and the recent index; the self-shutdown path extracts the helper
        application.executeOnPooledThread { syncAllProjectsWithType(syncType, project) }
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
            is SyncTarget.SelfShutdown -> syncOthersThenSelf(project, target, syncType)
            is SyncTarget.InProcess -> syncInProcess(project, target.projects, syncType)
            SyncTarget.NoTarget -> return
        }
    }

    private fun syncInProcess(
        project: Project?,
        projects: List<ProjectMetadata>,
        syncType: SyncType,
        onFinished: () -> Unit = {}
    ) {
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
            errorMessage = { detail ->
                ProjectJugglerBundle.message("notification.error.sync.projects.failed", detail)
            },
            onFinished = onFinished
        )
    }

    /**
     * Handles syncing all projects when the current project is among them: syncs the others in process (with
     * progress and their own notification), then hands the current one to the sync-helper. Cancelling the in-process
     * part skips the self-sync. `onFinished` runs on the task's pooled thread, as [performSelfShutdownSync] needs.
     */
    private fun syncOthersThenSelf(project: Project?, target: SyncTarget.SelfShutdown, syncType: SyncType) {
        if (target.others.isEmpty()) {
            selfShutdownSync(project, target.helperArgs)
        } else {
            syncInProcess(project, target.others, syncType) { selfShutdownSync(project, target.helperArgs) }
        }
    }

    private fun selfShutdownSync(project: Project?, helperArgs: List<String>) {
        performSelfShutdownSync(
            project = project,
            notificationMessage = ProjectJugglerBundle.message("notification.info.sync.self.shutdown.all"),
            helperArgs = helperArgs
        )
    }
}
