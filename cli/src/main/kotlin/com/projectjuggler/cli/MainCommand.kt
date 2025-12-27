package com.projectjuggler.cli

import com.projectjuggler.cli.framework.*
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.core.IntelliJLauncher
import com.projectjuggler.util.PathUtils.expandTilde
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists

class MainCommand : Command(
    name = "main",
    help = "Open the main project (configured via 'config --main-project')"
) {
    override fun run() {
        val configRepository = ConfigRepository.create()
        val config = configRepository.load()

        // Check if main project is configured
        val mainProjectPath = config.mainProjectPath
        if (mainProjectPath == null) {
            echo("Error: Main project not configured.", err = true)
            echo("Configure it using: project-juggler config --main-project <path>", err = true)
            throw ExitException(1)
        }

        // Expand tilde and validate the path
        val expandedPath = expandTilde(Path(mainProjectPath))
        if (!expandedPath.exists()) {
            echo("Error: Main project path no longer exists: $mainProjectPath", err = true)
            echo("Update it using: project-juggler config --main-project <path>", err = true)
            throw ExitException(1)
        }

        if (!Files.isDirectory(expandedPath)) {
            echo("Error: Main project path is not a directory: $mainProjectPath", err = true)
            throw ExitException(1)
        }

        // Get project name for display
        val projectName = expandedPath.fileName.toString()
        echo("Opening main project: $projectName")

        // Launch with special handling for main project
        val launcher = IntelliJLauncher.getInstance(configRepository)
        launcher.launchMain(expandedPath)
    }
}
