package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectPath
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.core.ProjectManager
import kotlin.io.path.isDirectory

class OpenCommand : Command(
    name = "open",
    help = "Open a project with dedicated IntelliJ instance"
) {
    private val projectPathArg = StringArgument(
        name = "project-path",
        help = "Path to project directory"
    ).also { arguments.add(it) }

    override fun run() {
        val projectPathString = projectPathArg.getValue()

        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)
        val resolvedPath = validatePath(projectManager, projectPathString)


        val launcher = ProjectLauncher.getInstance(configRepository)
        launcher.launch(SimpleMessageOutput(), resolvedPath)
    }

    private fun validatePath(
        projectManager: ProjectManager,
        projectPathString: String
    ): ProjectPath {
        // Validate using ProjectManager
        if (!projectManager.validatePathExists(projectPathString)) {
            fail("Path does not exist: $projectPathString")
        }

        // Resolve and validate directory
        val resolvedPath = projectManager.resolvePath(projectPathString)
        if (!resolvedPath.path.isDirectory()) {
            fail("Path is not a directory: $projectPathString")
        }
        return resolvedPath
    }
}
