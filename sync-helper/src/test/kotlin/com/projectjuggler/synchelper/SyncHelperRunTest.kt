package com.projectjuggler.synchelper

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SelfSyncResultStore
import com.projectjuggler.core.SyncHelperInstallation
import com.projectjuggler.core.SyncHelperInstallationStore
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.core.SyncTypeNames
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.platform.Platform
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.process.SyncException
import com.projectjuggler.process.WindowFocuser.FocusResult
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
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

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

    fun runHelper(
        vararg args: String,
        launcher: ProjectLauncher = syncingLauncher(),
        focus: (Int) -> FocusResult = { error("focus must not be called") },
        dispatcher: HelperDispatcher = HelperDispatcher(ownInstallDir = null, alreadyDispatched = false) { _, _ ->
            error("no helper must be started")
        },
    ): Int = run(
        args.toList().toTypedArray(), IdeRegistry.getInstance(), launcherFor = { launcher }, focus = focus,
        dispatcher = dispatcher,
    )

    /** A fake JBR and helper distribution recorded as [repository]'s helper, with the files `isUsable` checks. */
    fun recordHelper(repository: IdeConfigRepository, name: String, usable: Boolean = true): SyncHelperInstallation {
        val root = Files.createDirectories(projectsDir.resolve("helpers").resolve(name))
        val javaHome = root.resolve("jbr")
        val installDir = root.resolve("helper")
        if (usable) {
            Files.createDirectories(javaHome.resolve("bin")).resolve(if (Platform.current() == Platform.WINDOWS) "java.exe" else "java").createFile()
            Files.createDirectories(installDir.resolve("lib")).resolve("sync-helper.jar").createFile()
        }
        return SyncHelperInstallation(javaHome.toString(), installDir.toString())
            .also(SyncHelperInstallationStore.getInstance(repository)::write)
    }

    class StartedHelper(val command: List<String>, val environment: Map<String, String>)

    fun launchingLauncher(): ProjectLauncher = mockk {
        every { launch(any(), any()) } just runs
    }

    fun openArgs(path: String) = arrayOf("open", "--ide", installation.executablePath, "--path", path)

    /** Marks [project]'s isolated IDE as running in this test JVM. */
    fun markRunning(repository: IdeConfigRepository, project: ProjectMetadata): Int {
        val pid = ProcessHandle.current().pid().toInt()
        val configDir = DirectoryManager.getInstance(repository).getProjectRoot(project.path).resolve("config")
        Files.createDirectories(configDir).resolve(".lock").writeText(pid.toString())
        return pid
    }

    fun args(project: ProjectMetadata, vararg types: String) =
        arrayOf("sync", "--ide", installation.executablePath, "--path", project.path.pathString, *types)

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

        runHelper("sync", "--ide", "/unknown/ide", "--path", projectsDir.toString(), "--all", launcher = launcher) shouldBe
            EXIT_FAILURE

        verify(exactly = 0) { launcher.syncProject(any(), any(), any(), any(), any()) }
        if (v2Dir.exists()) v2Dir.listDirectoryEntries().shouldBeEmpty()
    }

    "an unknown project returns 1 without syncing" {
        registerIde()
        val launcher = syncingLauncher()
        val untracked: Path = Files.createDirectories(projectsDir.resolve("untracked"))

        runHelper("sync", "--ide", installation.executablePath, "--path", untracked.toString(), "--all", launcher = launcher) shouldBe
            EXIT_FAILURE

        verify(exactly = 0) { launcher.syncProject(any(), any(), any(), any(), any()) }
    }

    "open launches a project that is not running, also a new one" {
        registerIde()
        val dir = Files.createDirectories(projectsDir.resolve("new-project"))
        val launcher = launchingLauncher()

        runHelper(*openArgs(dir.toString()), launcher = launcher) shouldBe EXIT_OK

        verify { launcher.launch(ProjectPath(dir.toString()), any()) }
    }

    "open focuses the window of a running project" {
        val repository = registerIde()
        val project = registerProject(repository, "running")
        val pid = markRunning(repository, project)
        val launcher = launchingLauncher()
        val focused = mutableListOf<Int>()

        runHelper(*openArgs(project.path.pathString), launcher = launcher, focus = {
            focused += it
            FocusResult.Success
        }) shouldBe EXIT_OK

        focused shouldBe listOf(pid)
        verify(exactly = 0) { launcher.launch(any(), any()) }
    }

    "open returns 1 when focusing fails" {
        val repository = registerIde()
        val project = registerProject(repository, "unfocusable")
        markRunning(repository, project)

        runHelper(*openArgs(project.path.pathString), launcher = launchingLauncher(), focus = {
            FocusResult.WindowNotFound(it)
        }) shouldBe EXIT_FAILURE
    }

    "open returns 1 when launching throws" {
        registerIde()
        val dir = Files.createDirectories(projectsDir.resolve("broken"))
        val launcher = mockk<ProjectLauncher> {
            every { launch(any(), any()) } throws IllegalStateException("no executable")
        }

        runHelper(*openArgs(dir.toString()), launcher = launcher) shouldBe EXIT_FAILURE
    }

    "open of an unknown IDE returns 1 and creates no dir" {
        val launcher = launchingLauncher()

        runHelper("open", "--ide", "/unknown/ide", "--path", projectsDir.toString(), launcher = launcher) shouldBe
            EXIT_FAILURE

        verify(exactly = 0) { launcher.launch(any(), any()) }
        if (v2Dir.exists()) v2Dir.listDirectoryEntries().shouldBeEmpty()
    }

    "open of a missing path returns 1 without launching" {
        registerIde()
        val launcher = launchingLauncher()

        runHelper(*openArgs(projectsDir.resolve("missing").toString()), launcher = launcher) shouldBe EXIT_FAILURE

        verify(exactly = 0) { launcher.launch(any(), any()) }
    }

    "a command for an IDE with another recorded helper runs in that helper and returns its exit code" {
        val repository = registerIde()
        val target = recordHelper(repository, "other")
        val launcher = launchingLauncher()
        val started = mutableListOf<StartedHelper>()
        val dispatcher = HelperDispatcher(ownInstallDir = projectsDir.resolve("own"), alreadyDispatched = false) { command, env ->
            started += StartedHelper(command, env)
            7
        }
        val args = openArgs(projectsDir.toString())

        runHelper(*args, launcher = launcher, dispatcher = dispatcher) shouldBe 7

        started.single().command shouldBe target.command(IdeRegistry.getDefaultBaseDir().toString(), args.toList())
        started.single().environment shouldBe mapOf(DISPATCHED_ENV to "1")
        verify(exactly = 0) { launcher.launch(any(), any()) }
    }

    "a command whose recorded helper is this one runs here" {
        val repository = registerIde()
        val own = recordHelper(repository, "own")
        val launcher = launchingLauncher()
        val dispatcher = HelperDispatcher(ownInstallDir = Path.of(own.installDir), alreadyDispatched = false) { _, _ ->
            error("no helper must be started")
        }

        runHelper(*openArgs(projectsDir.toString()), launcher = launcher, dispatcher = dispatcher) shouldBe EXIT_OK

        verify { launcher.launch(any(), any()) }
    }

    "a command for an IDE without a recorded helper runs here" {
        registerIde()
        val launcher = launchingLauncher()

        runHelper(*openArgs(projectsDir.toString()), launcher = launcher) shouldBe EXIT_OK

        verify { launcher.launch(any(), any()) }
    }

    "a recorded helper whose files are gone is skipped" {
        val repository = registerIde()
        recordHelper(repository, "removed", usable = false)
        val launcher = launchingLauncher()

        runHelper(*openArgs(projectsDir.toString()), launcher = launcher) shouldBe EXIT_OK

        verify { launcher.launch(any(), any()) }
    }

    "a dispatched helper never dispatches again" {
        val repository = registerIde()
        recordHelper(repository, "loop")
        val launcher = launchingLauncher()
        val dispatcher = HelperDispatcher(ownInstallDir = null, alreadyDispatched = true) { _, _ ->
            error("no helper must be started")
        }

        runHelper(*openArgs(projectsDir.toString()), launcher = launcher, dispatcher = dispatcher) shouldBe EXIT_OK

        verify { launcher.launch(any(), any()) }
    }
})
