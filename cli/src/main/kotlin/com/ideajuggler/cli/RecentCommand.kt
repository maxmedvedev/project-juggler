package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.util.TimeUtils
import java.nio.file.Paths

class RecentCommand : Command(
    name = "recent",
    help = "Show recently opened projects"
) {
    private val limitOpt = IntOption(
        shortName = "n",
        longName = "limit",
        help = "Number of projects to show",
        default = 10
    ).also { options.add(it) }

    override fun run() {
        val limit = limitOpt.getValue()
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
        val selection = prompt("Select project number to open (or press Enter to cancel)")

        if (selection.isNullOrBlank()) {
            echo("Cancelled.")
            return
        }

        val selectedIndex = selection.toIntOrNull()?.minus(1)
        if (selectedIndex == null || selectedIndex !in recentProjects.indices) {
            echo("Invalid selection: $selection", err = true)
            throw ExitException(1)
        }

        val selectedProject = recentProjects[selectedIndex]
        val projectPath = Paths.get(selectedProject.path)

        echo("Opening ${selectedProject.name}...")

        val launcher = ProjectLauncher.getInstance(configRepository)
        launcher.launch(SimpleMessageOutput(), projectPath, selectedProject.id)
    }
}
