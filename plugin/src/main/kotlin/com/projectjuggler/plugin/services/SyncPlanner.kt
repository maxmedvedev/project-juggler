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

    /** `helperArgs`: `--ide <exe> (--path <p> | --all-projects) <flag>`, the full sync-helper argument list. */
    data class SelfShutdown(val helperArgs: List<String>) : SyncTarget

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
            return SyncTarget.SelfShutdown(helperArgs(repository, listOf("--path", projectPath.pathString), syncType))
        }
        val metadata = ProjectManager.getInstance(repository).get(projectPath) ?: return SyncTarget.NoTarget
        return SyncTarget.InProcess(listOf(metadata))
    }

    /** Targets every syncable project (`ProjectManager.listSyncable`, i.e. all tracked projects but the main one). */
    fun forAllProjects(
        repository: IdeConfigRepository,
        syncType: SyncType,
        currentProjectId: ProjectId? = IsolatedInstance.currentProjectId(),
    ): SyncTarget {
        val projects = ProjectManager.getInstance(repository).listSyncable()
        if (currentProjectId != null && projects.any { it.id == currentProjectId }) {
            return SyncTarget.SelfShutdown(helperArgs(repository, listOf("--all-projects"), syncType))
        }
        return SyncTarget.InProcess(projects)
    }

    private fun helperArgs(repository: IdeConfigRepository, target: List<String>, syncType: SyncType): List<String> =
        listOf("--ide", repository.installation.executablePath) + target + syncType.cliFlag
}
