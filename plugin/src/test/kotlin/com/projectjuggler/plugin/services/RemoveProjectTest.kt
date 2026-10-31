package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.markIsolatedInstanceOpen
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.paths.shouldBeADirectory
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class RemoveProjectTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectDir = createTempDir("project")
    val otherDir = createTempDir("other-project")

    // A fresh repository per test: metadata written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    // Directories made read-only by a test, restored so the temp dirs can be cleaned up
    val readOnlyDirs = mutableListOf<Path>()
    afterTest {
        readOnlyDirs.forEach { it.toFile().setWritable(true) }
        readOnlyDirs.clear()
    }

    "removes the project from the recents and deletes its isolated directories" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val other = registerProject(repository, otherDir)
        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(metadata)
        projectRoot.shouldBeADirectory()
        val notifications = RecordingNotificationHandler()

        removeRecentProject(metadata.path, repository, notifications) shouldBe true

        val recent = RecentProjectsIndex.getInstance(repository).getRecent(10)
        recent shouldNotContain metadata
        recent.map { it.path } shouldContainExactly listOf(other.path)
        projectRoot.shouldNotExist()
        (notifications.infos + notifications.errors).shouldBeEmpty()
    }

    "an unknown project is ignored" {
        val repository = newRepository()
        val other = registerProject(repository, otherDir)
        val notifications = RecordingNotificationHandler()

        removeRecentProject(ProjectPath(projectDir.toString()), repository, notifications) shouldBe false

        RecentProjectsIndex.getInstance(repository).getRecent(10).map { it.path } shouldContainExactly listOf(other.path)
        (notifications.infos + notifications.errors).shouldBeEmpty()
    }

    "a project whose isolated IDE runs is not removed" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        markIsolatedInstanceOpen(repository, metadata.path)
        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(metadata)
        val notifications = RecordingNotificationHandler()

        removeRecentProject(metadata.path, repository, notifications) shouldBe false

        projectRoot.shouldBeADirectory()
        projectRoot.resolve("metadata.json").shouldExist()
        RecentProjectsIndex.getInstance(repository).getRecent(10).map { it.path } shouldContainExactly
            listOf(metadata.path)
        notifications.errors shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.error.remove.project.running", metadata.path.name)
        )
        notifications.infos.shouldBeEmpty()
    }

    "a failed deletion is reported".config(enabledIf = { !System.getProperty("os.name").startsWith("Windows") }) {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val systemDir = DirectoryManager.getInstance(repository).getProjectRoot(metadata).resolve("system")
            .createDirectories()
        systemDir.resolve("held.txt").writeText("x")
        systemDir.toFile().setWritable(false) shouldBe true
        readOnlyDirs.add(systemDir)
        val notifications = RecordingNotificationHandler()

        removeRecentProject(metadata.path, repository, notifications) shouldBe false

        notifications.errors shouldHaveSize 1
        notifications.errors.single() shouldStartWith
            ProjectJugglerBundle.message("notification.error.remove.project.failed", metadata.path.name, "")
    }
})
