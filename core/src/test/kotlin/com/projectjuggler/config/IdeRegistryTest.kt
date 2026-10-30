package com.projectjuggler.config

import com.projectjuggler.locators.IntelliJLocator
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

class IdeRegistryTest : StringSpec({
    val first = IdeInstallation("/ide/first", "First IDE")
    val second = IdeInstallation("/ide/second", "Second IDE")

    afterTest {
        unmockkObject(IntelliJLocator)
    }

    fun IdeRegistry.register(installation: IdeInstallation) =
        getRepository(installation).save(IdeConfig.default(installation))

    "getRegisteredInstallations is empty without v2 dir" {
        IdeRegistry(createTempDir("test-registry")).getRegisteredInstallations().shouldBeEmpty()
    }

    "getRegisteredInstallations lists saved installations and ignores dirs without config" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        registry.register(first)
        registry.register(second)
        Files.createDirectories(baseDir.resolve("v2/stray"))

        registry.getRegisteredInstallations() shouldContainExactlyInAnyOrder listOf(first, second)
    }

    "getRepository creates nothing on disk" {
        val baseDir = createTempDir("test-registry")

        val repository = IdeRegistry(baseDir).getRepository(first)

        repository.baseDir shouldBe baseDir.resolve("v2").resolve(first.directoryName)
        baseDir.resolve("v2").exists() shouldBe false
    }

    "getAllInstallations merges detected and registered, detected entry wins" {
        val registry = IdeRegistry(createTempDir("test-registry"))
        registry.register(IdeInstallation(first.executablePath, "Registered Name"))
        registry.register(second)
        val detected = IdeInstallation(first.executablePath, "Detected Name")
        mockkObject(IntelliJLocator)
        every { IntelliJLocator.findAllInstallations() } returns listOf(detected)

        registry.getAllInstallations() shouldContainExactly listOf(detected, second)
    }

    "getDefaultBaseDir honors the system property and falls back to user home" {
        val saved = System.getProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
        try {
            System.setProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR, "/custom/base")
            IdeRegistry.getDefaultBaseDir() shouldBe Paths.get("/custom/base")

            System.clearProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
            IdeRegistry.getDefaultBaseDir() shouldBe Paths.get(System.getProperty("user.home"), ".project-juggler")
        } finally {
            if (saved != null) System.setProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR, saved)
            else System.clearProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
        }
    }
})
