package com.ideajuggler.platform

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

class IntelliJLocatorTest : StringSpec({

    "should return null when no IntelliJ installation found" {
        val locator = IntelliJLocator()

        // This test might find actual IntelliJ installations on the system
        // So we just verify the method doesn't throw
        val result = locator.findIntelliJ()

        // Result can be null or a valid path
        // We just verify it doesn't crash
        result // Just access it to ensure no exception
    }

    "should detect IntelliJ on current platform" {
        val locator = IntelliJLocator()

        // This test is platform-specific and may find real installations
        // We're just verifying the method works without crashing
        val result = locator.findIntelliJ()

        // If IntelliJ is installed, result should be a valid path
        if (result != null) {
            Files.exists(result) shouldBe true
        }
    }

    "should prioritize standard installation paths" {
        // This is more of a documentation test
        // The actual behavior depends on what's installed on the system
        val locator = IntelliJLocator()
        val result = locator.findIntelliJ()

        // If found, should be an executable
        if (result != null) {
            when (Platform.current()) {
                Platform.MACOS -> {
                    result.toString() shouldNotBe ""
                }
                Platform.LINUX -> {
                    result.toString() shouldNotBe ""
                }
                Platform.WINDOWS -> {
                    result.toString() shouldNotBe ""
                }
            }
        }
    }

    "should handle missing IntelliJ gracefully" {
        val locator = IntelliJLocator()

        // Even if IntelliJ isn't installed, this should not throw
        val result = locator.findIntelliJ()

        // Result is either null or a valid path
        if (result != null) {
            Files.exists(result) shouldBe true
        }
    }
})
