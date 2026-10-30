package com.projectjuggler.plugin.services

import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.markIsolatedInstanceOpen
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.paths.shouldBeADirectory
import io.kotest.matchers.paths.shouldNotExist
import kotlin.io.path.createTempDirectory

class LaunchPlanTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectDir = createTempDir("project")
    val elsewhere = createTempDir("other-config")

    // A fresh repository per test: metadata and locks written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    "an unregistered project is launched" {
        val repository = newRepository()

        planLaunch(repository, ProjectPath(projectDir.toString()), elsewhere) shouldContainExactly listOf(LaunchStep.LAUNCH)
    }

    "a registered project without a lock is launched" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)

        planLaunch(repository, metadata.path, elsewhere) shouldContainExactly listOf(LaunchStep.LAUNCH)
    }

    "a running project is focused" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        markIsolatedInstanceOpen(repository, metadata.path)

        planLaunch(repository, metadata.path, elsewhere) shouldContainExactly listOf(LaunchStep.FOCUS)
    }

    "reopening the project of this isolated instance opens it here and then focuses its own window (gap 1)" {
        // plugin-spec gap 1: launchOrFocus does not stop after opening in the current IDE
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        markIsolatedInstanceOpen(repository, metadata.path)
        val ownConfigDir = DirectoryManager.getInstance(repository).getProjectRoot(metadata.path).resolve("config")

        planLaunch(repository, metadata.path, ownConfigDir) shouldContainExactly
            listOf(LaunchStep.OPEN_IN_CURRENT_IDE, LaunchStep.FOCUS)
    }

    "reopening the project of this isolated instance without a lock opens it here and then launches" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val ownConfigDir = DirectoryManager.getInstance(repository).getProjectRoot(metadata.path).resolve("config")

        planLaunch(repository, metadata.path, ownConfigDir) shouldContainExactly
            listOf(LaunchStep.OPEN_IN_CURRENT_IDE, LaunchStep.LAUNCH)
    }

    "planning creates the isolated directories of a registered project" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(metadata.path)
        projectRoot.resolve("config").shouldNotExist()

        planLaunch(repository, metadata.path, elsewhere)

        listOf("config", "system", "logs", "plugins").forEach { projectRoot.resolve(it).shouldBeADirectory() }
    }
})
