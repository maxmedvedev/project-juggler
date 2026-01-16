package com.projectjuggler.platform

import com.projectjuggler.locators.ConfigLocator
import com.projectjuggler.locators.PluginLocator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ConfigLocatorTest : StringSpec({

    "should return null or valid config directory" {
        // This test may pass or fail depending on local environment
        // Just verify it doesn't throw exceptions
        val result = ConfigLocator.findDefaultConfigDirectory()

        if (result != null) {
            // Config directory should be named like "IntelliJIdea2024.3"
            result.fileName.toString().startsWith("IntelliJIdea") shouldBe true
        }
    }

    "should return parent of plugins directory" {
        val pluginsDir = PluginLocator.findDefaultPluginsDirectory()
        val configDir = ConfigLocator.findDefaultConfigDirectory()

        if (pluginsDir != null && configDir != null) {
            pluginsDir.parent shouldBe configDir
        }
    }
})
