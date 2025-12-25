package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.DirectoryManager
import com.ideajuggler.core.ProjectIdGenerator
import com.ideajuggler.core.ProjectManager
import com.ideajuggler.util.PathUtils
import java.nio.file.Path
import kotlin.io.path.exists

class CleanCommand : CliktCommand(
    name = "clean",
    help = "Clean up config folders for a project"
) {
    private val projectIdentifier by argument(help = "Project ID or path")
    private val force by option("-f", "--force", help = "Skip confirmation prompt").flag()

    override fun run() {
        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)

        // Resolve project ID from identifier (could be ID or path)
        val projectId = resolveProjectId(projectIdentifier, projectManager)
        val project = projectManager.get(projectId)

        if (project == null) {
            echo("Project not found: $projectIdentifier", err = true)
            throw ProgramResult(1)
        }

        // Confirm deletion
        if (!force) {
            echo("This will delete all IntelliJ data for project:")
            echo("  Name: ${project.name}")
            echo("  Path: ${project.path}")
            echo("  ID:   ${project.id}")
            echo()
            val response = terminal.prompt("Are you sure you want to continue? (y/N)")
            if (response?.lowercase() != "y") {
                echo("Cancelled.")
                return
            }
        }

        // Clean project directories
        DirectoryManager.getInstance(configRepository).cleanProject(projectId)

        // Remove from metadata
        projectManager.remove(projectId)

        // Remove from recent list
        RecentProjectsIndex.getInstance(configRepository).remove(projectId)

        echo("Successfully cleaned project: ${project.name}")
    }

    private fun resolveProjectId(
        identifier: String,
        projectManager: ProjectManager
    ): String {
        // First, try as project ID
        if (projectManager.get(identifier) != null) {
            return identifier
        }

        // Otherwise, try as path (with tilde expansion)
        val path = PathUtils.expandTilde(Path.of(identifier))
        if (path.exists()) {
            return ProjectIdGenerator.generate(path)
        }

        // If neither works, return the identifier as-is (will fail with not found)
        return identifier
    }
}
