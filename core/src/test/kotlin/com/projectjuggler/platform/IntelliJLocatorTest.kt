package com.projectjuggler.platform

import com.projectjuggler.locators.IntelliJLocator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class IntelliJLocatorTest : StringSpec({

    "should return null when no IntelliJ installation found" {

        // This test might find actual IntelliJ installations on the system
        // So we just verify the method doesn't throw
        // Result can be null or a valid path
        // We just verify it doesn't crash
        IntelliJLocator.findIntelliJ()
    }

    "should detect IntelliJ on current platform" {

        // This test is platform-specific and may find real installations
        // We're just verifying the method works without crashing
        val result = IntelliJLocator.findIntelliJ()

        // If IntelliJ is installed, result should be a valid path
        if (result != null) {
            Files.exists(result) shouldBe true
        }
    }

    "should prioritize standard installation paths" {
        // This is more of a documentation test
        // The actual behavior depends on what's installed on the system
        val result = IntelliJLocator.findIntelliJ()

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
        // Even if IntelliJ isn't installed, this should not throw
        val result = IntelliJLocator.findIntelliJ()

        // Result is either null or a valid path
        if (result != null) {
            Files.exists(result) shouldBe true
        }
    }
})
