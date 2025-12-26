package com.ideajuggler.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class ConfigRepositoryTest : StringSpec({

    "should return default config when no config file exists" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)
            val config = repository.load()

            config shouldBe GlobalConfig.default()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should save and load global config" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            val config = GlobalConfig(
                intellijPath = "/path/to/intellij",
                baseVmOptionsPath = "/path/to/base.vmoptions",
                baseVmOptionsHash = "hash123",
                maxRecentProjects = 20
            )

            repository.save(config)
            val loaded = repository.load()

            loaded shouldBe config
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should update config using transform function" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            // Save initial config
            repository.save(GlobalConfig(intellijPath = "/old/path"))

            // Update using transform
            repository.update { it.copy(intellijPath = "/new/path") }

            // Verify update
            val loaded = repository.load()
            loaded.intellijPath shouldBe "/new/path"
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should save and load project metadata" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            val projectPath = ProjectPath("/path/to/project")
            val metadata = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 5
            )

            repository.saveProjectMetadata(metadata)
            val loaded = repository.loadProjectMetadata(projectPath)

            loaded shouldBe metadata
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should return null when loading non-existent project metadata" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)
            val loaded = repository.loadProjectMetadata(ProjectPath("/non-existent"))

            loaded shouldBe null
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should load all projects" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            val projectPath1 = ProjectPath("/path/to/project1")
            val metadata1 = ProjectMetadata(
                path = projectPath1,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 1
            )

            val projectPath2 = ProjectPath("/path/to/project2")
            val metadata2 = ProjectMetadata(
                path = projectPath2,
                lastOpened = "2025-12-25T11:00:00Z",
                openCount = 2
            )

            repository.saveProjectMetadata(metadata1)
            repository.saveProjectMetadata(metadata2)

            val projects = repository.loadAllProjects()

            projects shouldHaveSize 2
            projects shouldContain metadata1
            projects shouldContain metadata2
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should return empty list when no projects exist" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)
            val projects = repository.loadAllProjects()

            projects shouldHaveSize 0
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should delete project metadata" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            val projectPath = ProjectPath("/path/to/project")
            val metadata = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 5
            )

            repository.saveProjectMetadata(metadata)

            // Verify it exists
            repository.loadProjectMetadata(projectPath.id) shouldNotBe null

            // Delete it
            repository.deleteProjectMetadata(projectPath)

            // Verify it's gone
            repository.loadProjectMetadata(projectPath.id) shouldBe null
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should not fail when deleting non-existent metadata" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            val projectPath = ProjectPath("/non/existent")
            // Should not throw
            repository.deleteProjectMetadata(projectPath)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should create config directory if it doesn't exist" {
        val tempDir = createTempDirectory("test-config")
        try {
            // Delete the directory
            tempDir.toFile().deleteRecursively()

            val repository = ConfigRepository(tempDir)
            val config = GlobalConfig(intellijPath = "/test/path")

            repository.save(config)

            // Verify directory was created
            Files.exists(tempDir) shouldBe true
        } finally {
            if (Files.exists(tempDir)) {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    "should handle corrupted project metadata gracefully" {
        val tempDir = createTempDirectory("test-config")
        try {
            val repository = ConfigRepository(tempDir)

            // Create valid project
            val projectPath = ProjectPath("/path/to/valid")
            val validMetadata = ProjectMetadata(
                path = projectPath,
                lastOpened = "2025-12-25T10:00:00Z",
                openCount = 1
            )
            repository.saveProjectMetadata(validMetadata)

            // Create corrupted project metadata by writing invalid JSON
            val corruptedProjectDir = tempDir.resolve("projects").resolve("corrupted-project")
            Files.createDirectories(corruptedProjectDir)
            Files.writeString(corruptedProjectDir.resolve("metadata.json"), "{invalid json}")

            // Should skip corrupted and return only valid projects
            val projects = repository.loadAllProjects()

            projects shouldHaveSize 1
            projects shouldContain validMetadata
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
