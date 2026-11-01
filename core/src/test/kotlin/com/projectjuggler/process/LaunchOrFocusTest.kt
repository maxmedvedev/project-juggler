package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.process.WindowFocuser.FocusResult
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class LaunchOrFocusTest : StringSpec({
    extensions(KoinTestExtension())

    val tempDir = createTempDir("launch-or-focus")
    var counter = 0

    fun newRepo(): IdeConfigRepository =
        IdeConfigRepository(tempDir.resolve("repo-${counter++}"), IdeInstallation("/test/ide", "Test IDE"))

    fun newProject(): ProjectPath =
        ProjectPath(tempDir.resolve("project-${counter++}").createDirectories().toString())

    fun writeLock(repo: IdeConfigRepository, project: ProjectPath, content: String) {
        DirectoryManager.getInstance(repo).getProjectRoot(project).resolve("config").createDirectories()
            .resolve(".lock").writeText(content)
    }

    fun currentPid(): Int = ProcessHandle.current().pid().toInt()

    fun deadPid(): Int {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) return Int.MAX_VALUE - 7
        val process = ProcessBuilder("true").start()
        process.waitFor()
        return process.pid().toInt()
    }

    "an unregistered project is launched and planning creates no dirs" {
        val repo = newRepo()
        val project = newProject()

        planOpen(repo, project) shouldBe OpenStep.LAUNCH
        DirectoryManager.getInstance(repo).getProjectRoot(project).shouldNotExist()
    }

    "a project without a live lock is launched" {
        val repo = newRepo()
        val project = newProject()
        writeLock(repo, project, deadPid().toString())

        planOpen(repo, project) shouldBe OpenStep.LAUNCH
    }

    "a project with a live lock is focused" {
        val repo = newRepo()
        val project = newProject()
        writeLock(repo, project, currentPid().toString())

        planOpen(repo, project) shouldBe OpenStep.FOCUS
    }

    "the main project is launched even when its isolated lock is live" {
        val repo = newRepo()
        val project = newProject()
        writeLock(repo, project, currentPid().toString())
        MainProjectService.setMainProject(repo, project)

        planOpen(repo, project) shouldBe OpenStep.LAUNCH
    }

    "focusing a project without a lock gives LockNotFound" {
        val calls = mutableListOf<Int>()

        focusRunningProject(newRepo(), newProject()) { calls += it; FocusResult.Success } shouldBe
            FocusResult.LockNotFound
        calls.shouldBeEmpty()
    }

    "focusing a dead process gives ProcessNotFound without calling the focuser" {
        val repo = newRepo()
        val project = newProject()
        val pid = deadPid()
        writeLock(repo, project, pid.toString())
        val calls = mutableListOf<Int>()

        focusRunningProject(repo, project) { calls += it; FocusResult.Success } shouldBe FocusResult.ProcessNotFound(pid)
        calls.shouldBeEmpty()
    }

    "focusing a live process passes the focuser result through" {
        val repo = newRepo()
        val project = newProject()
        writeLock(repo, project, currentPid().toString())
        val calls = mutableListOf<Int>()

        focusRunningProject(repo, project) { calls += it; FocusResult.CommandFailed("denied") } shouldBe
            FocusResult.CommandFailed("denied")
        calls shouldBe listOf(currentPid())
    }
})
