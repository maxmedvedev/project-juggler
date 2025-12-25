package com.ideajuggler

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.GlobalConfig
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class IntegrationTest : StringSpec({

    "should complete full workflow: configure, open, list, clean" {
        val baseDir = createTempDirectory("test-idea-juggler")
        val projectDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            // Initialize all components
            val configRepository = ConfigRepository(baseDir)
            val projectManager = ProjectManager(configRepository)
            val directoryManager = DirectoryManager(baseDir)
            val baseVMOptionsTracker = BaseVMOptionsTracker(configRepository)
            val recentProjectsIndex = RecentProjectsIndex(baseDir)

            // Step 1: Configure base VM options
            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = baseVmOptions.toString()
                )
            )
            baseVMOptionsTracker.updateHash()

            val config = configRepository.load()
            config.baseVmOptionsPath shouldBe baseVmOptions.toString()
            config.baseVmOptionsHash shouldNotBe null

            // Step 2: Open a project (simulate)
            val projectId = ProjectIdGenerator.generate(projectDir)
            projectManager.registerOrUpdate(projectId, projectDir)

            val projectDirs = directoryManager.ensureProjectDirectories(projectId)
            VMOptionsGenerator.generate(
                baseVMOptionsTracker.getBaseVmOptionsPath(),
                ProjectDirectories(
                    root = projectDirs.root,
                    config = projectDirs.config,
                    system = projectDirs.system,
                    logs = projectDirs.logs,
                    plugins = projectDirs.plugins
                )
            )

            recentProjectsIndex.recordOpen(projectId)

            // Verify project directories created
            projectDirs.config.exists() shouldBe true
            projectDirs.system.exists() shouldBe true
            projectDirs.logs.exists() shouldBe true
            projectDirs.plugins.exists() shouldBe true

            // Verify VM options file created
            val vmOptionsFile = projectDirs.root.resolve("idea.vmoptions")
            vmOptionsFile.exists() shouldBe true

            val vmOptionsContent = vmOptionsFile.toFile().readText()
            vmOptionsContent shouldNotBe ""
            vmOptionsContent.contains("-Xms256m") shouldBe true
            vmOptionsContent.contains("-Didea.config.path=${projectDirs.config}") shouldBe true

            // Step 3: List projects
            val projects = projectManager.listAll()
            projects shouldHaveSize 1

            val project = projects[0]
            project.id shouldBe projectId
            project.path shouldBe projectDir.toString()

            // Step 4: Verify recent projects
            val recentProjects = recentProjectsIndex.getRecent(10)
            recentProjects shouldHaveSize 1
            recentProjects shouldContain project

            // Step 5: Clean project
            directoryManager.cleanProject(projectId)
            projectManager.remove(projectId)
            recentProjectsIndex.remove(projectId)

            // Verify cleanup
            projectDirs.root.exists() shouldBe false
            projectManager.listAll() shouldHaveSize 0
            recentProjectsIndex.getRecent(10) shouldHaveSize 0

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should detect and handle base VM options changes" {
        val baseDir = createTempDirectory("test-idea-juggler")
        val projectDir1 = createTempDirectory("test-project-1")
        val projectDir2 = createTempDirectory("test-project-2")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            val configRepository = ConfigRepository(baseDir)
            val projectManager = ProjectManager(configRepository)
            val directoryManager = DirectoryManager(baseDir)
            val baseVMOptionsTracker = BaseVMOptionsTracker(configRepository)

            // Configure and setup two projects
            configRepository.save(GlobalConfig(baseVmOptionsPath = baseVmOptions.toString()))
            baseVMOptionsTracker.updateHash()

            val projectId1 = ProjectIdGenerator.generate(projectDir1)
            val projectId2 = ProjectIdGenerator.generate(projectDir2)

            val dirs1 = directoryManager.ensureProjectDirectories(projectId1)
            val dirs2 = directoryManager.ensureProjectDirectories(projectId2)

            VMOptionsGenerator.generate(
                baseVmOptions, ProjectDirectories(
                    dirs1.root, dirs1.config, dirs1.system, dirs1.logs, dirs1.plugins
                )
            )
            VMOptionsGenerator.generate(
                baseVmOptions, ProjectDirectories(
                    dirs2.root, dirs2.config, dirs2.system, dirs2.logs, dirs2.plugins
                )
            )

            projectManager.registerOrUpdate(projectId1, projectDir1)
            projectManager.registerOrUpdate(projectId2, projectDir2)

            // Verify no changes detected initially
            baseVMOptionsTracker.hasChanged() shouldBe false

            // Modify base VM options file
            baseVmOptions.writeText("-Xms512m\n-Xmx4096m\n-XX:NewFlag=true")

            // Should detect change
            baseVMOptionsTracker.hasChanged() shouldBe true

            // Regenerate all projects
            val projects = projectManager.listAll()
            projects.forEach { project ->
                val projectDirs = directoryManager.ensureProjectDirectories(project.id)
                VMOptionsGenerator.generate(
                    baseVmOptions,
                    ProjectDirectories(
                        projectDirs.root,
                        projectDirs.config,
                        projectDirs.system,
                        projectDirs.logs,
                        projectDirs.plugins
                    )
                )
            }

            baseVMOptionsTracker.updateHash()

            // Verify both projects have updated VM options
            val vmOptions1 = dirs1.root.resolve("idea.vmoptions").toFile().readText()
            val vmOptions2 = dirs2.root.resolve("idea.vmoptions").toFile().readText()

            vmOptions1.contains("-Xms512m") shouldBe true
            vmOptions1.contains("-XX:NewFlag=true") shouldBe true
            vmOptions2.contains("-Xms512m") shouldBe true
            vmOptions2.contains("-XX:NewFlag=true") shouldBe true

            // Should not detect changes after update
            baseVMOptionsTracker.hasChanged() shouldBe false

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir1.toFile().deleteRecursively()
            projectDir2.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should handle multiple projects independently" {
        val baseDir = createTempDirectory("test-idea-juggler")
        val projectDir1 = createTempDirectory("project-alpha")
        val projectDir2 = createTempDirectory("project-beta")
        val projectDir3 = createTempDirectory("project-gamma")

        try {
            val configRepository = ConfigRepository(baseDir)
            val projectManager = ProjectManager(configRepository)
            val directoryManager = DirectoryManager(baseDir)

            // Create three projects
            val id1 = ProjectIdGenerator.generate(projectDir1)
            val id2 = ProjectIdGenerator.generate(projectDir2)
            val id3 = ProjectIdGenerator.generate(projectDir3)

            projectManager.registerOrUpdate(id1, projectDir1)
            projectManager.registerOrUpdate(id2, projectDir2)
            projectManager.registerOrUpdate(id3, projectDir3)

            directoryManager.ensureProjectDirectories(id1)
            directoryManager.ensureProjectDirectories(id2)
            directoryManager.ensureProjectDirectories(id3)

            // Verify all projects exist
            projectManager.listAll() shouldHaveSize 3

            // Clean one project
            directoryManager.cleanProject(id2)
            projectManager.remove(id2)

            // Verify only that project was removed
            val remainingProjects = projectManager.listAll()
            remainingProjects shouldHaveSize 2

            val remainingIds = remainingProjects.map { it.id }
            remainingIds shouldContain id1
            remainingIds shouldContain id3

            // Verify directories
            directoryManager.getProjectRoot(id1).exists() shouldBe true
            directoryManager.getProjectRoot(id2).exists() shouldBe false
            directoryManager.getProjectRoot(id3).exists() shouldBe true

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir1.toFile().deleteRecursively()
            projectDir2.toFile().deleteRecursively()
            projectDir3.toFile().deleteRecursively()
        }
    }
})
