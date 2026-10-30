package com.projectjuggler.util

import com.projectjuggler.core.ConfigCopyExclusions
import com.projectjuggler.platform.Platform
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
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

    "copy should preserve excluded files and directories in the destination" {
        val source = tempDir.resolve("preserve-source")
        val destination = tempDir.resolve("preserve-destination")
        source.resolve("options").createDirectories()
        source.resolve("options/other.xml").writeText("other")
        source.resolve("new.txt").writeText("new")
        destination.resolve("options").createDirectories()
        destination.resolve("plugins").createDirectories()
        destination.resolve(".lock").writeText("1234")
        destination.resolve("options/recentProjects.xml").writeText("recent")
        destination.resolve("plugins/a.jar").writeText("jar")
        destination.resolve("stale.xml").writeText("stale")

        DirectoryCopier.copy(
            source,
            destination,
            ConfigCopyExclusions.directories,
            ConfigCopyExclusions.files,
            ConfigCopyExclusions.patterns,
        )

        destination.resolve(".lock").readText() shouldBe "1234"
        destination.resolve("options/recentProjects.xml").exists() shouldBe true
        destination.resolve("plugins/a.jar").exists() shouldBe true
        destination.resolve("stale.xml").exists() shouldBe false
        destination.resolve("options/other.xml").exists() shouldBe true
        destination.resolve("new.txt").exists() shouldBe true
    }

    "copy should not copy excluded source entries" {
        val source = tempDir.resolve("excluded-source")
        val destination = tempDir.resolve("excluded-destination")
        source.resolve("plugins").createDirectories()
        source.resolve(".lock").writeText("1234")
        source.resolve("plugins/b.jar").writeText("jar")
        source.resolve("keep.txt").writeText("keep")
        Files.createDirectories(destination)

        DirectoryCopier.copy(
            source,
            destination,
            ConfigCopyExclusions.directories,
            ConfigCopyExclusions.files,
            ConfigCopyExclusions.patterns,
        )

        destination.resolve(".lock").exists() shouldBe false
        destination.resolve("plugins").exists() shouldBe false
        destination.resolve("keep.txt").exists() shouldBe true
    }

    "copy without exclusions should clear everything" {
        val source = tempDir.resolve("no-exclusions-source")
        val destination = tempDir.resolve("no-exclusions-destination")
        Files.createDirectories(source)
        source.resolve("a.jar").writeText("jar")
        Files.createDirectories(destination)
        destination.resolve(".lock").writeText("1234")

        DirectoryCopier.copy(source, destination)

        destination.resolve(".lock").exists() shouldBe false
        destination.resolve("a.jar").exists() shouldBe true
    }

    "copyIfFirstOpen should roll back a partial copy so a retry copies again".config(
        enabledIf = { !Platform.isWindows() && System.getProperty("user.name") != "root" }
    ) {
        val source = tempDir.resolve("rollback-source")
        val destination = tempDir.resolve("rollback-destination")
        val sub = source.resolve("sub")
        sub.createDirectories()
        source.resolve("a.txt").writeText("a")
        sub.resolve("b.txt").writeText("b")
        Files.createDirectories(destination)

        // An unreadable file fails the copy after destination/sub was already created,
        // so a partial copy is left behind regardless of directory iteration order
        val unreadable = sub.resolve("b.txt")
        val permissions = Files.getPosixFilePermissions(unreadable)
        Files.setPosixFilePermissions(unreadable, emptySet())
        val result = try {
            DirectoryCopier.copyIfFirstOpen(source, destination)
        } finally {
            Files.setPosixFilePermissions(unreadable, permissions)
        }

        result shouldBe false
        Files.list(destination).use { it.count() } shouldBe 0L

        DirectoryCopier.copyIfFirstOpen(source, destination) shouldBe true
        destination.resolve("a.txt").readText() shouldBe "a"
        destination.resolve("sub/b.txt").readText() shouldBe "b"
    }
})
