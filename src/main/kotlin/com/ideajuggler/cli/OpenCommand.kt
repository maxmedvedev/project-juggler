package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.core.ProjectLauncher

class OpenCommand : CliktCommand(
    name = "open",
    help = "Open a project with dedicated IntelliJ instance"
) {
    private val projectPath by argument(help = "Path to project directory")
        .path(mustExist = true, canBeFile = false, canBeDir = true)

    override fun run() {
        val configRepository = ConfigRepository.create()
        val launcher = ProjectLauncher.getInstance(configRepository)
        launcher.launch(this, projectPath)
    }
}
