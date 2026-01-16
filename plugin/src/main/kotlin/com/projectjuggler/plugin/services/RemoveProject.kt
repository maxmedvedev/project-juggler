package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectCleaner
import com.projectjuggler.core.ProjectManager

fun removeRecentProject(projectPath: ProjectPath, repository: IdeConfigRepository) {

    val metadata = ProjectManager.getInstance(repository).get(projectPath) ?: return

    // cleaning recent projects eagerly so that we can show a new recent popup right away
    RecentProjectsIndex.getInstance(repository).remove(metadata.id)

    ProjectCleaner.getInstance(repository).cleanProject(metadata)
}
