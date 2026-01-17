package com.projectjuggler.process

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.*
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import com.projectjuggler.test.createTempFile
import com.projectjuggler.util.ProjectLockUtils
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ProjectLauncherSyncTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("test-sync")
    val projectDir = createTempDir("test-project")
    val projectDir2 = createTempDir("test-project-2")
    val baseVmOptions = createTempFile("base", ".vmoptions")
    val baseConfig = createTempDir("base-config")
    val basePlugins = createTempDir("base-plugins")

    lateinit var mockProcessLauncher: ProcessLauncher
    lateinit var ideConfigRepository: IdeConfigRepository
    lateinit var projectManager: ProjectManager
    lateinit var directoryManager: DirectoryManager
    lateinit var projectLauncher: ProjectLauncher

    beforeEach {
        clearAllMocks()

        // Setup base VM options content
        baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

        // Create base config content
        baseConfig.resolve("options").createDirectories()
        baseConfig.resolve("options/editor.xml").writeText("<config>editor settings</config>")

        // Create base plugins content
        basePlugins.resolve("test-plugin").createDirectories()
        basePlugins.resolve("test-plugin/plugin.xml").writeText("<plugin>test</plugin>")

        // Create mock ProcessLauncher
        mockProcessLauncher = mockk<ProcessLauncher>(relaxed = true)
        loadKoinModules(module {
            single<ProcessLauncher> { mockProcessLauncher }
        })

        // Setup IdeConfigRepository
        val testInstallation = IdeInstallation("/test/ide/bin/idea", "Test IDE")
        ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = baseVmOptions.toString(),
                baseConfigPath = baseConfig.toString(),
                basePluginsPath = basePlugins.toString()
            )
        )
        BaseVMOptionsTracker.getInstance(ideConfigRepository).updateHash()

        projectManager = ProjectManager.getInstance(ideConfigRepository)
        directoryManager = DirectoryManager.getInstance(ideConfigRepository)
        projectLauncher = ProjectLauncher.getInstance(ideConfigRepository)

        // Mock ProjectLockUtils and ShutdownWaiter
        mockkObject(ProjectLockUtils)
        mockkObject(ShutdownWaiter)

        // Default: project is not running
        every { ProjectLockUtils.isProjectOpen(any(), any()) } returns false
    }

    afterEach {
        unmockkAll()
    }

    // ============================================
    // Basic Sync Operations (no running projects)
    // ============================================

    "should sync VM options only" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Modify base VM options
        baseVmOptions.writeText("-Xms512m\n-Xmx4096m\n-XX:NewOption=true")

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = false,
            syncPlugins = false
        )

        // Verify VM options file was updated
        val vmOptionsFile = directoryManager.getProjectRoot(project).resolve("idea.vmoptions")
        vmOptionsFile.exists() shouldBe true
        val content = vmOptionsFile.readText()
        content shouldContain "-Xms512m"
        content shouldContain "-Xmx4096m"
        content shouldContain "-XX:NewOption=true"

        // Verify no restart (project wasn't running)
        verify(exactly = 0) { mockProcessLauncher.launch(any(), any(), any()) }
    }

    "should sync config only" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)

        // Add new config file to base
        baseConfig.resolve("options/newfile.xml").writeText("<new>content</new>")

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = false,
            syncConfig = true,
            syncPlugins = false
        )

        // Verify config was synced
        val projectConfig = directoryManager.getProjectRoot(project).resolve("config/options/newfile.xml")
        projectConfig.exists() shouldBe true
        projectConfig.readText() shouldContain "<new>content</new>"

        // Verify no restart
        verify(exactly = 0) { mockProcessLauncher.launch(any(), any(), any()) }
    }

    "should sync plugins only" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)

        // Add new plugin to base
        basePlugins.resolve("new-plugin").createDirectories()
        basePlugins.resolve("new-plugin/plugin.xml").writeText("<plugin>new</plugin>")

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = false,
            syncConfig = false,
            syncPlugins = true
        )

        // Verify plugin was synced
        val projectPlugin = directoryManager.getProjectRoot(project).resolve("plugins/new-plugin/plugin.xml")
        projectPlugin.exists() shouldBe true
        projectPlugin.readText() shouldContain "<plugin>new</plugin>"

        // Verify no restart
        verify(exactly = 0) { mockProcessLauncher.launch(any(), any(), any()) }
    }

    "should sync all settings" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Modify all base content
        baseVmOptions.writeText("-Xms1024m")
        baseConfig.resolve("options/all.xml").writeText("<all>synced</all>")
        basePlugins.resolve("all-plugin").createDirectories()
        basePlugins.resolve("all-plugin/plugin.xml").writeText("<plugin>all</plugin>")

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = true,
            syncPlugins = true
        )

        val projectRoot = directoryManager.getProjectRoot(project)

        // Verify all were synced
        projectRoot.resolve("idea.vmoptions").readText() shouldContain "-Xms1024m"
        projectRoot.resolve("config/options/all.xml").readText() shouldContain "<all>synced</all>"
        projectRoot.resolve("plugins/all-plugin/plugin.xml").readText() shouldContain "<plugin>all</plugin>"
    }

    // ============================================
    // Sync with Running Projects (stop/restart)
    // ============================================

    "should stop running project, sync, and restart" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Project is running
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } returns true andThen false
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), any())
        } returns ShutdownResult.Success

        val progressEvents = mutableListOf<SyncProgress>()
        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = true,
            onProgress = { progressEvents.add(it) }
        )

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = false,
            syncPlugins = false,
            options = options
        )

        // Verify shutdown was requested
        verify { ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), any()) }

        // Verify restart was called
        verify(exactly = 1) { mockProcessLauncher.launch(any(), any(), any()) }

        // Verify progress events
        progressEvents.any { it is SyncProgress.Syncing } shouldBe true
        progressEvents.any { it is SyncProgress.Restarting } shouldBe true
    }

    "should stop multiple running projects, sync all, and restart all" {
        val projectPath1 = ProjectPath(projectDir.toString())
        val projectPath2 = ProjectPath(projectDir2.toString())

        val project1 = projectManager.registerOrUpdate(projectPath1)
        val project2 = projectManager.registerOrUpdate(projectPath2)
        directoryManager.ensureProjectDirectories(project1)
        directoryManager.ensureProjectDirectories(project2)
        projectManager.ensureDebugPort(project1)
        projectManager.ensureDebugPort(project2)

        // Both projects are running
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath1) } returns true andThen false
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath2) } returns true andThen false
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, any(), any(), any())
        } returns ShutdownResult.Success

        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = true
        )

        // Sync both projects
        projectLauncher.syncProject(project1, syncVmOptions = true, syncConfig = false, syncPlugins = false, options)
        projectLauncher.syncProject(project2, syncVmOptions = true, syncConfig = false, syncPlugins = false, options)

        // Verify both were stopped and restarted
        verify(exactly = 2) { ShutdownWaiter.waitForShutdown(ideConfigRepository, any(), any(), any()) }
        verify(exactly = 2) { mockProcessLauncher.launch(any(), any(), any()) }
    }

    // ============================================
    // Sync Options Behavior
    // ============================================

    "should not stop running project when stopIfRunning is false" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Project is running
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } returns true

        val options = SyncOptions(
            stopIfRunning = false,  // Don't stop
            autoRestart = false
        )

        // Should sync without stopping
        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = false,
            syncPlugins = false,
            options = options
        )

        // Verify shutdown was NOT requested
        verify(exactly = 0) { ShutdownWaiter.waitForShutdown(any(), any(), any(), any()) }

        // Verify no restart
        verify(exactly = 0) { mockProcessLauncher.launch(any(), any(), any()) }
    }

    "should not restart when autoRestart is false" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Project is running
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } returns true andThen false
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), any())
        } returns ShutdownResult.Success

        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = false  // Don't restart
        )

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = false,
            syncPlugins = false,
            options = options
        )

        // Verify shutdown was requested
        verify { ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), any()) }

        // Verify NO restart
        verify(exactly = 0) { mockProcessLauncher.launch(any(), any(), any()) }
    }

    "should report progress events" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Project is running
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } returns true andThen false

        // Capture progress callback to simulate progress
        val progressCallbackSlot = slot<(Int) -> Unit>()
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), capture(progressCallbackSlot))
        } answers {
            // Simulate some progress
            progressCallbackSlot.captured(1)
            progressCallbackSlot.captured(2)
            ShutdownResult.Success
        }

        val progressEvents = mutableListOf<SyncProgress>()
        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = true,
            onProgress = { progressEvents.add(it) }
        )

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = false,
            syncPlugins = false,
            options = options
        )

        // Verify Stopping progress events were reported
        val stoppingEvents = progressEvents.filterIsInstance<SyncProgress.Stopping>()
        stoppingEvents.size shouldBe 2
        stoppingEvents[0].secondsElapsed shouldBe 1
        stoppingEvents[1].secondsElapsed shouldBe 2

        // Verify other events
        progressEvents.any { it is SyncProgress.Syncing } shouldBe true
        progressEvents.any { it is SyncProgress.Restarting } shouldBe true
    }

    // ============================================
    // Error Scenarios
    // ============================================

    "should prevent concurrent sync operations" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Simulate slow sync by holding the lock
        val exception = shouldThrow<SyncException> {
            // First, acquire lock manually
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)
            val lock = signalManager.acquireSyncLock(project)
            lock shouldBe lock  // lock is not null at this point

            lock.use {
                // Try to sync while lock is held
                projectLauncher.syncProject(
                    project = project,
                    syncVmOptions = true,
                    syncConfig = false,
                    syncPlugins = false
                )
            }
        }

        exception.message shouldContain "Sync already in progress"
    }

    "should throw on shutdown timeout" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Project is running and won't shutdown
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } returns true
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), any())
        } returns ShutdownResult.Timeout

        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = true,
            shutdownTimeout = 5
        )

        val exception = shouldThrow<SyncException> {
            projectLauncher.syncProject(
                project = project,
                syncVmOptions = true,
                syncConfig = false,
                syncPlugins = false,
                options = options
            )
        }

        exception.message shouldContain "did not shut down"
        exception.message shouldContain "5 seconds"
    }

    "should verify correct project path is passed to launch after restart" {
        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Project is running
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } returns true andThen false
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), any())
        } returns ShutdownResult.Success

        val argsSlot = slot<List<String>>()
        every { mockProcessLauncher.launch(any(), capture(argsSlot), any()) } returns Unit

        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = true
        )

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = false,
            syncPlugins = false,
            options = options
        )

        // Verify the correct project path was passed to launch
        argsSlot.captured shouldContainExactly listOf(projectDir.toString())
    }

    // ============================================
    // Self-Shutdown Sync (sync-helper scenario)
    // ============================================

    "should handle self-shutdown sync scenario (sync-helper flow)" {
        // This test simulates what happens when sync-helper is invoked:
        // 1. Plugin spawns sync-helper and exits
        // 2. sync-helper calls syncProject() while IDE is shutting down
        // 3. syncProject waits for the IDE to finish shutting down
        // 4. syncs the project
        // 5. restarts the project

        val projectPath = ProjectPath(projectDir.toString())
        val project = projectManager.registerOrUpdate(projectPath)
        directoryManager.ensureProjectDirectories(project)
        projectManager.ensureDebugPort(project)

        // Simulate: project is running, then shuts down after a brief wait
        var shutdownCheckCount = 0
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath) } answers {
            shutdownCheckCount++
            // First check: project is still running
            // Subsequent checks: project has shut down
            shutdownCheckCount == 1
        }

        // Track wait calls to verify shutdown waiting behavior
        var waitCalled = false
        val progressCallbackSlot = slot<(Int) -> Unit>()
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, projectPath, any(), capture(progressCallbackSlot))
        } answers {
            waitCalled = true
            // Simulate waiting a couple seconds before shutdown completes
            progressCallbackSlot.captured(1)
            progressCallbackSlot.captured(2)
            ShutdownResult.Success
        }

        val progressEvents = mutableListOf<SyncProgress>()
        val launchedProjects = mutableListOf<String>()
        every { mockProcessLauncher.launch(any(), any(), any()) } answers {
            launchedProjects.add(secondArg<List<String>>().first())
        }

        // This is exactly what sync-helper does
        val syncOptions = SyncOptions(
            stopIfRunning = true,
            autoRestart = true,
            shutdownTimeout = 60,
            onProgress = { progressEvents.add(it) }
        )

        projectLauncher.syncProject(
            project = project,
            syncVmOptions = true,
            syncConfig = true,
            syncPlugins = true,
            options = syncOptions
        )

        // Verify the full sync-helper flow:
        // 1. Waited for shutdown
        waitCalled shouldBe true

        // 2. Progress events show the full lifecycle
        progressEvents.filterIsInstance<SyncProgress.Stopping>().size shouldBe 2
        progressEvents.any { it is SyncProgress.Syncing } shouldBe true
        progressEvents.any { it is SyncProgress.Restarting } shouldBe true

        // 3. Project was restarted
        launchedProjects shouldContainExactly listOf(projectDir.toString())

        // 4. All settings were synced (verify files exist)
        val projectRoot = directoryManager.getProjectRoot(project)
        projectRoot.resolve("idea.vmoptions").exists() shouldBe true
        projectRoot.resolve("config/options/editor.xml").exists() shouldBe true
        projectRoot.resolve("plugins/test-plugin/plugin.xml").exists() shouldBe true
    }

    "should handle sync-helper syncing all projects" {
        // Simulates: sync-helper --ide /path --all-projects --all
        // This syncs ALL tracked projects, stopping and restarting each one

        val projectPath1 = ProjectPath(projectDir.toString())
        val projectPath2 = ProjectPath(projectDir2.toString())

        val project1 = projectManager.registerOrUpdate(projectPath1)
        val project2 = projectManager.registerOrUpdate(projectPath2)
        directoryManager.ensureProjectDirectories(project1)
        directoryManager.ensureProjectDirectories(project2)
        projectManager.ensureDebugPort(project1)
        projectManager.ensureDebugPort(project2)

        // Both projects are running (typical when syncing from one of them)
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath1) } returns true andThen false
        every { ProjectLockUtils.isProjectOpen(ideConfigRepository, projectPath2) } returns true andThen false
        every {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, any(), any(), any())
        } returns ShutdownResult.Success

        val launchedProjects = mutableListOf<String>()
        every { mockProcessLauncher.launch(any(), any(), any()) } answers {
            launchedProjects.add(secondArg<List<String>>().first())
        }

        // Sync options as sync-helper uses
        val syncOptions = SyncOptions(
            stopIfRunning = true,
            autoRestart = true,
            shutdownTimeout = 60
        )

        // Simulate sync-helper looping through all projects (as it does in main())
        val allProjects = listOf(project1, project2)
        for (project in allProjects) {
            projectLauncher.syncProject(
                project = project,
                syncVmOptions = true,
                syncConfig = true,
                syncPlugins = true,
                options = syncOptions
            )
        }

        // Verify both projects were stopped (shutdown waited)
        verify(exactly = 2) {
            ShutdownWaiter.waitForShutdown(ideConfigRepository, any(), any(), any())
        }

        // Verify both projects were restarted
        launchedProjects.size shouldBe 2
        launchedProjects shouldContainExactly listOf(projectDir.toString(), projectDir2.toString())
    }
})
