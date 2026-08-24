package com.projectjuggler.util

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object GitUtils {
    /**
     * Detects the current git branch for a project.
     *
     * The project path may point at a directory or at a file (projects can be opened by selecting
     * a file such as `module.bazel`). In both cases the enclosing directory and its ancestors are
     * searched for the git repository.
     *
     * @param projectPath The path to the project directory or project file
     * @return The branch name, "HEAD (detached)" for detached HEAD, or null if not a git repo or error
     */
    fun detectGitBranch(projectPath: Path): String? {
        try {
            val startDir = if (projectPath.isDirectory()) projectPath else projectPath.parent ?: return null

            val gitDir = findGitDir(startDir) ?: return null

            // Try reading HEAD file first (faster than executing git command)
            val headFile = gitDir.resolve("HEAD")
            if (headFile.exists() && headFile.isRegularFile()) {
                val headContent = headFile.readText().trim()

                // If HEAD contains "ref: refs/heads/branch-name"
                if (headContent.startsWith("ref: refs/heads/")) {
                    return headContent.removePrefix("ref: refs/heads/")
                }

                // If HEAD contains a commit SHA (detached HEAD state)
                if (headContent.matches(Regex("[0-9a-f]{40}"))) {
                    return "HEAD (detached)"
                }
            }

            // Fallback: execute git command
            return executeGitCommand(startDir, "git", "rev-parse", "--abbrev-ref", "HEAD")
        } catch (e: Exception) {
            // Silently return null on any error
            return null
        }
    }

    /**
     * Walks up from [startDir] looking for a git directory.
     * A `.git` file (git worktree or submodule) is followed to the directory it points at.
     */
    private fun findGitDir(startDir: Path): Path? {
        for (dir in generateSequence(startDir) { it.parent }) {
            val gitPath = dir.resolve(".git")

            if (gitPath.isDirectory()) return gitPath

            if (gitPath.isRegularFile()) {
                val target = gitPath.readText().trim().removePrefix("gitdir:").trim()
                if (target.isEmpty()) continue

                val resolved = dir.resolve(target).normalize()
                if (resolved.isDirectory()) return resolved
            }
        }
        return null
    }

    private fun executeGitCommand(workingDir: Path, vararg command: String): String? {
        try {
            val process = ProcessBuilder(*command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()

            // Wait max 2 seconds for git command to prevent hanging
            val completed = process.waitFor(2, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return null
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            return if (process.exitValue() == 0 && output.isNotEmpty()) output else null
        } catch (e: Exception) {
            return null
        }
    }
}
