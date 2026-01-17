package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.di.getScope

class ProjectCleaner internal constructor(private val configRepository: IdeConfigRepository) {

    /**
     * Cleans a project by removing its config directories and recent entry.
     * This is the shared logic used by both CleanCommand and the popup.
     */
    fun cleanProject(metadata: ProjectMetadata) {
        RecentProjectsIndex.getInstance(configRepository).remove(metadata.id)
        DirectoryManager.getInstance(configRepository).cleanProject(metadata)
    }

    companion object {
        fun getInstance(configRepository: IdeConfigRepository): ProjectCleaner =
            configRepository.getScope().get()
    }
}
