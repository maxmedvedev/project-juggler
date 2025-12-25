package com.ideajuggler.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText

class PluginCopierTest : StringSpec({

    "should copy plugins on first open when source exists" {
        val tempDir = createTempDirectory("plugin-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create some plugin files
            val pluginDir = source.resolve("some-plugin")
            Files.createDirectories(pluginDir)
            pluginDir.resolve("plugin.xml").writeText("<plugin>content</plugin>")

            val result = DirectoryCopier.copyIfFirstOpen(source, destination)

            result shouldBe true
            destination.resolve("some-plugin/plugin.xml").exists() shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should not copy if destination is not empty" {
        val tempDir = createTempDirectory("plugin-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create file in destination
            destination.resolve("existing.txt").writeText("existing")

            // Create source plugin
            source.resolve("plugin.xml").writeText("plugin")

            val result = DirectoryCopier.copyIfFirstOpen(source, destination)

            result shouldBe false
            destination.resolve("plugin.xml").exists() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should silently skip if source does not exist" {
        val tempDir = createTempDirectory("plugin-test")
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
        val tempDir = createTempDirectory("plugin-test")
        try {
            val source = tempDir.resolve("source")
            val destination = tempDir.resolve("destination")

            Files.createDirectories(source)
            Files.createDirectories(destination)

            // Create nested structure
            val nestedDir = source.resolve("plugin/lib/resources")
            Files.createDirectories(nestedDir)
            nestedDir.resolve("data.json").writeText("{}")

            DirectoryCopier.copyIfFirstOpen(source, destination)

            destination.resolve("plugin/lib/resources/data.json").exists() shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
