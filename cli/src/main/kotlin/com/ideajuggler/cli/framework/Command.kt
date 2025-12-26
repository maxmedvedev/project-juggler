package com.ideajuggler.cli.framework

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectMetadata
import com.ideajuggler.core.ProjectIdGenerator
import com.ideajuggler.core.ProjectManager
import com.ideajuggler.util.PathUtils
import java.nio.file.Path
import kotlin.io.path.exists

abstract class Command(
    val name: String,
    val help: String
) {
    internal val options = mutableListOf<OptionSpec<*>>()
    internal val arguments = mutableListOf<ArgumentSpec<*>>()

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
     * @param projectIdOpt The project ID option (can be null)
     * @param projectPathOpt The project path option (can be null)
     * @return A pair of (resolvedProjectId, project)
     * @throws ExitException if validation fails or project not found
     */
    protected fun resolveProject(
        projectIdOpt: String?,
        projectPathOpt: Path?
    ): Pair<String, ProjectMetadata> {
        // Validate that exactly one is provided
        if (projectIdOpt == null && projectPathOpt == null) {
            echo("Error: Either --id or --path must be specified", err = true)
            echo("Usage: $name --id <project-id> [options]", err = true)
            echo("   or: $name --path <project-path> [options]", err = true)
            throw ExitException(1)
        }

        if (projectIdOpt != null && projectPathOpt != null) {
            echo("Error: Cannot specify both --id and --path", err = true)
            throw ExitException(1)
        }

        val configRepository = ConfigRepository.create()
        val projectManager = ProjectManager.getInstance(configRepository)

        // Resolve project ID from either ID or path
        val resolvedProjectId = when {
            projectIdOpt != null -> projectIdOpt
            projectPathOpt != null -> {
                val expandedPath = PathUtils.expandTilde(projectPathOpt)
                if (!expandedPath.exists()) {
                    echo("Error: Path does not exist: $projectPathOpt", err = true)
                    throw ExitException(1)
                }
                ProjectIdGenerator.generate(expandedPath)
            }
            else -> throw IllegalStateException("unreachable")
        }

        // Get project and handle not found
        val project = projectManager.get(resolvedProjectId)
        if (project == null) {
            val identifier = projectIdOpt ?: projectPathOpt.toString()
            echo("Project not found: $identifier", err = true)
            echo("Use 'idea-juggler list' to see tracked projects", err = true)
            throw ExitException(1)
        }

        return Pair(resolvedProjectId, project)
    }
}

class CliException(message: String) : Exception(message)
class ExitException(val code: Int) : Exception()
