package com.ideajuggler.cli.framework

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectMetadata
import com.ideajuggler.core.ProjectManager

abstract class Command(
    val name: String,
    val help: String
) {
    internal val options = mutableListOf<OptionSpec<*>>()
    internal val arguments = mutableListOf<StringArgument>()

    abstract fun run()

    fun execute(args: List<String>) {
        try {
            parseArgs(args)
            run()
        } catch (e: CliException) {
            System.err.println("Error: ${e.message}")
            throw ExitException(1)
        }
    }

    protected fun echo(message: String = "", err: Boolean = false) {
        if (err) System.err.println(message) else println(message)
    }

    protected fun prompt(message: String): String? {
        print("$message: ")
        return readlnOrNull()
    }

    protected fun fail(message: String): Nothing {
        throw CliException(message)
    }

    /**
     * Resolves a project from either an ID or path option, handling validation and error messages.
     *
     * @param projectPathString The project path option (can be null)
     * @return A pair of (resolvedProjectId, project)
     * @throws ExitException if validation fails or project not found
     */
    protected fun resolveProject(
        projectPathString: String?
    ): ProjectMetadata {
        // Validate that exactly one is provided
        if (projectPathString == null) {
            echo("Error: --path must be specified", err = true)
            echo("Usage: $name --path <project-path> [options]", err = true)
            throw ExitException(1)
        }

        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)

        // Use ProjectManager for validation and ID generation
        if (!projectManager.validatePathExists(projectPathString)) {
            echo("Error: Path does not exist: $projectPathString", err = true)
            throw ExitException(1)
        }

        val projectPath = projectManager.resolvePath(projectPathString)

        // Get project and handle not found
        val project = projectManager.get(projectPath)
        if (project == null) {
            echo("Project not found: $projectPath", err = true)
            echo("Use 'idea-juggler list' to see tracked projects", err = true)
            throw ExitException(1)
        }

        return project
    }
}

class CliException(message: String) : Exception(message)
class ExitException(val code: Int) : Exception()
