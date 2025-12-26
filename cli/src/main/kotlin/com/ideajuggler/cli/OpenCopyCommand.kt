package com.ideajuggler.cli

import com.ideajuggler.cli.framework.*
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectPath
import com.ideajuggler.core.ProjectLauncher
import com.ideajuggler.core.ProjectManager
import com.ideajuggler.util.GitWorktreeManager
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class OpenCopyCommand : Command(
    name = "open-copy",
    help = "Copy a project using git worktree and open it in a new IntelliJ instance"
) {
    private val sourcePathArg = StringArgument(
        name = "source-path",
        help = "Path to source git repository"
    ).also { arguments.add(it) }

    private val destPathArg = StringArgument(
        name = "destination-path",
        help = "Path where git worktree will be created"
    ).also { arguments.add(it) }

    private val branchOption = StringOption(
        shortName = "b",
        longName = "branch",
        help = "Name of the new branch for the worktree (required)"
    ).also { options.add(it) }

    override fun run() {
        val sourcePathString = sourcePathArg.getValue()
        val destPathString = destPathArg.getValue()
        val branchName = branchOption.getValue()

        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)

        // Validate and resolve source path
        val sourcePath = validateSourcePath(projectManager, sourcePathString)

        // Validate destination path
        val destPath = validateDestinationPath(projectManager, destPathString)

        // Perform the worktree creation
        echo("Creating git worktree...")
        echo("  Source:      $sourcePathString")
        echo("  Destination: $destPathString")
        echo("  Branch:      $branchName")
        echo()

        try {
            GitWorktreeManager.createWorktree(
                source = sourcePath.path,
                destination = destPath.path,
                branchName = branchName
            ) { message ->
                echo(message)
            }

            echo()
            echo("Git worktree created successfully!")
            echo()

            // Launch the worktree
            echo("Opening worktree...")
            val launcher = ProjectLauncher.getInstance(configRepository)
            launcher.launch(SimpleMessageOutput(), destPath)
        } catch (e: Exception) {
            echo()
            echo("Error: ${e.message}", err = true)
            throw ExitException(1)
        }
    }

    private fun validateSourcePath(
        projectManager: ProjectManager,
        pathString: String
    ): ProjectPath {
        // Validate path exists
        if (!projectManager.validatePathExists(pathString)) {
            fail("Source path does not exist: $pathString")
        }

        // Resolve and validate it's a directory
        val resolvedPath = projectManager.resolvePath(pathString)
        if (!resolvedPath.path.isDirectory()) {
            fail("Source path is not a directory: $pathString")
        }

        // Validate it's a git repository
        if (!GitWorktreeManager.isGitRepository(resolvedPath.path)) {
            fail("Source path is not a git repository: $pathString\nPlease ensure the directory contains a .git directory or file.")
        }

        return resolvedPath
    }

    private fun validateDestinationPath(
        projectManager: ProjectManager,
        pathString: String
    ): ProjectPath {
        // Resolve the path (allow tilde expansion)
        val resolvedPath = projectManager.resolvePath(pathString)

        // Destination must NOT exist
        if (resolvedPath.path.exists()) {
            fail("Destination already exists: $pathString\nPlease choose a different destination or remove the existing directory.")
        }

        return resolvedPath
    }
}
