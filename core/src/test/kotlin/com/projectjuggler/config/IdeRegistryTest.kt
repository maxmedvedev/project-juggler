package com.projectjuggler.config

import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.locators.IntelliJLocator
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.writeText

class IdeRegistryTest : StringSpec({
    extensions(KoinTestExtension())

    val first = IdeInstallation("/ide/first", "First IDE")
    val second = IdeInstallation("/ide/second", "Second IDE")

    afterTest {
        unmockkObject(IntelliJLocator)
    }

    // Writes v2/<directoryName> directly: getRepository would reuse a dir registered for the same executable
    fun register(baseDir: Path, installation: IdeInstallation) =
        IdeConfigRepository.create(installation, baseDir.resolve("v2")).save(IdeConfig.default(installation))

    "getRegisteredInstallations is empty without v2 dir" {
        IdeRegistry(createTempDir("test-registry")).getRegisteredInstallations().shouldBeEmpty()
    }

    "getRegisteredInstallations lists saved installations and ignores dirs without config" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        register(baseDir, first)
        register(baseDir, second)
        Files.createDirectories(baseDir.resolve("v2/stray"))

        registry.getRegisteredInstallations() shouldContainExactlyInAnyOrder listOf(first, second)
    }

    "getRegisteredInstallations omits an ide dir with corrupt config" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        register(baseDir, first)
        val corruptDir = baseDir.resolve("v2/corrupt")
        Files.createDirectories(corruptDir)
        corruptDir.resolve("config.json").writeText("{not json")

        registry.getRegisteredInstallations() shouldContainExactly listOf(first)
    }

    "getRepository creates nothing on disk" {
        val baseDir = createTempDir("test-registry")

        val repository = IdeRegistry(baseDir).getRepository(first)

        repository.baseDir shouldBe baseDir.resolve("v2").resolve(first.directoryName)
        baseDir.resolve("v2").exists() shouldBe false
    }

    "getRepository reuses a registered dir with the same executable under another display name" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        register(baseDir, first)

        val repository = registry.getRepository(IdeInstallation(first.executablePath, "IDE"))

        repository.baseDir shouldBe baseDir.resolve("v2").resolve(first.directoryName)
        repository.installation.displayName shouldBe "First IDE"
    }

    "getRepository prefers the exact dir over another dir with the same executable" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        val renamed = IdeInstallation(first.executablePath, "Renamed IDE")
        register(baseDir, first)
        register(baseDir, renamed)

        registry.getRepository(renamed).baseDir shouldBe baseDir.resolve("v2").resolve(renamed.directoryName)
    }

    "getRepository ignores registered dirs with a corrupt config" {
        val baseDir = createTempDir("test-registry")
        val corruptDir = baseDir.resolve("v2/x")
        Files.createDirectories(corruptDir)
        corruptDir.resolve("config.json").writeText("{")

        val repository = IdeRegistry(baseDir).getRepository(first)

        repository.baseDir shouldBe baseDir.resolve("v2").resolve(first.directoryName)
        repository.baseDir.exists() shouldBe false
    }

    "adoptLegacyRepository moves the legacy dir and keeps its projects and recents" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        val legacy = IdeInstallation(LEGACY_HOME, "legacy")
        val legacyRepository = registry.getRepository(legacy)
        legacyRepository.save(IdeConfig.default(legacy).copy(mainProjectPath = "/main"))
        val project = ProjectPath(createTempDir("test-registry-project").toString())
        ProjectManager.getInstance(legacyRepository).registerOrUpdate(project)
        RecentProjectsIndex.getInstance(legacyRepository).recordOpen(project)
        val projectsBefore = legacyRepository.loadAllProjects()

        val adopted = registry.adoptLegacyRepository(first, listOf(LEGACY_HOME)).shouldNotBeNull()

        adopted.baseDir shouldBe baseDir.resolve("v2").resolve(first.directoryName)
        legacyRepository.baseDir.exists() shouldBe false
        adopted.load().installation shouldBe first
        adopted.load().mainProjectPath shouldBe "/main"
        adopted.loadAllProjects() shouldContainExactly projectsBefore
        RecentProjectsIndex.getInstance(adopted).getRecent(10).map { it.path } shouldContainExactly listOf(project)
        registry.getRepository(first).baseDir shouldBe adopted.baseDir
    }

    "adoptLegacyRepository returns null without a legacy dir" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        register(baseDir, second)

        registry.adoptLegacyRepository(first, listOf(LEGACY_HOME)).shouldBeNull()
        registry.getRegisteredInstallations() shouldContainExactly listOf(second)
    }

    "adoptLegacyRepository leaves both dirs alone when the target exists" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        val legacy = IdeInstallation(LEGACY_HOME, "legacy")
        register(baseDir, legacy)
        register(baseDir, first)

        registry.adoptLegacyRepository(first, listOf(LEGACY_HOME)).shouldBeNull()
        registry.getRegisteredInstallations() shouldContainExactlyInAnyOrder listOf(legacy, first)
    }

    "adoptLegacyRepository leaves the legacy dir alone while an isolated instance holds a lock" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        val legacy = IdeInstallation(LEGACY_HOME, "legacy")
        register(baseDir, legacy)
        val lockDir = baseDir.resolve("v2").resolve(legacy.directoryName).resolve("projects/p/config")
        Files.createDirectories(lockDir)
        lockDir.resolve(".lock").writeText("")

        registry.adoptLegacyRepository(first, listOf(LEGACY_HOME)).shouldBeNull()
        registry.getRegisteredInstallations() shouldContainExactly listOf(legacy)
        baseDir.resolve("v2").resolve(first.directoryName).exists() shouldBe false
    }

    "adoptLegacyRepository returns null with two candidates" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        val legacyA = IdeInstallation(LEGACY_HOME, "legacy a")
        val legacyB = IdeInstallation(LEGACY_HOME, "legacy b")
        register(baseDir, legacyA)
        register(baseDir, legacyB)

        registry.adoptLegacyRepository(first, listOf(LEGACY_HOME)).shouldBeNull()
        registry.getRegisteredInstallations() shouldContainExactlyInAnyOrder listOf(legacyA, legacyB)
    }

    "getAllInstallations merges detected and registered, detected entry wins" {
        val baseDir = createTempDir("test-registry")
        val registry = IdeRegistry(baseDir)
        register(baseDir, IdeInstallation(first.executablePath, "Registered Name"))
        register(baseDir, second)
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

private const val LEGACY_HOME = "/ide/first-home"
