package com.projectjuggler.util

import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class DirectoryCopierTest : StringSpec({
    val tempDir = createTempDir("directory-test")

    "should copy directory on first open when source exists" {
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
    }

    "should not copy if destination is not empty" {
        val source = tempDir.resolve("source2")
        val destination = tempDir.resolve("destination2")

        Files.createDirectories(source)
        Files.createDirectories(destination)

        // Create file in destination
        destination.resolve("existing.txt").writeText("existing")

        // Create source file
        source.resolve("file.txt").writeText("data")

        val result = DirectoryCopier.copyIfFirstOpen(source, destination)

        result shouldBe false
        destination.resolve("file.txt").exists() shouldBe false
    }

    "should silently skip if source does not exist" {
        val source = tempDir.resolve("non-existent")
        val destination = tempDir.resolve("destination3")
        Files.createDirectories(destination)

        val result = DirectoryCopier.copyIfFirstOpen(source, destination)

        result shouldBe false
    }

    "should copy nested directory structures" {
        val source = tempDir.resolve("source4")
        val destination = tempDir.resolve("destination4")

        Files.createDirectories(source)
        Files.createDirectories(destination)

        // Create nested structure
        val nestedDir = source.resolve("subdir/lib/resources")
        Files.createDirectories(nestedDir)
        nestedDir.resolve("data.json").writeText("{}")

        DirectoryCopier.copyIfFirstOpen(source, destination)

        destination.resolve("subdir/lib/resources/data.json").exists() shouldBe true
    }

    "copy should delete existing files not in source" {
        val source = tempDir.resolve("source5")
        val destination = tempDir.resolve("destination5")

        Files.createDirectories(source)
        Files.createDirectories(destination)

        // Create existing files in destination that don't exist in source
        val existingDir = destination.resolve("old-dir")
        Files.createDirectories(existingDir)
        existingDir.resolve("old-file.txt").writeText("old content")
        destination.resolve("another-old-file.txt").writeText("another old")

        // Create source file
        source.resolve("new-file.txt").writeText("new content")

        DirectoryCopier.copy(source, destination)

        // New file should exist
        destination.resolve("new-file.txt").exists() shouldBe true
        // Old files should be deleted
        destination.resolve("old-dir").exists() shouldBe false
        destination.resolve("another-old-file.txt").exists() shouldBe false
    }
})
