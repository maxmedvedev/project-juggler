package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.DirectoryManager
import com.ideajuggler.core.ProjectIdGenerator
import com.ideajuggler.core.ProjectManager
import com.ideajuggler.util.PathUtils
import java.nio.file.Path
import kotlin.io.path.exists

class CleanCommand : Command(
    name = "clean",
    help = "Clean up config folders for a project"
) {
    private val projectIdentifierArg = StringArgument(
        name = "project-id-or-path",
        help = "Project ID or path"
    ).also { arguments.add(it) }

    private val forceOpt = FlagOption(
        shortName = "f",
        longName = "force",
        help = "Skip confirmation prompt"
    ).also { options.add(it) }

    override fun run() {
        val projectIdentifier = projectIdentifierArg.getValue()
        val force = forceOpt.getValue()

        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)

        val projectId = resolveProjectId(projectIdentifier, projectManager)
        val project = projectManager.get(projectId)

        if (project == null) {
            echo("Project not found: $projectIdentifier", err = true)
            throw ExitException(1)
        }

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
        DirectoryManager.getInstance(configRepository).cleanProject(projectId)
        projectManager.remove(projectId)
        RecentProjectsIndex.getInstance(configRepository).remove(projectId)

        echo("Successfully cleaned project: ${project.name}")
    }

    private fun resolveProjectId(identifier: String, projectManager: ProjectManager): String {
        if (projectManager.get(identifier) != null) {
            return identifier
        }

        val path = PathUtils.expandTilde(Path.of(identifier))
        if (path.exists()) {
            return ProjectIdGenerator.generate(path)
        }

        return identifier
    }
}
