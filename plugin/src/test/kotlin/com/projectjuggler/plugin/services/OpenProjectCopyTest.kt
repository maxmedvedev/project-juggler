package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
import com.projectjuggler.platform.Platform
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.expectLoggedError
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.plugin.test.writeGitHead
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.test.createTempDir
import com.projectjuggler.util.GitWorktreeManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText

class OpenProjectCopyTest : StringSpec({

    val workDir = createTempDir("project-copy")
    val repository = testRepository(createTempDir("juggler"))

    val gitSource = ProjectPath(workDir.resolve("repo").createDirectory().also { writeGitHead(it, "main") }.toString())
    val plainSource = ProjectPath(workDir.resolve("plain").createDirectory().toString())
    val fileSource = ProjectPath(workDir.resolve("file.txt").apply { writeText("x") }.toString())
    val existingDestination = ProjectPath(workDir.resolve("taken").createDirectory().toString())
    val destination = ProjectPath(workDir.resolve("copy").toString())

    afterTest { unmockkAll() }

    fun input(source: ProjectPath = gitSource, branch: String = "feature", dest: String = destination.pathString) =
        ProjectCopyInput(source.pathString, branch, dest)

    fun invalid(field: ProjectCopyField, key: String, vararg params: Any) =
        ProjectCopyValidation.Invalid(field, ProjectJugglerBundle.message(key, *params))

    "validateProjectCopyInput rejects a blank source" {
        validateProjectCopyInput(ProjectCopyInput("  ", "feature", destination.pathString)) shouldBe
            invalid(ProjectCopyField.SOURCE, "dialog.project.copy.error.blank.source")
    }

    "validateProjectCopyInput rejects a blank branch" {
        validateProjectCopyInput(input(branch = " ")) shouldBe
            invalid(ProjectCopyField.BRANCH, "dialog.project.copy.error.blank.branch")
    }

    "validateProjectCopyInput rejects a blank destination" {
        validateProjectCopyInput(input(dest = "")) shouldBe
            invalid(ProjectCopyField.DESTINATION, "dialog.project.copy.error.blank.destination")
    }

    "validateProjectCopyInput rejects a source that is not a directory" {
        validateProjectCopyInput(input(source = fileSource)) shouldBe
            invalid(ProjectCopyField.SOURCE, "notification.error.not.directory", fileSource.pathString)
    }

    "validateProjectCopyInput rejects a source that is not a git repository" {
        validateProjectCopyInput(input(source = plainSource)) shouldBe
            invalid(ProjectCopyField.SOURCE, "notification.error.not.git.repository", plainSource.pathString)
    }

    "validateProjectCopyInput rejects an existing destination" {
        validateProjectCopyInput(input(dest = existingDestination.pathString)) shouldBe
            invalid(ProjectCopyField.DESTINATION, "notification.error.destination.exists", existingDestination.pathString)
    }

    "validateProjectCopyInput rejects an unparsable destination" {
        validateProjectCopyInput(input(dest = "\u0000")) shouldBe
            invalid(ProjectCopyField.DESTINATION, "dialog.project.copy.error.invalid.path", "\u0000")
    }

    "validateProjectCopyInput accepts valid input with normalized paths and a trimmed branch" {
        val raw = ProjectCopyInput(
            " ${workDir.resolve("plain/../repo")} ",
            " feature/x ",
            " ${workDir.resolve("plain/../copy")} ",
        )

        validateProjectCopyInput(raw) shouldBe ProjectCopyValidation.Valid(
            ProjectCopyRequest(gitSource, destination, "feature/x")
        )
    }

    "validateProjectCopyInput resolves a tilde source under user.home".config(enabled = Platform.isUnix()) {
        val home = Path(System.getProperty("user.home")).toAbsolutePath().normalize()
        val missing = "project-juggler-no-such-dir-${System.nanoTime()}"

        validateProjectCopyInput(ProjectCopyInput("~/$missing", "feature", destination.pathString)) shouldBe
            invalid(ProjectCopyField.SOURCE, "notification.error.not.directory", "~/$missing")
        suggestDestination("~/$missing", "feature") shouldBe home.resolve("$missing-feature").toString()
    }

    "suggestDestination appends the branch with slashes replaced to the source name".config(enabled = Platform.isUnix()) {
        suggestDestination("/a/repo", "feature/x") shouldBe "/a/repo-feature-x"
    }

    "suggestDestination is null for a blank branch or source" {
        suggestDestination("/a/repo", "  ").shouldBeNull()
        suggestDestination(" ", "feature").shouldBeNull()
    }

    "openProjectCopy reports invalid input on the re-check and creates nothing" {
        mockkObject(GitWorktreeManager)
        val handler = RecordingNotificationHandler()

        openProjectCopy(input(dest = existingDestination.pathString), handler) { repository }

        verify(exactly = 0) { GitWorktreeManager.createWorktree(any(), any(), any(), any()) }
        handler.infos.shouldBeEmpty()
        handler.errors shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.destination.exists", existingDestination.pathString)
        )
    }

    "createAndLaunchProjectCopy creates the worktree, then launches it" {
        mockkObject(GitWorktreeManager)
        every { GitWorktreeManager.createWorktree(any(), any(), any(), any()) } just runs
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        createAndLaunchProjectCopy(repository, ProjectCopyRequest(gitSource, destination, "feature"), handler, launcher)

        verifyOrder {
            GitWorktreeManager.createWorktree(gitSource.path, destination.path, "feature", null)
            launcher.launch(destination)
        }
        handler.infos shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.success.worktree.launched", "copy", "feature")
        )
        handler.errors.shouldBeEmpty()
    }

    "createAndLaunchProjectCopy reports a worktree failure and does not launch" {
        mockkObject(GitWorktreeManager)
        every { GitWorktreeManager.createWorktree(any(), any(), any(), any()) } throws IOException()
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        val logged = expectLoggedError {
            createAndLaunchProjectCopy(repository, ProjectCopyRequest(gitSource, destination, "feature"), handler, launcher)
        }

        logged.shouldBeInstanceOf<IOException>()
        verify(exactly = 0) { launcher.launch(any(), any()) }
        handler.infos.shouldBeEmpty()
        handler.errors shouldContainExactly listOf(
            ProjectJugglerBundle.message(
                "notification.error.worktree.failed",
                ProjectJugglerBundle.message("error.unknown")
            )
        )
    }
})
