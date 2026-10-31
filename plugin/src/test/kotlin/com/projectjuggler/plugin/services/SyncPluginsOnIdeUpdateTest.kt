package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.expectLoggedError
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.nio.file.FileAlreadyExistsException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

class SyncPluginsOnIdeUpdateTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val mainConfigDir = createTempDir("ide-config")
    val projectA = createTempDir("project-a")
    val projectB = createTempDir("project-b")
    val mainDir = createTempDir("main-project")

    // A fresh repository per test: config and metadata written by one test must not leak into the next
    fun newRepository(): IdeConfigRepository = testRepository(createTempDirectory(baseDir, "ide"))

    /** A repository that last saw build `OLD-1`, with sync-on-update [enabled]. */
    fun updatedRepository(enabled: Boolean = true) = newRepository().apply {
        update { it.copy(lastKnownBuildNumber = "OLD-1", syncPluginsOnIdeUpdate = enabled) }
    }

    /** Two isolated projects plus a tracked main project. */
    fun IdeConfigRepository.registerTwoIsolatedAndMain(): List<ProjectMetadata> {
        val isolated = listOf(registerProject(this, projectA), registerProject(this, projectB))
        registerProject(this, mainDir)
        MainProjectService.setMainProject(this, ProjectPath(mainDir.toString()))
        return isolated
    }

    fun ProjectLauncher.verifyPluginSync(metadata: ProjectMetadata) = verify(exactly = 1) {
        syncProject(
            metadata,
            syncVmOptions = false,
            syncConfig = false,
            syncPlugins = true,
            options = match { it.stopIfRunning && it.autoRestart && it.shutdownTimeout == 60 },
        )
    }

    "does nothing in an isolated instance" {
        val repository = updatedRepository()
        repository.registerTwoIsolatedAndMain()
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()
        val isolatedConfigDir = repository.baseDir.resolve("projects/x/config")

        syncPluginsOnIdeUpdate(repository, isolatedConfigDir, "NEW-2", handler, launcher)

        repository.load().lastKnownBuildNumber shouldBe "OLD-1"
        verify { launcher wasNot Called }
        handler.infos.shouldBeEmpty()
    }

    "the first run stores the build without syncing" {
        val repository = newRepository()
        repository.update { it.copy(syncPluginsOnIdeUpdate = true) }
        repository.registerTwoIsolatedAndMain()
        val launcher = mockk<ProjectLauncher>(relaxed = true)

        syncPluginsOnIdeUpdate(repository, mainConfigDir, "NEW-2", RecordingNotificationHandler(), launcher)

        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        verify { launcher wasNot Called }
    }

    "an unchanged build does not sync" {
        val repository = updatedRepository()
        repository.registerTwoIsolatedAndMain()
        val launcher = mockk<ProjectLauncher>(relaxed = true)

        syncPluginsOnIdeUpdate(repository, mainConfigDir, "OLD-1", RecordingNotificationHandler(), launcher)

        repository.load().lastKnownBuildNumber shouldBe "OLD-1"
        verify { launcher wasNot Called }
    }

    "a new build with the setting off stores the build without syncing" {
        val repository = updatedRepository(enabled = false)
        repository.registerTwoIsolatedAndMain()
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        syncPluginsOnIdeUpdate(repository, mainConfigDir, "NEW-2", handler, launcher)

        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        verify { launcher wasNot Called }
        handler.infos.shouldBeEmpty()
    }

    "a new build syncs plugins into the isolated projects only" {
        val repository = updatedRepository()
        val (a, b) = repository.registerTwoIsolatedAndMain()
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val handler = RecordingNotificationHandler()

        syncPluginsOnIdeUpdate(repository, mainConfigDir, "NEW-2", handler, launcher)

        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        launcher.verifyPluginSync(a)
        launcher.verifyPluginSync(b)
        verify(exactly = 2) { launcher.syncProject(any(), any(), any(), any(), any()) }
        handler.infos shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.sync.on.update.started", "NEW-2", 2),
            ProjectJugglerBundle.message("notification.sync.on.update.complete", 2),
        )
        handler.errors.shouldBeEmpty()
    }

    "a failing project does not stop the others" {
        val repository = updatedRepository()
        val (a, b) = repository.registerTwoIsolatedAndMain()
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        // Whichever project comes first fails
        every { launcher.syncProject(a, any(), any(), any(), any()) } throws IllegalStateException("boom")
        every { launcher.syncProject(b, any(), any(), any(), any()) } throws IllegalStateException("boom")
        val handler = RecordingNotificationHandler()

        syncPluginsOnIdeUpdate(repository, mainConfigDir, "NEW-2", handler, launcher)

        launcher.verifyPluginSync(a)
        launcher.verifyPluginSync(b)
        handler.infos shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.sync.on.update.started", "NEW-2", 2),
            ProjectJugglerBundle.message("notification.sync.on.update.complete", 2),
        )
        handler.errors.shouldBeEmpty()
    }

    "any other failure is reported" {
        val repository = testRepository(createTempFile(baseDir, "not-a-dir"))
        val handler = RecordingNotificationHandler()

        val logged = expectLoggedError {
            syncPluginsOnIdeUpdate(repository, mainConfigDir, "NEW-2", handler, mockk(relaxed = true))
        }

        logged.shouldBeInstanceOf<FileAlreadyExistsException>()
        handler.infos.shouldBeEmpty()
        handler.errors.single() shouldStartWith ProjectJugglerBundle.message("notification.sync.on.update.failed", "")
    }

    "a failure without a message is reported as an unknown error" {
        mockkObject(MainProjectService)
        try {
            every { MainProjectService.isRunningInMainInstance(any(), any()) } throws IllegalStateException()
            val handler = RecordingNotificationHandler()

            val logged = expectLoggedError {
                syncPluginsOnIdeUpdate(updatedRepository(), mainConfigDir, "NEW-2", handler, mockk(relaxed = true))
            }

            logged.shouldBeInstanceOf<IllegalStateException>()
            handler.errors shouldContainExactly listOf(
                ProjectJugglerBundle.message(
                    "notification.sync.on.update.failed",
                    ProjectJugglerBundle.message("error.unknown")
                )
            )
        } finally {
            unmockkObject(MainProjectService)
        }
    }
})
