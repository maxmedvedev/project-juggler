package com.ideajuggler.core

import com.ideajuggler.config.ProjectPath
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class ProjectIdGeneratorTest : StringSpec({
    "should generate consistent IDs for the same path" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectPath = ProjectPath(tempDir.toString())
            val id1 = ProjectIdGenerator.generate(projectPath)
            val id2 = ProjectIdGenerator.generate(projectPath)

            id1 shouldBe id2
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate 16-character IDs" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectPath = ProjectPath(tempDir.toString())
            val id = ProjectIdGenerator.generate(projectPath)
            id.id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate different IDs for different paths" {
        val tempDir1 = createTempDirectory("test-project-1")
        val tempDir2 = createTempDirectory("test-project-2")

        try {
            val projectPath1 = ProjectPath(tempDir1.toString())
            val projectPath2 = ProjectPath(tempDir2.toString())
            val id1 = ProjectIdGenerator.generate(projectPath1)
            val id2 = ProjectIdGenerator.generate(projectPath2)

            id1 shouldNotBe id2
        } finally {
            tempDir1.toFile().deleteRecursively()
            tempDir2.toFile().deleteRecursively()
        }
    }

    "should handle absolute paths" {
        val tempDir = createTempDirectory("test-project")
        try {
            val absolutePath = tempDir.toAbsolutePath()
            val projectPath = ProjectPath(absolutePath.toString())
            val id = ProjectIdGenerator.generate(projectPath)

            id.id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate same ID for relative and absolute paths to same directory" {
        val tempDir = createTempDirectory("test-project")
        try {
            // Create a subdirectory
            val subDir = tempDir.resolve("subdir")
            Files.createDirectory(subDir)

            val absoluteProjectPath = ProjectPath(subDir.toAbsolutePath().toString())
            val absoluteId = ProjectIdGenerator.generate(absoluteProjectPath)

            // Change to parent directory and use relative path
            val relativeProjectPath = ProjectPath(tempDir.resolve("subdir").toString())
            val relativeId = ProjectIdGenerator.generate(relativeProjectPath)

            absoluteId shouldBe relativeId
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle paths with spaces" {
        val tempDir = createTempDirectory("test project with spaces")
        try {
            val projectPath = ProjectPath(tempDir.toString())
            val id = ProjectIdGenerator.generate(projectPath)
            id.id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle paths with special characters" {
        val tempDir = createTempDirectory("test-project_@#$")
        try {
            val projectPath = ProjectPath(tempDir.toString())
            val id = ProjectIdGenerator.generate(projectPath)
            id.id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate ID for non-existent path" {
        val nonExistentPath = Path.of("/tmp/non-existent-path-12345")
        val projectPath = ProjectPath(nonExistentPath.toString())
        val id = ProjectIdGenerator.generate(projectPath)

        id.id shouldHaveLength 16
    }
})
