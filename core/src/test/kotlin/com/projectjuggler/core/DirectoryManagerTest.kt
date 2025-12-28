package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

class DirectoryManagerTest : StringSpec({

    "should create all project directories" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
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
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should create directories under correct base path" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectPath = ProjectPath("/test/project/path")
            val project = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 1
            )

            val dirs = manager.ensureProjectDirectories(project)

            dirs.root.toString() shouldBe tempDir.resolve("projects").resolve(project.id.id).toString()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should create nested directory structure correctly" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectPath = ProjectPath("/test/project/path")
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
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should not fail if directories already exist" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectPath = ProjectPath("/test/project/path")
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
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should clean project directories completely" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectPath = ProjectPath("/test/project/path")
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
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should not fail when cleaning non-existent project" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectPath = ProjectPath("/non/existent/project")
            val project = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 1
            )

            // Try to clean non-existent project (should not throw)
            manager.cleanProject(project)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should get correct project root path" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)

            val projectPath = ProjectPath("/test/project/path")
            val project = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-01-01T00:00:00Z"
            )

            val root = manager.getProjectRoot(project)

            root.toString() shouldBe tempDir.resolve("projects").resolve(project.id.id).toString()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle project IDs with special characters" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)

            val projectPath = ProjectPath("/test/project/@#$/path")
            val project = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 1
            )

            val dirs = manager.ensureProjectDirectories(project)

            Files.exists(dirs.root) shouldBe true
            Files.exists(dirs.config) shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should create parent directories if they don't exist" {
        val tempDir = createTempDirectory("test-base")
        try {
            // Delete the temp directory to test creation from scratch
            tempDir.toFile().deleteRecursively()

            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)

            val projectPath = ProjectPath("/test/project/path")
            val project = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 1
            )

            val dirs = manager.ensureProjectDirectories(project)

            Files.exists(tempDir) shouldBe true
            Files.exists(tempDir.resolve("projects")) shouldBe true
            Files.exists(dirs.root) shouldBe true
        } finally {
            if (tempDir.exists()) {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    "should handle plugin copying on directory creation" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectPath = ProjectPath("/test/project/path")
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
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
