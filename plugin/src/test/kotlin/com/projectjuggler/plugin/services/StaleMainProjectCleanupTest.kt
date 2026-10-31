package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.paths.shouldBeADirectory
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class StaleMainProjectCleanupTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val mainConfigDir = createTempDir("ide-config")
    val mainDir = createTempDir("main-project")

    /** A fresh repository whose main project still has a tracked isolated entry. */
    fun repositoryWithStaleEntry(): Pair<IdeConfigRepository, ProjectMetadata> {
        val repository = testRepository(createTempDirectory(baseDir, "ide"))
        val metadata = registerProject(repository, mainDir)
        MainProjectService.setMainProject(repository, metadata.path)
        return repository to metadata
    }

    fun IdeConfigRepository.projRoot(metadata: ProjectMetadata) = this.baseDir.resolve("projects").resolve(metadata.id.id)

    "an isolated instance leaves the entry alone" {
        val (repository, metadata) = repositoryWithStaleEntry()
        val isolatedConfigDir = repository.projRoot(metadata).resolve("config")

        cleanStaleMainProjectEntryIfMainInstance(repository, isolatedConfigDir).shouldBeNull()

        repository.projRoot(metadata).shouldBeADirectory()
        repository.loadProjectMetadata(metadata.path) shouldBe metadata
    }

    "the main instance removes the stale entry and returns it" {
        val (repository, metadata) = repositoryWithStaleEntry()

        cleanStaleMainProjectEntryIfMainInstance(repository, mainConfigDir) shouldBe metadata

        repository.projRoot(metadata).shouldNotExist()
    }
})
