package com.projectjuggler.plugin.services

import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.createTempDirectory

class SyncPlannerTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectA = createTempDir("project-a")
    val projectB = createTempDir("project-b")
    val mainDir = createTempDir("main-project")

    val ideExecutable = "/test/ide/bin/idea"

    // A fresh repository per test: metadata written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    "forProject hands the current project to the sync-helper" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectA)

        val target = SyncPlanner.forProject(repository, metadata.path, SyncType.VmOptions, currentProjectId = metadata.id)

        target shouldBe SyncTarget.SelfShutdown(
            listOf("--ide", ideExecutable, "--path", metadata.path.pathString, "--vmoptions")
        )
    }

    "forProject syncs another registered project in process" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectA)

        val target = SyncPlanner.forProject(repository, metadata.path, SyncType.Config, currentProjectId = ProjectId("other"))

        target shouldBe SyncTarget.InProcess(listOf(metadata))
    }

    "forProject has no target for an unregistered project" {
        val repository = newRepository()

        val target = SyncPlanner.forProject(repository, ProjectPath(projectA.toString()), SyncType.All, currentProjectId = null)

        target shouldBe SyncTarget.NoTarget
    }

    "forAllProjects targets the isolated projects and excludes the main project" {
        val repository = newRepository()
        val a = registerProject(repository, projectA)
        val b = registerProject(repository, projectB)
        val main = registerProject(repository, mainDir)
        MainProjectService.setMainProject(repository, main.path)

        val target = SyncPlanner.forAllProjects(repository, SyncType.Plugins, currentProjectId = null)

        target.shouldBeInstanceOf<SyncTarget.InProcess>().projects shouldContainExactlyInAnyOrder listOf(a, b)
    }

    "forAllProjects hands the current project to the sync-helper and keeps the others in process" {
        val repository = newRepository()
        val a = registerProject(repository, projectA)
        val b = registerProject(repository, projectB)
        val main = registerProject(repository, mainDir)
        MainProjectService.setMainProject(repository, main.path)

        val target = SyncPlanner.forAllProjects(repository, SyncType.All, currentProjectId = a.id)

        target shouldBe SyncTarget.SelfShutdown(
            helperArgs = listOf("--ide", ideExecutable, "--path", a.path.pathString, "--all"),
            others = listOf(b)
        )
    }

    "forAllProjects with only the current project tracked has no others" {
        val repository = newRepository()
        val a = registerProject(repository, projectA)

        val target = SyncPlanner.forAllProjects(repository, SyncType.Plugins, currentProjectId = a.id)

        target shouldBe SyncTarget.SelfShutdown(
            listOf("--ide", ideExecutable, "--path", a.path.pathString, "--plugins")
        )
    }

    "forAllProjects with only the main project tracked syncs an empty list (gap 16)" {
        // plugin-spec gap 16: sync-all with no syncable target still reports success for 0 projects
        val repository = newRepository()
        val main = registerProject(repository, mainDir)
        MainProjectService.setMainProject(repository, main.path)

        val target = SyncPlanner.forAllProjects(repository, SyncType.Config, currentProjectId = null)

        target shouldBe SyncTarget.InProcess(emptyList())
    }
})
