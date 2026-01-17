package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ImportRecentProjectsTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("test-import")
    val projectDir1 = createTempDir("test-project-1")
    val projectDir2 = createTempDir("test-project-2")
    val projectDir3 = createTempDir("test-project-3")

    "should import multiple projects and create metadata for each" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("import-multi"), testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val recentIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

        // Simulate importing 3 projects (like ImportRecentProjectsAction does)
        val projectPaths = listOf(
            ProjectPath(projectDir1.toString()),
            ProjectPath(projectDir2.toString()),
            ProjectPath(projectDir3.toString())
        )

        projectPaths.forEach { projectPath ->
            projectManager.registerOrUpdate(projectPath)
            recentIndex.recordOpen(projectPath)
        }

        // Verify all projects are in recent list
        val recentProjects = recentIndex.getRecent(10)
        recentProjects shouldHaveSize 3

        // Verify metadata was created for each project
        projectPaths.forEach { projectPath ->
            val metadata = ideConfigRepository.loadProjectMetadata(projectPath)
            metadata shouldNotBe null
            metadata!!.path shouldBe projectPath
            metadata.openCount shouldBe 1
        }
    }

    "should not duplicate projects when importing the same project twice" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("import-dup"), testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val recentIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

        val projectPath = ProjectPath(projectDir1.toString())

        // Import same project twice
        repeat(2) {
            projectManager.registerOrUpdate(projectPath)
            recentIndex.recordOpen(projectPath)
        }

        // Should have only 1 project in recent list
        val recentProjects = recentIndex.getRecent(10)
        recentProjects shouldHaveSize 1

        // Open count should be 2 (metadata tracks opens)
        val metadata = ideConfigRepository.loadProjectMetadata(projectPath)
        metadata shouldNotBe null
        metadata!!.openCount shouldBe 2
    }

    "should preserve existing projects when importing new ones" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("import-preserve"), testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val recentIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

        val existingProjectPath = ProjectPath(projectDir1.toString())
        val newProjectPath = ProjectPath(projectDir2.toString())

        // Existing project was opened before
        projectManager.registerOrUpdate(existingProjectPath)
        recentIndex.recordOpen(existingProjectPath)

        // Now import a new project
        projectManager.registerOrUpdate(newProjectPath)
        recentIndex.recordOpen(newProjectPath)

        // Both projects should be in recent list
        val recentProjects = recentIndex.getRecent(10)
        recentProjects shouldHaveSize 2
        recentProjects.map { it.path } shouldContainExactlyInAnyOrder listOf(existingProjectPath, newProjectPath)
    }

    "should handle importing projects with same name but different paths" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("import-samename"), testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val recentIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

        // Create two directories with the same leaf name but different paths
        val parentA = createTempDir("parent-a")
        val parentB = createTempDir("parent-b")
        val projectA = parentA.resolve("my-project").also { it.toFile().mkdirs() }
        val projectB = parentB.resolve("my-project").also { it.toFile().mkdirs() }

        val projectPathA = ProjectPath(projectA.toString())
        val projectPathB = ProjectPath(projectB.toString())

        // Import both projects
        projectManager.registerOrUpdate(projectPathA)
        recentIndex.recordOpen(projectPathA)
        projectManager.registerOrUpdate(projectPathB)
        recentIndex.recordOpen(projectPathB)

        // Both projects should be in recent list (different IDs due to different paths)
        val recentProjects = recentIndex.getRecent(10)
        recentProjects shouldHaveSize 2

        // IDs should be different
        projectPathA.id shouldNotBe projectPathB.id
    }
})
