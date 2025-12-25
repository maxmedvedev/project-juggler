package com.ideajuggler.core

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
            val id1 = ProjectIdGenerator.generate(tempDir)
            val id2 = ProjectIdGenerator.generate(tempDir)

            id1 shouldBe id2
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate 16-character IDs" {
        val tempDir = createTempDirectory("test-project")
        try {
            val id = ProjectIdGenerator.generate(tempDir)
            id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate different IDs for different paths" {
        val tempDir1 = createTempDirectory("test-project-1")
        val tempDir2 = createTempDirectory("test-project-2")

        try {
            val id1 = ProjectIdGenerator.generate(tempDir1)
            val id2 = ProjectIdGenerator.generate(tempDir2)

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
            val id = ProjectIdGenerator.generate(absolutePath)

            id shouldHaveLength 16
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

            val absoluteId = ProjectIdGenerator.generate(subDir.toAbsolutePath())

            // Change to parent directory and use relative path
            val relativeId = ProjectIdGenerator.generate(tempDir.resolve("subdir"))

            absoluteId shouldBe relativeId
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle paths with spaces" {
        val tempDir = createTempDirectory("test project with spaces")
        try {
            val id = ProjectIdGenerator.generate(tempDir)
            id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle paths with special characters" {
        val tempDir = createTempDirectory("test-project_@#$")
        try {
            val id = ProjectIdGenerator.generate(tempDir)
            id shouldHaveLength 16
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should generate ID for non-existent path" {
        val nonExistentPath = Path.of("/tmp/non-existent-path-12345")
        val id = ProjectIdGenerator.generate(nonExistentPath)

        id shouldHaveLength 16
    }
})
