package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectIdGenerator
import com.ideajuggler.core.ProjectManager
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

class ListCommand : CliktCommand(
    name = "list",
    help = "List all tracked projects"
) {
    private val verbose by option("-v", "--verbose", help = "Show detailed information").flag()

    override fun run() {
        val baseDir = Paths.get(System.getProperty("user.home"), ".idea-juggler")
        val configRepository = ConfigRepository(baseDir)
        val projectIdGenerator = ProjectIdGenerator()
        val projectManager = ProjectManager(configRepository, projectIdGenerator)

        val projects = projectManager.listAll()

        if (projects.isEmpty()) {
            echo("No projects tracked yet.")
            echo("Use 'idea-juggler open <project-path>' to start tracking a project.")
            return
        }

        echo("Tracked projects (${projects.size}):")
        echo()

        projects.forEach { project ->
            val relativeTime = formatRelativeTime(project.lastOpened)
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
