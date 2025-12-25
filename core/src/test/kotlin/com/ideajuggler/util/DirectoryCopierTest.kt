package com.ideajuggler.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText

class DirectoryCopierTest : StringSpec({

    "should copy directory on first open when source exists" {
        val tempDir = createTempDirectory("directory-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create some files
            val subDir = source.resolve("some-subdir")
            Files.createDirectories(subDir)
            subDir.resolve("file.txt").writeText("<content>data</content>")

            val result = DirectoryCopier.copyIfFirstOpen(source, destination)

            result shouldBe true
            destination.resolve("some-subdir/file.txt").exists() shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should not copy if destination is not empty" {
        val tempDir = createTempDirectory("directory-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create file in destination
            destination.resolve("existing.txt").writeText("existing")

            // Create source file
            source.resolve("file.txt").writeText("data")

            val result = DirectoryCopier.copyIfFirstOpen(source, destination)

            result shouldBe false
            destination.resolve("file.txt").exists() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should silently skip if source does not exist" {
        val tempDir = createTempDirectory("directory-test")
        try {
            val source = tempDir.resolve("non-existent")
            val destination = tempDir.resolve("destination")
            Files.createDirectories(destination)

            val result = DirectoryCopier.copyIfFirstOpen(source, destination)

            result shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should copy nested directory structures" {
        val tempDir = createTempDirectory("directory-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create nested structure
            val nestedDir = source.resolve("subdir/lib/resources")
            Files.createDirectories(nestedDir)
            nestedDir.resolve("data.json").writeText("{}")

            DirectoryCopier.copyIfFirstOpen(source, destination)

            destination.resolve("subdir/lib/resources/data.json").exists() shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should skip .lock files when copying" {
        val tempDir = createTempDirectory("directory-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create regular file and .lock file
            source.resolve("config.xml").writeText("<config>data</config>")
            source.resolve(".lock").writeText("lock content")

            val result = DirectoryCopier.copyIfFirstOpen(source, destination)

            result shouldBe true
            destination.resolve("config.xml").exists() shouldBe true
            destination.resolve(".lock").exists() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
