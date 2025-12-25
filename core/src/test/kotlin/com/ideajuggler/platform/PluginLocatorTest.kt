package com.ideajuggler.platform

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PluginLocatorTest : StringSpec({

    "should return null or valid plugins directory" {
        // This test may pass or fail depending on local environment
        // Just verify it doesn't throw exceptions
        val result = PluginLocator.findDefaultPluginsDirectory()

        if (result != null) {
            result.fileName.toString() shouldBe "plugins"
        }
    }

    "should handle platform detection without crashing" {
        // Verify platform-specific logic doesn't crash
        val platform = Platform.current()
        platform shouldNotBe null
    }
})
