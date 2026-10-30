package com.projectjuggler.util

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.locators.ConfigLocator
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText

class ProjectLockUtilsTest : StringSpec({
    extensions(KoinTestExtension())

    val tempDir = createTempDir("lock-utils")
    var counter = 0

    fun newRepo(): IdeConfigRepository =
        IdeConfigRepository(tempDir.resolve("repo-${counter++}"), IdeInstallation("/test/ide", "Test IDE"))

    fun newProject(): ProjectPath =
        ProjectPath(tempDir.resolve("project-${counter++}").createDirectories().toString())

    fun isolatedConfigDir(repo: IdeConfigRepository, project: ProjectPath): Path =
        DirectoryManager.getInstance(repo).getProjectRoot(project).resolve("config").createDirectories()

    fun currentPid(): Int = ProcessHandle.current().pid().toInt()

    fun deadPid(): Int {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) return Int.MAX_VALUE - 7
        val process = ProcessBuilder("true").start()
        process.waitFor()
        return process.pid().toInt()
    }

    afterTest {
        unmockkObject(ConfigLocator)
    }

    "isolated project without lock is not open" {
        val repo = newRepo()
        val project = newProject()
        isolatedConfigDir(repo, project)

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe false
        ProjectLockUtils.isIsolatedInstanceOpen(repo, project) shouldBe false
    }

    "isolated project with lock holding the current PID is open" {
        val repo = newRepo()
        val project = newProject()
        isolatedConfigDir(repo, project).resolve(".lock").writeText(currentPid().toString())

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe true
        ProjectLockUtils.isIsolatedInstanceOpen(repo, project) shouldBe true
    }

    "isolated project with lock holding a dead PID is not open" {
        val repo = newRepo()
        val project = newProject()
        isolatedConfigDir(repo, project).resolve(".lock").writeText(deadPid().toString())

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe false
        ProjectLockUtils.isIsolatedInstanceOpen(repo, project) shouldBe false
    }

    "isolated project with non-numeric lock content is open" {
        val repo = newRepo()
        val project = newProject()
        isolatedConfigDir(repo, project).resolve(".lock").writeText("port=6942\ntoken")

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe true
    }

    "readPidFromLock returns the pid or null" {
        val repo = newRepo()
        val project = newProject()
        val lock = isolatedConfigDir(repo, project).resolve(".lock")

        ProjectLockUtils.readPidFromLock(repo, project) shouldBe null

        lock.writeText(" 12345\n")
        ProjectLockUtils.readPidFromLock(repo, project) shouldBe 12345

        lock.writeText("port=6942\ntoken")
        ProjectLockUtils.readPidFromLock(repo, project) shouldBe null
    }

    "main project uses configured base config dir" {
        val repo = newRepo()
        val project = newProject()
        val baseConfig = tempDir.resolve("base-config-${counter++}").createDirectories()
        MainProjectService.setMainProject(repo, project)
        repo.update { it.copy(baseConfigPath = baseConfig.toString()) }
        val lock = baseConfig.resolve(".lock")
        lock.writeText(currentPid().toString())

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe true
        ProjectLockUtils.readPidFromLock(repo, project) shouldBe currentPid()

        lock.deleteExisting()
        ProjectLockUtils.isProjectOpen(repo, project) shouldBe false
    }

    "main project falls back to detected config dir" {
        val repo = newRepo()
        val project = newProject()
        val detected = tempDir.resolve("detected-${counter++}").createDirectories()
        detected.resolve(".lock").writeText(currentPid().toString())
        MainProjectService.setMainProject(repo, project)
        mockkObject(ConfigLocator)
        every { ConfigLocator.findDefaultConfigDirectory() } returns detected

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe true
        ProjectLockUtils.readPidFromLock(repo, project) shouldBe currentPid()
    }

    "main project with no config dir is not open" {
        val repo = newRepo()
        val project = newProject()
        MainProjectService.setMainProject(repo, project)
        mockkObject(ConfigLocator)
        every { ConfigLocator.findDefaultConfigDirectory() } returns null

        ProjectLockUtils.isProjectOpen(repo, project) shouldBe false
        ProjectLockUtils.readPidFromLock(repo, project) shouldBe null
    }

    "isProcessRunning" {
        ProjectLockUtils.isProcessRunning(currentPid()) shouldBe true
        ProjectLockUtils.isProcessRunning(deadPid()) shouldBe false
    }
})
