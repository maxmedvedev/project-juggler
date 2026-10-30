package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import com.projectjuggler.test.createTempFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.io.path.createTempDirectory

class RecentProjectsImportTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectA = createTempDir("project-a")
    val projectB = createTempDir("project-b")
    val file = createTempFile("not-a-project", ".txt")
    val missing = baseDir.resolve("missing")

    "importableProjects keeps existing directories in order and drops files and missing paths" {
        val paths = listOf(projectB, file, projectA, missing).map { it.toString() }

        importableProjects(paths) shouldContainExactly listOf(
            ProjectPath(projectB.toString()),
            ProjectPath(projectA.toString()),
        )
    }

    "importProjects tracks each project with metadata and a recent entry" {
        val repository = testRepository(createTempDirectory(baseDir, "ide"))
        val projects = listOf(ProjectPath(projectA.toString()), ProjectPath(projectB.toString()))

        importProjects(repository, projects)

        projects.forEach { ProjectManager.getInstance(repository).get(it).shouldNotBeNull() }
        RecentProjectsIndex.getInstance(repository).getRecent(10).map { it.path } shouldContainExactlyInAnyOrder projects
    }
})
