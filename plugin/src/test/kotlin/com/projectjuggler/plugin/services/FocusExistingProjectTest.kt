package com.projectjuggler.plugin.services

import com.intellij.openapi.progress.ProcessCanceledException
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.markIsolatedInstanceOpen
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.process.WindowFocuser
import com.projectjuggler.test.createTempDir
import com.projectjuggler.util.ProjectLockUtils
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.io.path.createTempDirectory

class FocusExistingProjectTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectPath = ProjectPath(createTempDir("project").toString())
    val livePid = ProcessHandle.current().pid().toInt()

    // A fresh repository per test: the lock written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    afterTest { unmockkAll() }

    /** A repository with a running isolated instance of the project whose window focusing yields [result]. */
    fun runningRepository(result: WindowFocuser.FocusResult) = newRepository().also {
        markIsolatedInstanceOpen(it, projectPath)
        mockkObject(WindowFocuser)
        every { WindowFocuser.focus(livePid) } returns result
    }

    fun focusAndCollectErrors(repository: IdeConfigRepository): List<String> {
        val handler = RecordingNotificationHandler()
        focusExistingProject(projectPath, repository, handler)
        return handler.errors
    }

    "a project without a lock cannot be focused" {
        focusAndCollectErrors(newRepository()) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.no.pid", projectPath.name)
        )
    }

    "a lock of a dead process cannot be focused" {
        val repository = newRepository()
        markIsolatedInstanceOpen(repository, projectPath)
        mockkObject(ProjectLockUtils)
        every { ProjectLockUtils.isProcessRunning(any()) } returns false

        focusAndCollectErrors(repository) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, livePid)
        )
    }

    "a focused window is silent" {
        focusAndCollectErrors(runningRepository(WindowFocuser.FocusResult.Success)).shouldBeEmpty()
    }

    "a process the focuser cannot find is reported" {
        focusAndCollectErrors(runningRepository(WindowFocuser.FocusResult.ProcessNotFound(livePid))) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.process.not.found", projectPath.name, livePid)
        )
    }

    "a missing window is reported" {
        focusAndCollectErrors(runningRepository(WindowFocuser.FocusResult.WindowNotFound(livePid))) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.window.not.found", projectPath.name)
        )
    }

    "a failed focus command is reported" {
        focusAndCollectErrors(runningRepository(WindowFocuser.FocusResult.CommandFailed("denied"))) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.failed", projectPath.name, "denied")
        )
    }

    "a missing focus tool is reported" {
        focusAndCollectErrors(runningRepository(WindowFocuser.FocusResult.ToolNotInstalled("wmctrl"))) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.tool.missing", "wmctrl")
        )
    }

    "an exception while focusing is reported" {
        val repository = newRepository()
        markIsolatedInstanceOpen(repository, projectPath)
        mockkObject(WindowFocuser)
        every { WindowFocuser.focus(livePid) } throws IllegalStateException("boom")

        focusAndCollectErrors(repository) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.exception", projectPath.name, "boom")
        )
    }

    "an error that is not an exception is reported too" {
        val repository = newRepository()
        markIsolatedInstanceOpen(repository, projectPath)
        mockkObject(WindowFocuser)
        every { WindowFocuser.focus(livePid) } throws AssertionError("x")

        focusAndCollectErrors(repository) shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.focus.exception", projectPath.name, "x")
        )
    }

    "a control-flow exception while focusing propagates without a notification" {
        val repository = newRepository()
        markIsolatedInstanceOpen(repository, projectPath)
        mockkObject(WindowFocuser)
        every { WindowFocuser.focus(livePid) } throws ProcessCanceledException()
        val handler = RecordingNotificationHandler()

        shouldThrow<ProcessCanceledException> { focusExistingProject(projectPath, repository, handler) }

        handler.errors.shouldBeEmpty()
    }
})
