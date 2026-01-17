package com.projectjuggler.core

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import com.projectjuggler.test.createTempFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.writeText

class BaseVMOptionsTrackerTest : StringSpec({
    extensions(KoinTestExtension())

    val tempDir = createTempDir("test-config")
    val baseVmOptions = createTempFile("base", ".vmoptions")

    "should detect no changes when hash matches" {
        baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t1"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        // Set up initial config with matching hash
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = baseVmOptions.toString(),
                baseVmOptionsHash = com.projectjuggler.util.HashUtils.calculateFileHash(baseVmOptions)
            )
        )

        tracker.hasChanged() shouldBe false
    }

    "should detect changes when hash differs" {
        val vmOptions2 = createTempFile("base2", ".vmoptions")
        vmOptions2.writeText("-Xms256m\n-Xmx2048m")

        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t2"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        // Set up initial config with old hash
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = vmOptions2.toString(),
                baseVmOptionsHash = "old-hash-value"
            )
        )

        tracker.hasChanged() shouldBe true
    }

    "should detect changes after file modification" {
        val vmOptions3 = createTempFile("base3", ".vmoptions")
        vmOptions3.writeText("-Xms256m\n-Xmx2048m")

        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t3"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        // Configure base path first
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = vmOptions3.toString()
            )
        )

        // Set up initial config with current hash
        tracker.updateHash()

        // File hasn't changed yet
        tracker.hasChanged() shouldBe false

        // Now modify the file
        vmOptions3.writeText("-Xms512m\n-Xmx4096m")

        // Should detect change
        tracker.hasChanged() shouldBe true
    }

    "should return false when base VM options path is not configured" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t4"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        // No base VM options configured
        tracker.hasChanged() shouldBe false
    }

    "should return false when base VM options file does not exist" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t5"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        // Configure with non-existent path
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = "/non/existent/path.vmoptions",
                baseVmOptionsHash = "some-hash"
            )
        )

        tracker.hasChanged() shouldBe false
    }

    "should update hash correctly" {
        val vmOptions6 = createTempFile("base6", ".vmoptions")
        vmOptions6.writeText("-Xms256m\n-Xmx2048m")

        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t6"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        // Configure base path
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = vmOptions6.toString()
            )
        )

        // Update hash
        tracker.updateHash()

        // Load config and verify hash was stored
        val config = ideConfigRepository.load()
        config.baseVmOptionsHash shouldBe com.projectjuggler.util.HashUtils.calculateFileHash(vmOptions6)

        // Should not detect changes after update
        tracker.hasChanged() shouldBe false
    }

    "should get base VM options path when configured and exists" {
        val vmOptions7 = createTempFile("base7", ".vmoptions")
        vmOptions7.writeText("-Xms256m")

        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t7"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = vmOptions7.toString()
            )
        )

        val path = tracker.getBaseVmOptionsPath()
        path shouldBe vmOptions7
    }

    "should return null when base VM options path is not configured" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t8"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        val path = tracker.getBaseVmOptionsPath()
        path shouldBe null
    }

    "should return null when base VM options file does not exist" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t9"), testInstallation)
        val tracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)

        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = "/non/existent/path.vmoptions"
            )
        )

        val path = tracker.getBaseVmOptionsPath()
        path shouldBe null
    }
})
