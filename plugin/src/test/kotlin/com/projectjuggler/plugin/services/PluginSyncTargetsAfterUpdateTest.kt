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
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.FileAlreadyExistsException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

class PluginSyncTargetsAfterUpdateTest : StringSpec({
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

    fun RecordingNotificationHandler.shouldBeSilent() {
        infos.shouldBeEmpty()
        errors.shouldBeEmpty()
    }

    "does nothing in an isolated instance" {
        val repository = updatedRepository()
        repository.registerTwoIsolatedAndMain()
        val handler = RecordingNotificationHandler()
        val isolatedConfigDir = repository.baseDir.resolve("projects/x/config")

        val targets = pluginSyncTargetsAfterUpdate(repository, isolatedConfigDir, "NEW-2", handler)

        targets.shouldBeEmpty()
        repository.load().lastKnownBuildNumber shouldBe "OLD-1"
        handler.shouldBeSilent()
    }

    "the first run stores the build without targets" {
        val repository = newRepository()
        repository.update { it.copy(syncPluginsOnIdeUpdate = true) }
        repository.registerTwoIsolatedAndMain()
        val handler = RecordingNotificationHandler()

        val targets = pluginSyncTargetsAfterUpdate(repository, mainConfigDir, "NEW-2", handler)

        targets.shouldBeEmpty()
        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        handler.shouldBeSilent()
    }

    "an unchanged build has no targets" {
        val repository = updatedRepository()
        repository.registerTwoIsolatedAndMain()
        val handler = RecordingNotificationHandler()

        val targets = pluginSyncTargetsAfterUpdate(repository, mainConfigDir, "OLD-1", handler)

        targets.shouldBeEmpty()
        repository.load().lastKnownBuildNumber shouldBe "OLD-1"
        handler.shouldBeSilent()
    }

    "a new build with the setting off stores the build without targets" {
        val repository = updatedRepository(enabled = false)
        repository.registerTwoIsolatedAndMain()
        val handler = RecordingNotificationHandler()

        val targets = pluginSyncTargetsAfterUpdate(repository, mainConfigDir, "NEW-2", handler)

        targets.shouldBeEmpty()
        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        handler.shouldBeSilent()
    }

    "a new build without isolated projects has no targets" {
        val repository = updatedRepository()
        val handler = RecordingNotificationHandler()

        val targets = pluginSyncTargetsAfterUpdate(repository, mainConfigDir, "NEW-2", handler)

        targets.shouldBeEmpty()
        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        handler.shouldBeSilent()
    }

    "a new build returns the isolated projects only and syncs nothing itself" {
        val repository = updatedRepository()
        val isolated = repository.registerTwoIsolatedAndMain()
        val handler = RecordingNotificationHandler()

        val targets = pluginSyncTargetsAfterUpdate(repository, mainConfigDir, "NEW-2", handler)

        targets.map { it.path } shouldContainExactlyInAnyOrder isolated.map { it.path }
        repository.load().lastKnownBuildNumber shouldBe "NEW-2"
        handler.shouldBeSilent()
    }

    "any failure is reported and yields no targets" {
        val repository = testRepository(createTempFile(baseDir, "not-a-dir"))
        val handler = RecordingNotificationHandler()
        var targets: List<ProjectMetadata>? = null

        val logged = expectLoggedError {
            targets = pluginSyncTargetsAfterUpdate(repository, mainConfigDir, "NEW-2", handler)
        }

        logged.shouldBeInstanceOf<FileAlreadyExistsException>()
        targets.shouldBe(emptyList())
        handler.infos.shouldBeEmpty()
        handler.errors.single() shouldStartWith ProjectJugglerBundle.message("notification.sync.on.update.failed", "")
    }

    "a failure without a message is reported as an unknown error" {
        mockkObject(MainProjectService)
        try {
            every { MainProjectService.isRunningInMainInstance(any(), any()) } throws IllegalStateException()
            val handler = RecordingNotificationHandler()

            val logged = expectLoggedError {
                pluginSyncTargetsAfterUpdate(updatedRepository(), mainConfigDir, "NEW-2", handler)
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
