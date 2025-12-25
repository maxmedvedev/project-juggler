package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
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
            val projectId = "test-project-123"

            val dirs = manager.ensureProjectDirectories(projectId)

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
            val projectId = "test-project-123"

            val dirs = manager.ensureProjectDirectories(projectId)

            dirs.root.toString() shouldBe tempDir.resolve("projects").resolve(projectId).toString()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should create nested directory structure correctly" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)
            val projectId = "test-project-123"

            val dirs = manager.ensureProjectDirectories(projectId)

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
            val projectId = "test-project-123"

            // Create first time
            val dirs1 = manager.ensureProjectDirectories(projectId)

            // Create second time (should not fail)
            val dirs2 = manager.ensureProjectDirectories(projectId)

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
            val projectId = "test-project-123"

            // Create directories
            val dirs = manager.ensureProjectDirectories(projectId)

            // Create some files
            Files.writeString(dirs.config.resolve("test.txt"), "test content")
            Files.writeString(dirs.system.resolve("test.txt"), "test content")

            // Verify directories exist
            Files.exists(dirs.root) shouldBe true

            // Clean
            manager.cleanProject(projectId)

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

            // Try to clean non-existent project (should not throw)
            manager.cleanProject("non-existent-project")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should get correct project root path" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)

            val projectId = "test-project-123"

            val root = manager.getProjectRoot(projectId)

            root.toString() shouldBe tempDir.resolve("projects").resolve(projectId).toString()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle project IDs with special characters" {
        val tempDir = createTempDirectory("test-base")
        try {
            val configRepository = ConfigRepository(tempDir)
            val manager = DirectoryManager.getInstance(configRepository)

            val projectId = "test-project-@#$-123"

            val dirs = manager.ensureProjectDirectories(projectId)

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

            val projectId = "test-project-123"

            val dirs = manager.ensureProjectDirectories(projectId)

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
            val projectId = "test-project-123"

            val dirs = manager.ensureProjectDirectories(projectId)

            // Verify plugins directory exists (even if no plugins copied)
            Files.exists(dirs.plugins) shouldBe true

            // Second call should not fail
            val dirs2 = manager.ensureProjectDirectories(projectId)
            Files.exists(dirs2.plugins) shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
