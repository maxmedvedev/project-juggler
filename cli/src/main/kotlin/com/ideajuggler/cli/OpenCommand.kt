package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectLauncher
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class OpenCommand : Command(
    name = "open",
    help = "Open a project with dedicated IntelliJ instance"
) {
    private val projectPathArg = PathArgument(
        name = "project-path",
        help = "Path to project directory"
    ).also { arguments.add(it) }

    override fun run() {
        val projectPath = projectPathArg.getValue()

        // Validate path
        if (!projectPath.exists()) {
            fail("Path does not exist: $projectPath")
        }
        if (!projectPath.isDirectory()) {
            fail("Path is not a directory: $projectPath")
        }

        val configRepository = ConfigRepository.create()
        val launcher = ProjectLauncher.getInstance(configRepository)
        launcher.launch(SimpleMessageOutput(), projectPath)
    }
}
