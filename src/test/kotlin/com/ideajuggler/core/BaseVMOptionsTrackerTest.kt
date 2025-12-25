package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.GlobalConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class BaseVMOptionsTrackerTest : StringSpec({

    "should detect no changes when hash matches" {
        val tempDir = createTempDirectory("test-config")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            // Set up initial config with matching hash
            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = baseVmOptions.toString(),
                    baseVmOptionsHash = com.ideajuggler.util.HashUtils.calculateFileHash(baseVmOptions)
                )
            )

            tracker.hasChanged() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should detect changes when hash differs" {
        val tempDir = createTempDirectory("test-config")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            // Set up initial config with old hash
            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = baseVmOptions.toString(),
                    baseVmOptionsHash = "old-hash-value"
                )
            )

            tracker.hasChanged() shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should detect changes after file modification" {
        val tempDir = createTempDirectory("test-config")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            // Configure base path first
            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = baseVmOptions.toString()
                )
            )

            // Set up initial config with current hash
            tracker.updateHash()

            // File hasn't changed yet
            tracker.hasChanged() shouldBe false

            // Now modify the file
            baseVmOptions.writeText("-Xms512m\n-Xmx4096m")

            // Should detect change
            tracker.hasChanged() shouldBe true
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should return false when base VM options path is not configured" {
        val tempDir = createTempDirectory("test-config")

        try {
            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            // No base VM options configured
            tracker.hasChanged() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should return false when base VM options file does not exist" {
        val tempDir = createTempDirectory("test-config")

        try {
            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            // Configure with non-existent path
            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = "/non/existent/path.vmoptions",
                    baseVmOptionsHash = "some-hash"
                )
            )

            tracker.hasChanged() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should update hash correctly" {
        val tempDir = createTempDirectory("test-config")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            // Configure base path
            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = baseVmOptions.toString()
                )
            )

            // Update hash
            tracker.updateHash()

            // Load config and verify hash was stored
            val config = configRepository.load()
            config.baseVmOptionsHash shouldBe com.ideajuggler.util.HashUtils.calculateFileHash(baseVmOptions)

            // Should not detect changes after update
            tracker.hasChanged() shouldBe false
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should get base VM options path when configured and exists" {
        val tempDir = createTempDirectory("test-config")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("-Xms256m")

            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = baseVmOptions.toString()
                )
            )

            val path = tracker.getBaseVmOptionsPath()
            path shouldBe baseVmOptions
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should return null when base VM options path is not configured" {
        val tempDir = createTempDirectory("test-config")

        try {
            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            val path = tracker.getBaseVmOptionsPath()
            path shouldBe null
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should return null when base VM options file does not exist" {
        val tempDir = createTempDirectory("test-config")

        try {
            val configRepository = ConfigRepository(tempDir)
            val tracker = BaseVMOptionsTracker(configRepository)

            configRepository.save(
                GlobalConfig(
                    baseVmOptionsPath = "/non/existent/path.vmoptions"
                )
            )

            val path = tracker.getBaseVmOptionsPath()
            path shouldBe null
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})
