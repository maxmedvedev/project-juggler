package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectManager
import com.ideajuggler.util.TimeUtils

class ListCommand : CliktCommand(
    name = "list",
    help = "List all tracked projects"
) {
    private val verbose by option("-v", "--verbose", help = "Show detailed information").flag()

    override fun run() {
        val configRepository = ConfigRepository.create()

        val projects = ProjectManager.getInstance(configRepository).listAll()

        if (projects.isEmpty()) {
            echo("No projects tracked yet.")
            echo("Use 'idea-juggler open <project-path>' to start tracking a project.")
            return
        }

        echo("Tracked projects (${projects.size}):")
        echo()

        projects.forEach { project ->
            val relativeTime = TimeUtils.formatRelativeTime(project.lastOpened)
            echo("  ${project.name}")
            echo("    ID:          ${project.id}")
            echo("    Path:        ${project.path}")
            echo("    Last opened: $relativeTime")

            if (verbose) {
                echo("    Open count:  ${project.openCount}")
            }
            echo()
        }
    }
}
