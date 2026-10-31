package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.process.LaunchFailure
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class LaunchProjectTest : StringSpec({

    val repository = testRepository(createTempDir("juggler"))
    val projectPath = ProjectPath(createTempDir("project").toString())

    /** A launcher whose process fails with [failure] right away. */
    fun crashingLauncher(failure: LaunchFailure) = mockk<ProjectLauncher> {
        every { launch(any(), any()) } answers { secondArg<(LaunchFailure) -> Unit>()(failure) }
    }

    fun crashedMessage(exitCode: Int, output: String) =
        ProjectJugglerBundle.message("notification.error.launch.crashed", projectPath.name, exitCode, output)

    "launches the project and stays silent" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, launcher)

        verify(exactly = 1) { launcher.launch(projectPath, any()) }
        handler.errors.shouldBeEmpty()
        handler.infos.shouldBeEmpty()
    }

    "a crash reports the exit code and stderr" {
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, crashingLauncher(LaunchFailure(3, "out", "err")))

        handler.errors shouldContainExactly listOf(crashedMessage(3, "err"))
    }

    "a crash with blank stderr reports stdout" {
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, crashingLauncher(LaunchFailure(1, "out", " ")))

        handler.errors shouldContainExactly listOf(crashedMessage(1, "out"))
    }

    "a crash without output reports a placeholder" {
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, crashingLauncher(LaunchFailure(1, "", "")))

        handler.errors shouldContainExactly listOf(crashedMessage(1, ProjectJugglerBundle.message("notification.error.launch.no.output")))
    }

    "a crash keeps at most 500 characters of output" {
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, crashingLauncher(LaunchFailure(1, "", "e".repeat(600))))

        handler.errors shouldContainExactly listOf(crashedMessage(1, "e".repeat(500)))
    }

    "a failure to start reports the error" {
        val launcher = mockk<ProjectLauncher> {
            every { launch(any(), any()) } throws IllegalStateException("no executable")
        }
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, launcher)

        handler.errors shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.launch.failed", "no executable")
        )
    }

    "a failure to start without a message reports an unknown error" {
        val launcher = mockk<ProjectLauncher> {
            every { launch(any(), any()) } throws IllegalStateException()
        }
        val handler = RecordingNotificationHandler()

        launchProject(projectPath, repository, handler, launcher)

        handler.errors shouldContainExactly listOf(
            ProjectJugglerBundle.message(
                "notification.error.launch.failed",
                ProjectJugglerBundle.message("error.unknown")
            )
        )
    }
})
