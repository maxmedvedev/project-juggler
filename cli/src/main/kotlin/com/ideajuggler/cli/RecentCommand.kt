package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.util.TimeUtils
import java.nio.file.Paths

class RecentCommand : CliktCommand(
    name = "recent",
    help = "Show recently opened projects"
) {
    private val limit by option("-n", "--limit", help = "Number of projects to show")
        .int()
        .default(10)

    override fun run() {
        val configRepository = ConfigRepository.create()

        val recentProjects = RecentProjectsIndex.getInstance(configRepository).getRecent(limit)

        if (recentProjects.isEmpty()) {
            echo("No recent projects.")
            echo("Use 'idea-juggler open <project-path>' to open a project.")
            return
        }

        echo("Recently opened projects:")
        echo()

        recentProjects.forEachIndexed { index, project ->
            val relativeTime = TimeUtils.formatRelativeTime(project.lastOpened)
            echo("  ${index + 1}. ${project.name} ($relativeTime)")
            echo("     ${project.path}")
            echo()
        }

        // Interactive selection
        val selection = terminal.prompt("Select project number to open (or press Enter to cancel)")

        if (selection.isNullOrBlank()) {
            echo("Cancelled.")
            return
        }

        val selectedIndex = selection.toIntOrNull()?.minus(1)
        if (selectedIndex == null || selectedIndex !in recentProjects.indices) {
            echo("Invalid selection: $selection", err = true)
            return
        }

        val selectedProject = recentProjects[selectedIndex]
        val projectPath = Paths.get(selectedProject.path)

        // Launch the selected project
        echo("Opening ${selectedProject.name}...")

        val launcher = ProjectLauncher.getInstance(ConfigRepository.create())
        launcher.launch(CliktMessageOutput(this), projectPath, selectedProject.id)
    }
}
