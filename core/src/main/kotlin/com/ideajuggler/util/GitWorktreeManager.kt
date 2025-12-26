package com.ideajuggler.util

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object GitWorktreeManager {

    /**
     * Creates a git worktree at the destination path with a new branch.
     *
     * @param source Source git repository directory
     * @param destination Destination directory for the worktree (must not exist)
     * @param branchName Name of the new branch to create in the worktree
     * @param messageOutput Optional callback for progress messages
     * @throws IllegalArgumentException if validation fails
     * @throws IOException if git command fails
     */
    fun createWorktree(
        source: Path,
        destination: Path,
        branchName: String,
        messageOutput: ((String) -> Unit)? = null
    ) {
        // Validate source
        if (!source.exists()) {
            throw IllegalArgumentException("Source directory does not exist: $source")
        }
        if (!source.isDirectory()) {
            throw IllegalArgumentException("Source is not a directory: $source")
        }
        if (!isGitRepository(source)) {
            throw IllegalArgumentException("Source is not a git repository: $source")
        }

        // Validate destination
        if (destination.exists()) {
            throw IllegalArgumentException("Destination already exists: $destination")
        }

        // Validate branch name
        if (branchName.isBlank()) {
            throw IllegalArgumentException("Branch name cannot be empty")
        }

        messageOutput?.invoke("Creating git worktree at $destination with branch '$branchName'...")

        // Execute git worktree add command
        try {
            val result = executeGitCommand(
                workingDirectory = source,
                args = listOf("worktree", "add", "-b", branchName, destination.toString())
            )

            if (result.exitCode != 0) {
                val errorMessage = if (result.stderr.isNotBlank()) {
                    result.stderr.trim()
                } else {
                    "Git command failed with exit code ${result.exitCode}"
                }
                throw IOException("Failed to create git worktree: $errorMessage")
            }

            messageOutput?.invoke("Git worktree created successfully")
        } catch (e: IOException) {
            throw IOException("Failed to execute git command: ${e.message}", e)
        }
    }

    /**
     * Checks if a directory is a git repository by looking for .git directory or file.
     * A .git file indicates a git worktree or submodule.
     */
    fun isGitRepository(path: Path): Boolean {
        val gitPath = path.resolve(".git")
        return gitPath.exists()
    }

    /**
     * Executes a git command and waits for completion.
     */
    private fun executeGitCommand(
        workingDirectory: Path,
        args: List<String>
    ): CommandResult {
        val command = listOf("git") + args

        val processBuilder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = processBuilder.start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }

        val exitCode = process.waitFor()

        return CommandResult(exitCode, stdout, stderr)
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}
