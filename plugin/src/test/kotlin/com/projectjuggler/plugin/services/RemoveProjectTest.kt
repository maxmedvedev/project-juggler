package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.paths.shouldBeADirectory
import io.kotest.matchers.paths.shouldNotExist
import kotlin.io.path.createTempDirectory

class RemoveProjectTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectDir = createTempDir("project")
    val otherDir = createTempDir("other-project")

    // A fresh repository per test: metadata written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    "removes the project from the recents and deletes its isolated directories" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val other = registerProject(repository, otherDir)
        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(metadata)
        projectRoot.shouldBeADirectory()

        removeRecentProject(metadata.path, repository)

        val recent = RecentProjectsIndex.getInstance(repository).getRecent(10)
        recent shouldNotContain metadata
        recent.map { it.path } shouldContainExactly listOf(other.path)
        projectRoot.shouldNotExist()
    }

    "an unknown project is ignored" {
        val repository = newRepository()
        val other = registerProject(repository, otherDir)

        shouldNotThrowAny { removeRecentProject(ProjectPath(projectDir.toString()), repository) }

        RecentProjectsIndex.getInstance(repository).getRecent(10).map { it.path } shouldContainExactly listOf(other.path)
    }
})
