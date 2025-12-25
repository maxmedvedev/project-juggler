package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.*
import com.ideajuggler.platform.IntelliJLocator
import com.ideajuggler.platform.ProcessLauncher
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

class RecentCommand : CliktCommand(
    name = "recent",
    help = "Show recently opened projects"
) {
    private val limit by option("-n", "--limit", help = "Number of projects to show")
        .int()
        .default(10)

    override fun run() {
        val baseDir = Paths.get(System.getProperty("user.home"), ".idea-juggler")
        val configRepository = ConfigRepository(baseDir)
        val recentProjectsIndex = RecentProjectsIndex(baseDir)

        val recentProjects = recentProjectsIndex.getRecent(limit)

        if (recentProjects.isEmpty()) {
            echo("No recent projects.")
            echo("Use 'idea-juggler open <project-path>' to open a project.")
            return
        }

        echo("Recently opened projects:")
        echo()

        recentProjects.forEachIndexed { index, project ->
            val relativeTime = formatRelativeTime(project.lastOpened)
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
            return
        }

        val selectedProject = recentProjects[selectedIndex]
        val projectPath = Paths.get(selectedProject.path)

        // Launch the selected project
        echo("Opening ${selectedProject.name}...")
        launchProject(selectedProject.id, projectPath, baseDir)
    }

    private fun launchProject(projectId: String, projectPath: java.nio.file.Path, baseDir: java.nio.file.Path) {
        val configRepository = ConfigRepository(baseDir)
        val projectIdGenerator = ProjectIdGenerator()
        val projectManager = ProjectManager(configRepository, projectIdGenerator)
        val directoryManager = DirectoryManager(baseDir)
        val baseVMOptionsTracker = BaseVMOptionsTracker(configRepository)
        val vmOptionsGenerator = VMOptionsGenerator()
        val intellijLocator = IntelliJLocator()
        val processLauncher = ProcessLauncher()
        val intellijLauncher = IntelliJLauncher(
            configRepository,
            directoryManager,
            vmOptionsGenerator,
            baseVMOptionsTracker,
            intellijLocator,
            processLauncher
        )
        val recentProjectsIndex = RecentProjectsIndex(baseDir)

        // Update project metadata and recent list
        projectManager.registerOrUpdate(projectId, projectPath)
        recentProjectsIndex.recordOpen(projectId)

        // Launch IntelliJ
        intellijLauncher.launch(projectId, projectPath)
    }

    private fun formatRelativeTime(timestampStr: String): String {
        return try {
            val timestamp = Instant.parse(timestampStr)
            val now = Instant.now()
            val duration = Duration.between(timestamp, now)

            when {
                duration.toMinutes() < 1 -> "just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()} minute${if (duration.toMinutes() == 1L) "" else "s"} ago"
                duration.toHours() < 24 -> "${duration.toHours()} hour${if (duration.toHours() == 1L) "" else "s"} ago"
                duration.toDays() < 7 -> "${duration.toDays()} day${if (duration.toDays() == 1L) "" else "s"} ago"
                duration.toDays() < 30 -> "${duration.toDays() / 7} week${if (duration.toDays() / 7 == 1L) "" else "s"} ago"
                else -> "${duration.toDays() / 30} month${if (duration.toDays() / 30 == 1L) "" else "s"} ago"
            }
        } catch (e: Exception) {
            timestampStr
        }
    }
}
