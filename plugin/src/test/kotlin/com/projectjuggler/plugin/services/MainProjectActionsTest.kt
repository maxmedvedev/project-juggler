package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.markIsolatedInstanceOpen
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.paths.shouldBeAFile
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class MainProjectActionsTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectDir = createTempDir("project")

    // A fresh repository per test: config and metadata written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    fun metadataFile(repository: IdeConfigRepository, projectPath: ProjectPath) =
        repository.baseDir.resolve("projects").resolve(projectPath.id.id).resolve("metadata.json")

    "setMainProject persists the main project and reports it" {
        val repository = newRepository()
        val projectPath = ProjectPath(projectDir.toString())
        val handler = RecordingNotificationHandler()

        setMainProject(projectPath, handler, repository)

        repository.load().mainProjectPath shouldBe projectPath.pathString
        handler.infos shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.success.set.main", projectPath.name)
        )
    }

    "setMainProject drops the stale isolated entry of the new main project" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        metadataFile(repository, metadata.path).shouldBeAFile()

        setMainProject(metadata.path, RecordingNotificationHandler(), repository)

        metadataFile(repository, metadata.path).shouldNotExist()
    }

    "setMainProject keeps the isolated entry while its instance is running" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        markIsolatedInstanceOpen(repository, metadata.path)

        setMainProject(metadata.path, RecordingNotificationHandler(), repository)

        metadataFile(repository, metadata.path).shouldBeAFile()
    }

    "clearMainProject unsets the main project and reports it" {
        val repository = newRepository()
        MainProjectService.setMainProject(repository, ProjectPath(projectDir.toString()))
        val handler = RecordingNotificationHandler()

        clearMainProject(handler, repository)

        repository.load().mainProjectPath.shouldBeNull()
        handler.infos shouldContainExactly listOf(ProjectJugglerBundle.message("notification.success.unset.main"))
    }
})
