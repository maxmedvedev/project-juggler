package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
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

    "validateProjectCopy rejects a source that is not a directory" {
        validateProjectCopy(fileSource, destination) shouldBe
            ProjectJugglerBundle.message("notification.error.not.directory", fileSource.pathString)
    }

    "validateProjectCopy rejects a source that is not a git repository" {
        validateProjectCopy(plainSource, destination) shouldBe
            ProjectJugglerBundle.message("notification.error.not.git.repository", plainSource.pathString)
    }

    "validateProjectCopy rejects an existing destination" {
        validateProjectCopy(gitSource, existingDestination) shouldBe
            ProjectJugglerBundle.message("notification.error.destination.exists", existingDestination.pathString)
    }

    "validateProjectCopy accepts a git source and a new destination" {
        validateProjectCopy(gitSource, destination).shouldBeNull()
    }

    "createAndLaunchProjectCopy creates the worktree, then launches it" {
        mockkObject(GitWorktreeManager)
        every { GitWorktreeManager.createWorktree(any(), any(), any(), any()) } just runs
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        createAndLaunchProjectCopy(repository, gitSource, destination, "feature", handler, launcher)

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
        every { GitWorktreeManager.createWorktree(any(), any(), any(), any()) } throws IOException("git failed")
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        val logged = expectLoggedError {
            createAndLaunchProjectCopy(repository, gitSource, destination, "feature", handler, launcher)
        }

        logged.shouldBeInstanceOf<IOException>()
        verify(exactly = 0) { launcher.launch(any(), any()) }
        handler.infos.shouldBeEmpty()
        handler.errors shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.worktree.failed", "git failed")
        )
    }
})
