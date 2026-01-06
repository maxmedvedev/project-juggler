package com.projectjuggler.cli

import com.projectjuggler.cli.framework.Command
import com.projectjuggler.cli.framework.ExitException
import com.projectjuggler.cli.framework.FlagOption
import com.projectjuggler.cli.framework.StringOption
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.ProjectCleaner

class CleanCommand : Command(
    name = "clean",
    help = "Clean up config folders for a project"
) {
    private val projectPath = StringOption(
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
        val projectPath = projectPath.getValueOrNull()
        if (projectPath == null) {
            echo("Project path is required.")
            throw ExitException(1)
        }

        val force = forceOpt.getValue()

        // Resolve project using helper method
        val project = resolveProject(projectPath)

        val configRepository = ConfigRepository.create()

        // Confirm deletion
        if (!force) {
            echo("This will delete all IntelliJ data for project:")
            echo("  Name: ${project.name}")
            echo("  Path: ${project.path}")
            echo()
            val response = prompt("Are you sure you want to continue? (y/N)")
            if (response?.lowercase() != "y") {
                echo("Cancelled.")
                return
            }
        }

        // Clean project directories
        ProjectCleaner.getInstance(configRepository).cleanProject(project)

        echo("Successfully cleaned project: ${project.name}")
    }
}
