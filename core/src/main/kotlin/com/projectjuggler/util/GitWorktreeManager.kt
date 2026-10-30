package com.projectjuggler.util

import java.io.IOException
import java.nio.file.Path
import java.time.Duration
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
     * @throws IOException if git exits non-zero, runs longer than 30 s, or cannot be started
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

        val result = GitCommandRunner.run(
            source,
            listOf("worktree", "add", "-b", branchName, destination.toString()),
            Duration.ofSeconds(30)
        )
        if (result.exitCode != 0) {
            val detail = result.output.trim().ifBlank { "git exited with code ${result.exitCode}" }
            throw IOException("Failed to create git worktree: $detail")
        }

        messageOutput?.invoke("Git worktree created successfully")
    }

    /**
     * Checks if a directory is a git repository by looking for .git directory or file.
     * A .git file indicates a git worktree or submodule.
     */
    fun isGitRepository(path: Path): Boolean {
        val gitPath = path.resolve(".git")
        return gitPath.exists()
    }
}
