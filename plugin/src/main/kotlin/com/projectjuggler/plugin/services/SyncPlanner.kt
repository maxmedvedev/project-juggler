package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.plugin.actions.recent.SyncType

/** What a sync request resolves to: run it here, hand it to the sync-helper because it would stop this IDE, or nothing. */
internal sealed interface SyncTarget {
    data class InProcess(val projects: List<ProjectMetadata>) : SyncTarget

    /**
     * The current project is a target: `helperArgs` (`sync --ide <exe> --path <current project> <flag>`, the full
     * sync-helper argument list) syncs it from outside. [others] are the remaining targets, to sync in process first.
     */
    data class SelfShutdown(
        val helperArgs: List<String>,
        val others: List<ProjectMetadata> = emptyList(),
    ) : SyncTarget

    /** A single project without metadata. */
    data object NoTarget : SyncTarget
}

internal object SyncPlanner {

    fun forProject(
        repository: IdeConfigRepository,
        projectPath: ProjectPath,
        syncType: SyncType,
        currentProjectId: ProjectId? = IsolatedInstance.currentProjectId(),
    ): SyncTarget {
        if (IsolatedInstance.isCurrentProject(repository, projectPath, currentProjectId)) {
            return SyncTarget.SelfShutdown(helperArgs(repository, projectPath, syncType))
        }
        val metadata = ProjectManager.getInstance(repository).get(projectPath) ?: return SyncTarget.NoTarget
        return SyncTarget.InProcess(listOf(metadata))
    }

    /**
     * Targets every syncable project (`ProjectManager.listSyncable`, i.e. all tracked projects but the main one).
     * When the current project is one of them, it goes to the sync-helper and the rest to [SyncTarget.SelfShutdown.others].
     */
    fun forAllProjects(
        repository: IdeConfigRepository,
        syncType: SyncType,
        currentProjectId: ProjectId? = IsolatedInstance.currentProjectId(),
    ): SyncTarget {
        val projects = ProjectManager.getInstance(repository).listSyncable()
        val (current, others) = projects.partition { it.id == currentProjectId }
        val currentProject = current.firstOrNull() ?: return SyncTarget.InProcess(projects)
        return SyncTarget.SelfShutdown(helperArgs(repository, currentProject.path, syncType), others)
    }

    private fun helperArgs(repository: IdeConfigRepository, projectPath: ProjectPath, syncType: SyncType): List<String> =
        listOf("sync", "--ide", repository.installation.executablePath, "--path", projectPath.pathString, syncType.cliFlag)
}
