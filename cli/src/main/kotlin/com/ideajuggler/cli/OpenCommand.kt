package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.util.PathUtils
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class OpenCommand : CliktCommand(
    name = "open",
    help = "Open a project with dedicated IntelliJ instance"
) {
    private val projectPath by argument(help = "Path to project directory")
        .convert { pathString ->
            // Expand tilde to home directory
            val path = PathUtils.expandTilde(Path.of(pathString))

            // Validate path exists and is a directory
            if (!path.exists()) {
                fail("Path does not exist: $pathString")
            }
            if (!path.isDirectory()) {
                fail("Path is not a directory: $pathString")
            }

            path
        }

    override fun run() {
        val configRepository = ConfigRepository.create()
        val launcher = ProjectLauncher.getInstance(configRepository)
        launcher.launch(CliktMessageOutput(this), projectPath)
    }
}
