package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectManager
import java.nio.file.Files
import java.nio.file.Paths

/** The recent paths of the IDE that can be imported: existing directories, in the given order. */
internal fun importableProjects(paths: List<String>): List<ProjectPath> =
    paths.filter { Files.isDirectory(Paths.get(it)) }.map(::ProjectPath)

/**
 * Tracks [projects] as recent isolated projects: metadata plus a recent entry each.
 * Nothing is launched and no isolated directories are created.
 */
internal fun importProjects(repository: IdeConfigRepository, projects: List<ProjectPath>) {
    val projectManager = ProjectManager.getInstance(repository)
    val recentIndex = RecentProjectsIndex.getInstance(repository)

    projects.forEach { projectPath ->
        projectManager.registerOrUpdate(projectPath)
        recentIndex.recordOpen(projectPath)
    }
}
