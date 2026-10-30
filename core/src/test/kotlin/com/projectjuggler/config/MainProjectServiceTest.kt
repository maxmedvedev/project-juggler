package com.projectjuggler.config

import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.platform.Platform
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class MainProjectServiceTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("test-main-project")
    val projectDir = createTempDir("test-project")
    val projectDirA = createTempDir("test-project-a")
    val projectDirB = createTempDir("test-project-b")

    "should set a project as main project" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectPath = ProjectPath(projectDir.toString())

        // Set as main project
        MainProjectService.setMainProject(ideConfigRepository, projectPath)

        // Verify via service method
        MainProjectService.isMainProject(ideConfigRepository, projectPath) shouldBe true

        // Verify via repository load
        ideConfigRepository.load().mainProjectPath shouldBe projectPath.pathString
    }

    "should unset main project" {
        val testInstallation = IdeInstallation("/test/ide2", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("unset"), testInstallation)
        val projectPath = ProjectPath(projectDir.toString())

        // First set as main project
        MainProjectService.setMainProject(ideConfigRepository, projectPath)
        MainProjectService.isMainProject(ideConfigRepository, projectPath) shouldBe true

        // Now clear main project
        MainProjectService.clearMainProject(ideConfigRepository)

        // Verify it's no longer main
        MainProjectService.isMainProject(ideConfigRepository, projectPath) shouldBe false

        // Verify repository has null mainProjectPath
        ideConfigRepository.load().mainProjectPath shouldBe null
    }

    "should replace main project when setting a different project" {
        val testInstallation = IdeInstallation("/test/ide3", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("replace"), testInstallation)
        val projectPathA = ProjectPath(projectDirA.toString())
        val projectPathB = ProjectPath(projectDirB.toString())

        // Set project A as main
        MainProjectService.setMainProject(ideConfigRepository, projectPathA)
        MainProjectService.isMainProject(ideConfigRepository, projectPathA) shouldBe true
        MainProjectService.isMainProject(ideConfigRepository, projectPathB) shouldBe false

        // Set project B as main (replaces A)
        MainProjectService.setMainProject(ideConfigRepository, projectPathB)

        // Verify A is no longer main, B is now main
        MainProjectService.isMainProject(ideConfigRepository, projectPathA) shouldBe false
        MainProjectService.isMainProject(ideConfigRepository, projectPathB) shouldBe true

        // Verify repository has B's path
        ideConfigRepository.load().mainProjectPath shouldBe projectPathB.pathString
    }

    "should return false for isMainProject when no main project is set" {
        val testInstallation = IdeInstallation("/test/ide4", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("empty"), testInstallation)
        val projectPath = ProjectPath(projectDir.toString())

        // Fresh repository - no main project set
        MainProjectService.isMainProject(ideConfigRepository, projectPath) shouldBe false

        // Verify repository has null mainProjectPath
        ideConfigRepository.load().mainProjectPath shouldBe null
    }

    "should persist main project configuration to config.json file" {
        val testInstallation = IdeInstallation("/test/ide5", "Test IDE")
        val persistDir = baseDir.resolve("persist")
        val ideConfigRepository = IdeConfigRepository(persistDir, testInstallation)
        val projectPath = ProjectPath(projectDir.toString())

        // Set as main project
        MainProjectService.setMainProject(ideConfigRepository, projectPath)

        // Verify config.json file exists
        val configFile = persistDir.resolve("config.json")
        configFile.exists() shouldBe true

        // Verify file contents contain the mainProjectPath
        val configContent = configFile.readText()
        configContent shouldContain "\"mainProjectPath\""
        configContent shouldContain projectPath.pathString
    }

    "should not duplicate project in recent projects after marking as main" {
        val testInstallation = IdeInstallation("/test/ide6", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir.resolve("recent"), testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val recentProjectsIndex = RecentProjectsIndex.getInstance(ideConfigRepository)
        val projectPath = ProjectPath(projectDir.toString())

        // Register the project and record it as opened
        projectManager.registerOrUpdate(projectPath)
        recentProjectsIndex.recordOpen(projectPath)

        // Verify project is in recent projects (exactly once)
        recentProjectsIndex.getRecent(10) shouldHaveSize 1

        // Mark the project as main
        MainProjectService.setMainProject(ideConfigRepository, projectPath)

        // Verify project is still in recent projects exactly once (no duplication)
        recentProjectsIndex.getRecent(10) shouldHaveSize 1

        // Open the project again (simulating re-opening after marking as main)
        recentProjectsIndex.recordOpen(projectPath)

        // Verify still no duplication
        recentProjectsIndex.getRecent(10) shouldHaveSize 1
    }

    "isRunningInMainInstance is false for an isolated config dir" {
        val repo = IdeConfigRepository(baseDir.resolve("isolated"), IdeInstallation("/test/ide7", "Test IDE"))
        val isolatedConfig = repo.baseDir.resolve("projects/x-abc/config").createDirectories()

        MainProjectService.isRunningInMainInstance(repo, isolatedConfig) shouldBe false

        repo.update { it.copy(baseConfigPath = isolatedConfig.toString()) }
        MainProjectService.isRunningInMainInstance(repo, isolatedConfig) shouldBe false
    }

    "isRunningInMainInstance is true when base is unset and dir is not isolated" {
        val repo = IdeConfigRepository(baseDir.resolve("unset-base"), IdeInstallation("/test/ide8", "Test IDE"))
        val configDir = createTempDir("test-base-config")

        MainProjectService.isRunningInMainInstance(repo, configDir) shouldBe true
    }

    "isRunningInMainInstance normalizes paths" {
        val repo = IdeConfigRepository(baseDir.resolve("normalize"), IdeInstallation("/test/ide9", "Test IDE"))
        val configDir = createTempDir("test-base-config")
        repo.update { it.copy(baseConfigPath = configDir.toString()) }

        MainProjectService.isRunningInMainInstance(repo, configDir.resolve(".")) shouldBe true
        MainProjectService.isRunningInMainInstance(repo, Paths.get(configDir.toString() + "/")) shouldBe true
        MainProjectService.isRunningInMainInstance(repo, configDir.resolve("sub/..")) shouldBe true
    }

    "isRunningInMainInstance follows symlinks" {
        if (!Platform.isWindows()) {
            val repo = IdeConfigRepository(baseDir.resolve("symlink"), IdeInstallation("/test/ide10", "Test IDE"))
            val configDir = createTempDir("test-base-config")
            val link = createTempDir("test-link-parent").resolve("config-link")
            Files.createSymbolicLink(link, configDir)
            repo.update { it.copy(baseConfigPath = configDir.toString()) }

            MainProjectService.isRunningInMainInstance(repo, link) shouldBe true
        }
    }

    "isRunningInMainInstance is false for a different dir" {
        val repo = IdeConfigRepository(baseDir.resolve("different"), IdeInstallation("/test/ide11", "Test IDE"))
        val configDir = createTempDir("test-base-config")
        val otherDir = createTempDir("test-other-config")
        repo.update { it.copy(baseConfigPath = configDir.toString()) }

        MainProjectService.isRunningInMainInstance(repo, otherDir) shouldBe false
        MainProjectService.isRunningInMainInstance(repo, configDir.resolve("missing")) shouldBe false
    }

    "shouldPromptForMainProject is false in an isolated instance even with no main project" {
        val repo = IdeConfigRepository(baseDir.resolve("prompt"), IdeInstallation("/test/ide12", "Test IDE"))
        val isolatedConfig = repo.baseDir.resolve("projects/x-abc/config")
        val mainConfig = createTempDir("test-base-config")

        MainProjectService.shouldPromptForMainProject(repo, isolatedConfig) shouldBe false
        MainProjectService.shouldPromptForMainProject(repo, mainConfig) shouldBe true
    }
})
