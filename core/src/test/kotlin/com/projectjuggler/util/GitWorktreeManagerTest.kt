package com.projectjuggler.util

import com.projectjuggler.test.createTempDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/**
 * Runs against a real `git`; skipped when `git` is not on PATH.
 * The timeout path is not unit tested (it would need a hanging `git`); it relies on [GitCommandRunner]'s
 * `waitFor(timeout)` contract.
 */
class GitWorktreeManagerTest : StringSpec({
    val gitAvailable = runCatching {
        ProcessBuilder("git", "--version").start().waitFor(5, TimeUnit.SECONDS)
    }.getOrDefault(false)

    val baseDir = createTempDir("test-git-worktree")
    val repo = baseDir.resolve("repo")

    fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: $output"
        }
    }

    beforeSpec {
        if (gitAvailable) {
            repo.createDirectories()
            git(repo, "init", "-q")
            git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "init")
        }
    }

    "should create a worktree on a new branch".config(enabledIf = { gitAvailable }) {
        val wt = baseDir.resolve("wt1")

        GitWorktreeManager.createWorktree(repo, wt, "feature-1")

        wt.resolve(".git").isRegularFile() shouldBe true
        GitUtils.detectGitBranch(wt) shouldBe "feature-1"
    }

    "should throw a single IOException when the branch already exists".config(enabledIf = { gitAvailable }) {
        GitWorktreeManager.createWorktree(repo, baseDir.resolve("wt-dup-1"), "dup")

        val e = shouldThrow<IOException> {
            GitWorktreeManager.createWorktree(repo, baseDir.resolve("wt-dup-2"), "dup")
        }

        e.message!! shouldStartWith "Failed to create git worktree: "
        e.message!! shouldNotContain "Failed to execute git command"
        e.cause shouldBe null
    }

    "should reject invalid inputs".config(enabledIf = { gitAvailable }) {
        val notARepo = baseDir.resolve("not-a-repo").createDirectories()
        val existing = baseDir.resolve("existing").createDirectories()

        shouldThrow<IllegalArgumentException> {
            GitWorktreeManager.createWorktree(baseDir.resolve("missing"), baseDir.resolve("wt-x1"), "x1")
        }
        shouldThrow<IllegalArgumentException> {
            GitWorktreeManager.createWorktree(notARepo, baseDir.resolve("wt-x2"), "x2")
        }
        shouldThrow<IllegalArgumentException> {
            GitWorktreeManager.createWorktree(repo, existing, "x3")
        }
        shouldThrow<IllegalArgumentException> {
            GitWorktreeManager.createWorktree(repo, baseDir.resolve("wt-x4"), " ")
        }
    }

    "should pass progress messages to the callback".config(enabledIf = { gitAvailable }) {
        val messages = mutableListOf<String>()

        GitWorktreeManager.createWorktree(repo, baseDir.resolve("wt-progress"), "progress") { messages += it }

        messages shouldHaveSize 2
    }
})
