package com.projectjuggler.platform

import com.projectjuggler.locators.ConfigLocator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories

class ConfigLocatorTest : StringSpec({
    val macBase = "Library/Application Support/JetBrains"

    "smoke: findDefaultConfigDirectory on the real machine does not throw" {
        ConfigLocator.findDefaultConfigDirectory()
    }

    "picks the lexicographically greatest IntelliJIdea dir" {
        val fixture = locatorFixture()
        fixture.home.resolve("$macBase/IntelliJIdea2024.3").createDirectories()
        val newest = fixture.home.resolve("$macBase/IntelliJIdea2025.1").createDirectories()

        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.MACOS)) shouldBe newest
    }

    "sorts versions as strings, so 2025.9 wins over 2025.10 (known gap 7)" {
        val fixture = locatorFixture()
        fixture.home.resolve("$macBase/IntelliJIdea2025.10").createDirectories()
        val picked = fixture.home.resolve("$macBase/IntelliJIdea2025.9").createDirectories()

        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.MACOS)) shouldBe picked
    }

    "ignores community IdeaIC dirs (known gap 7)" {
        val fixture = locatorFixture()
        fixture.home.resolve("$macBase/IdeaIC2025.1").createDirectories()

        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.MACOS)) shouldBe null
    }

    "linux config dir is looked up under .local/share (known gap 7)" {
        val fixture = locatorFixture()
        fixture.home.resolve(".config/JetBrains/IntelliJIdea2025.1").createDirectories()
        val picked = fixture.home.resolve(".local/share/JetBrains/IntelliJIdea2025.1").createDirectories()

        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.LINUX)) shouldBe picked
    }

    "windows config dir is looked up under APPDATA" {
        val fixture = locatorFixture()
        val appData = fixture.home.resolve("AppData/Roaming")
        val picked = appData.resolve("JetBrains/IntelliJIdea2025.1").createDirectories()

        ConfigLocator.findDefaultConfigDirectory(
            fixture.env(Platform.WINDOWS, mapOf("APPDATA" to appData.toString()))
        ) shouldBe picked
    }

    "returns null when the base dir is missing" {
        val fixture = locatorFixture()

        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.MACOS)) shouldBe null
        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.LINUX)) shouldBe null
        ConfigLocator.findDefaultConfigDirectory(fixture.env(Platform.WINDOWS)) shouldBe null
    }
})
