package com.projectjuggler.config

import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.io.path.writeText

class RecentProjectsIndexTest : StringSpec({
    extensions(KoinTestExtension())

    val installation = IdeInstallation("/test/ide", "Test IDE")

    fun newRepository() = IdeConfigRepository(createTempDir("test-recent-repo"), installation)

    fun IdeConfigRepository.openProject(name: String): ProjectPath {
        val path = ProjectPath(createTempDir("test-recent-$name").toString())
        ProjectManager.getInstance(this).registerOrUpdate(path)
        RecentProjectsIndex.getInstance(this).recordOpen(path)
        return path
    }

    fun IdeConfigRepository.recentPaths(limit: Int = 10) =
        RecentProjectsIndex.getInstance(this).getRecent(limit).map { it.path }

    "recordOpen trims to maxRecentProjects, newest first" {
        val repository = newRepository()
        repository.update { it.copy(maxRecentProjects = 3) }

        val paths = (1..5).map { repository.openProject("p$it") }

        repository.recentPaths() shouldContainExactly paths.takeLast(3).reversed()
    }

    "recordOpen moves an existing entry to the head" {
        val repository = newRepository()
        val first = repository.openProject("first")
        val second = repository.openProject("second")

        RecentProjectsIndex.getInstance(repository).recordOpen(first)

        repository.recentPaths() shouldContainExactly listOf(first, second)
    }

    "getRecent skips an entry whose metadata is corrupt" {
        val repository = newRepository()
        val first = repository.openProject("first")
        val second = repository.openProject("second")
        repository.baseDir.resolve("projects").resolve(first.id.id).resolve("metadata.json").writeText("garbage")

        repository.recentPaths() shouldContainExactly listOf(second)
    }

    "corrupt recent.json reads as empty and recordOpen recovers" {
        val repository = newRepository()
        repository.baseDir.resolve("recent.json").writeText("garbage")

        repository.recentPaths().shouldBeEmpty()

        val path = repository.openProject("after")
        repository.recentPaths() shouldContainExactly listOf(path)
    }

    "remove drops the entry" {
        val repository = newRepository()
        val first = repository.openProject("first")
        val second = repository.openProject("second")

        RecentProjectsIndex.getInstance(repository).remove(first.id)

        repository.recentPaths() shouldContainExactly listOf(second)
    }
})
