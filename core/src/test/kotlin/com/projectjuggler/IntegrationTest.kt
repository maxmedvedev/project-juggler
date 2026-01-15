package com.projectjuggler

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class IntegrationTest : StringSpec({

    "should complete full workflow: configure, open, list, clean" {
        val baseDir = createTempDirectory("test-project-juggler")
        val projectDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            // Initialize all components
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
            val projectManager = ProjectManager.getInstance(ideConfigRepository)
            val directoryManager = DirectoryManager.getInstance(ideConfigRepository)
            val baseVMOptionsTracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)
            val recentProjectsIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

            // Step 1: Configure base VM options
            ideConfigRepository.save(
                IdeConfig(
                    installation = testInstallation,
                    baseVmOptionsPath = baseVmOptions.toString()
                )
            )
            baseVMOptionsTracker.updateHash()

            val config = ideConfigRepository.load()
            config.baseVmOptionsPath shouldBe baseVmOptions.toString()
            config.baseVmOptionsHash shouldNotBe null

            // Step 2: Open a project (simulate)
            val projectPath = ProjectPath(projectDir.toString())
            var project = projectManager.registerOrUpdate(projectPath)

            val projectDirs = directoryManager.ensureProjectDirectories(project)
            // Allocate debug port
            val debugPort = projectManager.ensureDebugPort(project)
            // Reload project after debug port allocation updates metadata
            project = projectManager.get(projectPath)!!
            VMOptionsGenerator.generate(
                baseVMOptionsTracker.getBaseVmOptionsPath(),
                ProjectDirectories(
                    root = projectDirs.root,
                    config = projectDirs.config,
                    system = projectDirs.system,
                    logs = projectDirs.logs,
                    plugins = projectDirs.plugins
                ),
                debugPort
            )

            recentProjectsIndex.recordOpen(projectPath)

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

            val listedProject = projects[0]
            listedProject.id shouldBe project.id
            listedProject.path shouldBe projectPath

            // Step 4: Verify recent projects
            val recentProjects = recentProjectsIndex.getRecent(10)
            recentProjects shouldHaveSize 1
            recentProjects shouldContain project

            // Step 5: Clean project
            directoryManager.cleanProject(project)
            projectManager.remove(projectPath)
            recentProjectsIndex.remove(project.id)

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
        val baseDir = createTempDirectory("test-project-juggler")
        val projectDir1 = createTempDirectory("test-project-1")
        val projectDir2 = createTempDirectory("test-project-2")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
            val projectManager = ProjectManager.getInstance(ideConfigRepository)
            val directoryManager = DirectoryManager.getInstance(ideConfigRepository)
            val baseVMOptionsTracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

            // Configure and setup two projects
            ideConfigRepository.save(IdeConfig(installation = testInstallation, baseVmOptionsPath = baseVmOptions.toString()))
            baseVMOptionsTracker.updateHash()

            val projectPath1 = ProjectPath(projectDir1.toString())
            val projectPath2 = ProjectPath(projectDir2.toString())

            val project1 = projectManager.registerOrUpdate(projectPath1)
            val project2 = projectManager.registerOrUpdate(projectPath2)

            val dirs1 = directoryManager.ensureProjectDirectories(project1)
            val dirs2 = directoryManager.ensureProjectDirectories(project2)

            // Allocate debug ports
            val debugPort1 = projectManager.ensureDebugPort(project1)
            val debugPort2 = projectManager.ensureDebugPort(project2)

            VMOptionsGenerator.generate(
                baseVmOptions, ProjectDirectories(
                    dirs1.root, dirs1.config, dirs1.system, dirs1.logs, dirs1.plugins
                ),
                debugPort1
            )
            VMOptionsGenerator.generate(
                baseVmOptions, ProjectDirectories(
                    dirs2.root, dirs2.config, dirs2.system, dirs2.logs, dirs2.plugins
                ),
                debugPort2
            )

            // Verify no changes detected initially
            baseVMOptionsTracker.hasChanged() shouldBe false

            // Modify base VM options file
            baseVmOptions.writeText("-Xms512m\n-Xmx4096m\n-XX:NewFlag=true")

            // Should detect change
            baseVMOptionsTracker.hasChanged() shouldBe true

            // Regenerate all projects
            val projects = projectManager.listAll()
            projects.forEach { project ->
                val projectDirs = directoryManager.ensureProjectDirectories(project)
                // Allocate debug port
                val debugPort = projectManager.ensureDebugPort(project)
                VMOptionsGenerator.generate(
                    baseVmOptions,
                    ProjectDirectories(
                        projectDirs.root,
                        projectDirs.config,
                        projectDirs.system,
                        projectDirs.logs,
                        projectDirs.plugins
                    ),
                    debugPort,
                    forceRegenerate = true
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
        val baseDir = createTempDirectory("test-project-juggler")
        val projectDir1 = createTempDirectory("project-alpha")
        val projectDir2 = createTempDirectory("project-beta")
        val projectDir3 = createTempDirectory("project-gamma")

        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
            val projectManager = ProjectManager.getInstance(ideConfigRepository)
            val directoryManager = DirectoryManager.getInstance(ideConfigRepository)

            // Create three projects
            val projectPath1 = ProjectPath(projectDir1.toString())
            val projectPath2 = ProjectPath(projectDir2.toString())
            val projectPath3 = ProjectPath(projectDir3.toString())

            val project1 = projectManager.registerOrUpdate(projectPath1)
            val project2 = projectManager.registerOrUpdate(projectPath2)
            val project3 = projectManager.registerOrUpdate(projectPath3)

            directoryManager.ensureProjectDirectories(project1)
            directoryManager.ensureProjectDirectories(project2)
            directoryManager.ensureProjectDirectories(project3)

            // Verify all projects exist
            projectManager.listAll() shouldHaveSize 3

            // Clean one project
            directoryManager.cleanProject(project2)
            projectManager.remove(projectPath2)

            // Verify only that project was removed
            val remainingProjects = projectManager.listAll()
            remainingProjects shouldHaveSize 2

            val remainingIds = remainingProjects.map { it.id }
            remainingIds shouldContain project1.id
            remainingIds shouldContain project3.id

            // Verify directories
            directoryManager.getProjectRoot(project1).exists() shouldBe true
            directoryManager.getProjectRoot(project2).exists() shouldBe false
            directoryManager.getProjectRoot(project3).exists() shouldBe true

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir1.toFile().deleteRecursively()
            projectDir2.toFile().deleteRecursively()
            projectDir3.toFile().deleteRecursively()
        }
    }
})
