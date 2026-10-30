package com.projectjuggler.platform

import com.projectjuggler.locators.PluginLocator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories

class PluginLocatorTest : StringSpec({
    val configDir = ".local/share/JetBrains/IntelliJIdea2025.1"

    "smoke: findDefaultPluginsDirectory on the real machine does not throw" {
        PluginLocator.findDefaultPluginsDirectory()
    }

    "returns the plugins child of the chosen config dir" {
        val fixture = locatorFixture()
        val plugins = fixture.home.resolve("$configDir/plugins").createDirectories()

        PluginLocator.findDefaultPluginsDirectory(fixture.env(Platform.LINUX)) shouldBe plugins
    }

    "returns null when plugins is absent" {
        val fixture = locatorFixture()
        fixture.home.resolve(configDir).createDirectories()

        PluginLocator.findDefaultPluginsDirectory(fixture.env(Platform.LINUX)) shouldBe null
    }

    "returns null when plugins is a file" {
        val fixture = locatorFixture()
        fixture.home.createFileWithParents("$configDir/plugins")

        PluginLocator.findDefaultPluginsDirectory(fixture.env(Platform.LINUX)) shouldBe null
    }

    "returns null without a config dir" {
        PluginLocator.findDefaultPluginsDirectory(locatorFixture().env(Platform.LINUX)) shouldBe null
    }
})
