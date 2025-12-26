package com.ideajuggler.util

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object GitUtils {
    /**
     * Detects the current git branch for a project directory.
     * Returns null if not a git repository or if an error occurs.
     *
     * @param projectPath The path to the project directory
     * @return The branch name, "HEAD (detached)" for detached HEAD, or null if not a git repo or error
     */
    fun detectGitBranch(projectPath: Path): String? {
        try {
            val gitDir = projectPath.resolve(".git")
            if (!gitDir.exists()) return null

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
            return executeGitCommand(projectPath, "git", "rev-parse", "--abbrev-ref", "HEAD")
        } catch (e: Exception) {
            // Silently return null on any error
            return null
        }
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
