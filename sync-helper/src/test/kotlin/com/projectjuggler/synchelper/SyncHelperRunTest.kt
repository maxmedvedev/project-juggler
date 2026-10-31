package com.projectjuggler.synchelper

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SelfSyncResultStore
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.core.SyncTypeNames
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.SyncException
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

@OptIn(ExperimentalPathApi::class)
class SyncHelperRunTest : StringSpec({
    extensions(KoinTestExtension())

    // The test task points IdeRegistry.PROJECT_JUGGLER_BASE_DIR at build/project-juggler-test
    val v2Dir = IdeRegistry.getDefaultBaseDir().resolve("v2")
    val projectsDir = createTempDir("sync-helper-projects")
    val installation = IdeInstallation("/test/ide/bin/idea", "Test IDE")

    beforeTest {
        if (v2Dir.exists()) v2Dir.deleteRecursively()
    }

    afterSpec {
        if (v2Dir.exists()) v2Dir.deleteRecursively()
    }

    fun registerIde(): IdeConfigRepository =
        IdeConfigRepository.create(installation, v2Dir).also { it.save(IdeConfig.default(installation)) }

    fun registerProject(repository: IdeConfigRepository, name: String = "project"): ProjectMetadata {
        val dir = Files.createDirectories(projectsDir.resolve(name))
        val projectManager = ProjectManager.getInstance(repository)
        return projectManager.registerOrUpdate(projectManager.resolvePath(dir.toString()))
    }

    fun syncingLauncher(): ProjectLauncher = mockk {
        every { syncProject(any(), any(), any(), any(), any()) } just runs
    }

    fun runHelper(vararg args: String, launcher: ProjectLauncher = syncingLauncher()): Int =
        run(args.toList().toTypedArray(), IdeRegistry.getInstance(), launcherFor = { launcher })

    fun args(project: ProjectMetadata, vararg types: String) =
        arrayOf("--ide", installation.executablePath, "--path", project.path.pathString, *types)

    fun consumeResult(repository: IdeConfigRepository, project: ProjectMetadata) =
        SelfSyncResultStore.getInstance(repository).consume(project)

    "a successful sync returns 0, passes the flags and stop/restart, and writes a success result" {
        val repository = registerIde()
        val project = registerProject(repository)
        val launcher = syncingLauncher()
        val options = slot<SyncOptions>()
        every { launcher.syncProject(any(), any(), any(), any(), capture(options)) } just runs

        runHelper(*args(project, "--vmoptions", "--plugins"), launcher = launcher) shouldBe EXIT_OK

        verify { launcher.syncProject(project, true, false, true, any()) }
        options.captured.stopIfRunning shouldBe true
        options.captured.autoRestart shouldBe true
        val result = consumeResult(repository, project).shouldNotBeNull()
        result.succeeded shouldBe true
        result.syncTypes shouldBe listOf(SyncTypeNames.VM_OPTIONS, SyncTypeNames.PLUGINS)
        result.message.shouldBeNull()
        result.restartError.shouldBeNull()
    }

    "a failing sync returns 1 and writes a failure result with the message" {
        val repository = registerIde()
        val project = registerProject(repository)
        val launcher = mockk<ProjectLauncher> {
            every { syncProject(any(), any(), any(), any(), any()) } throws SyncException("Base config path is not set")
        }

        runHelper(*args(project, "--config"), launcher = launcher) shouldBe EXIT_FAILURE

        val result = consumeResult(repository, project).shouldNotBeNull()
        result.succeeded shouldBe false
        result.syncTypes shouldBe listOf(SyncTypeNames.CONFIG)
        result.message shouldBe "Base config path is not set"
    }

    "an exception without a message is reported by its class name" {
        val repository = registerIde()
        val project = registerProject(repository)
        val launcher = mockk<ProjectLauncher> {
            every { syncProject(any(), any(), any(), any(), any()) } throws IllegalStateException()
        }

        runHelper(*args(project, "--all"), launcher = launcher) shouldBe EXIT_FAILURE

        consumeResult(repository, project).shouldNotBeNull().message shouldBe "java.lang.IllegalStateException"
    }

    "a progress error becomes the restart error" {
        val repository = registerIde()
        val project = registerProject(repository)
        val launcher = mockk<ProjectLauncher> {
            every { syncProject(any(), any(), any(), any(), any()) } answers {
                val options = arg<SyncOptions>(4)
                options.onProgress(SyncProgress.Restarting)
                options.onProgress(SyncProgress.Error("Failed to restart IntelliJ: boom"))
            }
        }

        runHelper(*args(project, "--plugins"), launcher = launcher) shouldBe EXIT_OK

        val result = consumeResult(repository, project).shouldNotBeNull()
        result.succeeded shouldBe true
        result.restartError shouldBe "Failed to restart IntelliJ: boom"
    }

    "a usage error returns 2 without syncing" {
        val repository = registerIde()
        val project = registerProject(repository)
        val launcher = syncingLauncher()

        runHelper(*args(project), launcher = launcher) shouldBe EXIT_USAGE

        verify(exactly = 0) { launcher.syncProject(any(), any(), any(), any(), any()) }
        consumeResult(repository, project).shouldBeNull()
    }

    "an unknown IDE returns 1 and creates no dir" {
        val launcher = syncingLauncher()

        runHelper("--ide", "/unknown/ide", "--path", projectsDir.toString(), "--all", launcher = launcher) shouldBe
            EXIT_FAILURE

        verify(exactly = 0) { launcher.syncProject(any(), any(), any(), any(), any()) }
        if (v2Dir.exists()) v2Dir.listDirectoryEntries().shouldBeEmpty()
    }

    "an unknown project returns 1 without syncing" {
        registerIde()
        val launcher = syncingLauncher()
        val untracked: Path = Files.createDirectories(projectsDir.resolve("untracked"))

        runHelper("--ide", installation.executablePath, "--path", untracked.toString(), "--all", launcher = launcher) shouldBe
            EXIT_FAILURE

        verify(exactly = 0) { launcher.syncProject(any(), any(), any(), any(), any()) }
    }
})
