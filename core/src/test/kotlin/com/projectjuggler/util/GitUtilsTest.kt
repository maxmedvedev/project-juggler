package com.projectjuggler.util

import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

/**
 * Branch detection has to work for projects opened by selecting a file (e.g. `module.bazel`)
 * as well as for plain project directories.
 */
class GitUtilsTest : StringSpec({
    val baseDir = createTempDir("test-git-utils")

    fun repo(name: String, head: String = "ref: refs/heads/feature-x"): Path {
        val repoDir = baseDir.resolve(name)
        repoDir.resolve(".git").createDirectories().resolve("HEAD").writeText(head)
        return repoDir
    }

    "should detect branch for a file-based project" {
        val repoDir = repo("file-based")
        val moduleFile = repoDir.resolve("module.bazel").createFile()

        GitUtils.detectGitBranch(moduleFile) shouldBe "feature-x"
    }

    "should detect branch for a project directory" {
        GitUtils.detectGitBranch(repo("dir-based")) shouldBe "feature-x"
    }

    "should detect branch for a file nested below the repository root" {
        val repoDir = repo("nested")
        val moduleFile = repoDir.resolve("sub/dir").createDirectories().resolve("module.bazel").createFile()

        GitUtils.detectGitBranch(moduleFile) shouldBe "feature-x"
    }

    "should report detached HEAD" {
        val repoDir = repo("detached", head = "a".repeat(40))

        GitUtils.detectGitBranch(repoDir.resolve("module.bazel").createFile()) shouldBe "HEAD (detached)"
    }

    "should follow a .git file pointing at the real git directory" {
        val worktreeDir = baseDir.resolve("worktree").createDirectories()
        val realGitDir = baseDir.resolve("real-git-dir").createDirectories()
        realGitDir.resolve("HEAD").writeText("ref: refs/heads/worktree-branch")
        worktreeDir.resolve(".git").writeText("gitdir: ../real-git-dir\n")

        val moduleFile = worktreeDir.resolve("module.bazel").createFile()

        GitUtils.detectGitBranch(moduleFile) shouldBe "worktree-branch"
    }

    "should return null when there is no repository above the path" {
        val plainDir = baseDir.resolve("no-git").createDirectories()
        val plainFile = plainDir.resolve("module.bazel").createFile()

        GitUtils.detectGitBranch(plainFile) shouldBe null
        GitUtils.detectGitBranch(plainDir) shouldBe null
        GitUtils.detectGitBranch(plainDir.resolve("missing")) shouldBe null
    }
})
