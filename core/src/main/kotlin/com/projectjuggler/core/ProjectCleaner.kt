package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex

class ProjectCleaner(private val configRepository: ConfigRepository) {

    /**
     * Cleans a project by removing its config directories and recent entry.
     * This is the shared logic used by both CleanCommand and the popup.
     */
    fun cleanProject(metadata: ProjectMetadata) {
        RecentProjectsIndex.getInstance(configRepository).remove(metadata.id)
        DirectoryManager.getInstance(configRepository).cleanProject(metadata)
    }

    companion object {
        fun getInstance(configRepository: ConfigRepository) = ProjectCleaner(configRepository)
    }
}
