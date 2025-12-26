package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.DirectoryManager

class CleanCommand : Command(
    name = "clean",
    help = "Clean up config folders for a project"
) {
    private val projectIdOpt = StringOption(
        shortName = "i",
        longName = "id",
        help = "Project ID"
    ).also { options.add(it) }

    private val projectPathOpt = PathOption(
        shortName = "p",
        longName = "path",
        help = "Project path"
    ).also { options.add(it) }

    private val forceOpt = FlagOption(
        shortName = "f",
        longName = "force",
        help = "Skip confirmation prompt"
    ).also { options.add(it) }

    override fun run() {
        val projectId = projectIdOpt.getValueOrNull()
        val projectPath = projectPathOpt.getValueOrNull()
        val force = forceOpt.getValue()

        // Resolve project using helper method
        val (resolvedProjectId, project) = resolveProject(projectId, projectPath)

        val configRepository = ConfigRepository.create()

        // Confirm deletion
        if (!force) {
            echo("This will delete all IntelliJ data for project:")
            echo("  Name: ${project.name}")
            echo("  Path: ${project.path}")
            echo("  ID:   ${project.id}")
            echo()
            val response = prompt("Are you sure you want to continue? (y/N)")
            if (response?.lowercase() != "y") {
                echo("Cancelled.")
                return
            }
        }

        // Clean project directories
        DirectoryManager.getInstance(configRepository).cleanProject(resolvedProjectId)
        com.ideajuggler.core.ProjectManager.getInstance(configRepository).remove(resolvedProjectId)
        RecentProjectsIndex.getInstance(configRepository).remove(resolvedProjectId)

        echo("Successfully cleaned project: ${project.name}")
    }
}
