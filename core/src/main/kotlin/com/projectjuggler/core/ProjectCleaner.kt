package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.di.getScope
import com.projectjuggler.util.ProjectLockUtils

class ProjectCleaner internal constructor(private val configRepository: IdeConfigRepository) {

    /**
     * Cleans a project by removing its config directories and recent entry.
     * This is the shared logic used by both CleanCommand and the popup.
     */
    fun cleanProject(metadata: ProjectMetadata) {
        RecentProjectsIndex.getInstance(configRepository).remove(metadata.id)
        DirectoryManager.getInstance(configRepository).cleanProject(metadata)
    }

    /**
     * Drops the tracked (isolated) entry of the main project, if one exists.
     *
     * A project that was opened isolated and later made the main project keeps its
     * `projects/<id>/` entry. The main project runs on base settings, so that entry is dead
     * weight and makes "sync all" style operations target the main IDE. The entry is left alone
     * while an isolated instance of the project is still running, so its config dir is not
     * deleted from under it; the cleanup is retried on the next call.
     *
     * @return the removed metadata, or null if nothing was removed
     */
    fun cleanStaleMainProjectEntry(): ProjectMetadata? {
        val mainProjectPath = configRepository.load().mainProjectPath?.let { ProjectPath(it) } ?: return null
        val metadata = configRepository.loadProjectMetadata(mainProjectPath) ?: return null
        if (ProjectLockUtils.isIsolatedInstanceOpen(configRepository, mainProjectPath)) return null

        cleanProject(metadata)
        return metadata
    }

    companion object {
        fun getInstance(configRepository: IdeConfigRepository): ProjectCleaner =
            configRepository.getScope().get()
    }
}
