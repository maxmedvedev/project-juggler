package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.exists

class DirectoryManagerTest : StringSpec({
    extensions(KoinTestExtension())

    val tempDir = createTempDir("test-base")

    "should create all project directories" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t1"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        Files.exists(dirs.root) shouldBe true
        Files.exists(dirs.config) shouldBe true
        Files.exists(dirs.system) shouldBe true
        Files.exists(dirs.logs) shouldBe true
        Files.exists(dirs.plugins) shouldBe true
    }

    "should create directories under correct base path" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val baseDir = tempDir.resolve("t2")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path2")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        dirs.root.toString() shouldBe baseDir.resolve("projects").resolve(project.id.id).toString()
    }

    "should create nested directory structure correctly" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t3"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path3")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        dirs.config.toString() shouldBe dirs.root.resolve("config").toString()
        dirs.system.toString() shouldBe dirs.root.resolve("system").toString()
        dirs.logs.toString() shouldBe dirs.root.resolve("logs").toString()
        dirs.plugins.toString() shouldBe dirs.root.resolve("plugins").toString()
    }

    "should not fail if directories already exist" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t4"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path4")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        // Create first time
        val dirs1 = manager.ensureProjectDirectories(project)

        // Create second time (should not fail)
        val dirs2 = manager.ensureProjectDirectories(project)

        dirs1.root shouldBe dirs2.root
        Files.exists(dirs2.config) shouldBe true
        Files.exists(dirs2.system) shouldBe true
        Files.exists(dirs2.logs) shouldBe true
        Files.exists(dirs2.plugins) shouldBe true
    }

    "should clean project directories completely" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t5"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path5")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        // Create directories
        val dirs = manager.ensureProjectDirectories(project)

        // Create some files
        Files.writeString(dirs.config.resolve("test.txt"), "test content")
        Files.writeString(dirs.system.resolve("test.txt"), "test content")

        // Verify directories exist
        Files.exists(dirs.root) shouldBe true

        // Clean
        manager.cleanProject(project)

        // Verify directories are gone
        Files.exists(dirs.root) shouldBe false
        Files.exists(dirs.config) shouldBe false
        Files.exists(dirs.system) shouldBe false
    }

    "should not fail when cleaning non-existent project" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t6"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/non/existent/project")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        // Try to clean non-existent project (should not throw)
        manager.cleanProject(project)
    }

    "should get correct project root path" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val baseDir = tempDir.resolve("t7")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)

        val projectPath = ProjectPath("/test/project/path7")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-01-01T00:00:00Z"
        )

        val root = manager.getProjectRoot(project)

        root.toString() shouldBe baseDir.resolve("projects").resolve(project.id.id).toString()
    }

    "should handle project IDs with special characters" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t8"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)

        val projectPath = ProjectPath("/test/project/@#$/path")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        Files.exists(dirs.root) shouldBe true
        Files.exists(dirs.config) shouldBe true
    }

    "should create parent directories if they don't exist" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val newTempDir = tempDir.resolve("t9-nonexistent")
        val ideConfigRepository = IdeConfigRepository(newTempDir, testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)

        val projectPath = ProjectPath("/test/project/path9")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        Files.exists(newTempDir) shouldBe true
        Files.exists(newTempDir.resolve("projects")) shouldBe true
        Files.exists(dirs.root) shouldBe true
    }

    "should handle plugin copying on directory creation" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t10"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path10")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        // Verify plugins directory exists (even if no plugins copied)
        Files.exists(dirs.plugins) shouldBe true

        // Second call should not fail
        val dirs2 = manager.ensureProjectDirectories(project)
        Files.exists(dirs2.plugins) shouldBe true
    }
})
