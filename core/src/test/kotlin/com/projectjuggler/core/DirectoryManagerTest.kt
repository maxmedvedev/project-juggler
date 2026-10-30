package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.IdeConfig
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.locators.ConfigLocator
import com.projectjuggler.locators.PluginLocator
import com.projectjuggler.test.createTempDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

class DirectoryManagerTest : StringSpec({
    extensions(KoinTestExtension())

    val tempDir = createTempDir("test-base")

    "should create all project directories" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t1"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        Files.exists(dirs.root) shouldBe true
        Files.exists(dirs.config) shouldBe true
        Files.exists(dirs.system) shouldBe true
        Files.exists(dirs.logs) shouldBe true
        Files.exists(dirs.plugins) shouldBe true
    }

    "should create directories under correct base path" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val baseDir = tempDir.resolve("t2")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path2")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        dirs.root.toString() shouldBe baseDir.resolve("projects").resolve(project.id.id).toString()
    }

    "should create nested directory structure correctly" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t3"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path3")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        dirs.config.toString() shouldBe dirs.root.resolve("config").toString()
        dirs.system.toString() shouldBe dirs.root.resolve("system").toString()
        dirs.logs.toString() shouldBe dirs.root.resolve("logs").toString()
        dirs.plugins.toString() shouldBe dirs.root.resolve("plugins").toString()
    }

    "should not fail if directories already exist" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t4"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path4")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        // Create first time
        val dirs1 = manager.ensureProjectDirectories(project)

        // Create second time (should not fail)
        val dirs2 = manager.ensureProjectDirectories(project)

        dirs1.root shouldBe dirs2.root
        Files.exists(dirs2.config) shouldBe true
        Files.exists(dirs2.system) shouldBe true
        Files.exists(dirs2.logs) shouldBe true
        Files.exists(dirs2.plugins) shouldBe true
    }

    "should clean project directories completely" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t5"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path5")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        // Create directories
        val dirs = manager.ensureProjectDirectories(project)

        // Create some files
        Files.writeString(dirs.config.resolve("test.txt"), "test content")
        Files.writeString(dirs.system.resolve("test.txt"), "test content")

        // Verify directories exist
        Files.exists(dirs.root) shouldBe true

        // Clean
        manager.cleanProject(project)

        // Verify directories are gone
        Files.exists(dirs.root) shouldBe false
        Files.exists(dirs.config) shouldBe false
        Files.exists(dirs.system) shouldBe false
    }

    "should not fail when cleaning non-existent project" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t6"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/non/existent/project")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        // Try to clean non-existent project (should not throw)
        manager.cleanProject(project)
    }

    "should get correct project root path" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val baseDir = tempDir.resolve("t7")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)

        val projectPath = ProjectPath("/test/project/path7")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-01-01T00:00:00Z"
        )

        val root = manager.getProjectRoot(project)

        root.toString() shouldBe baseDir.resolve("projects").resolve(project.id.id).toString()
    }

    "should handle project IDs with special characters" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t8"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)

        val projectPath = ProjectPath("/test/project/@#$/path")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        Files.exists(dirs.root) shouldBe true
        Files.exists(dirs.config) shouldBe true
    }

    "should create parent directories if they don't exist" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val newTempDir = tempDir.resolve("t9-nonexistent")
        val ideConfigRepository = IdeConfigRepository(newTempDir, testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)

        val projectPath = ProjectPath("/test/project/path9")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        Files.exists(newTempDir) shouldBe true
        Files.exists(newTempDir.resolve("projects")) shouldBe true
        Files.exists(dirs.root) shouldBe true
    }

    "should handle plugin copying on directory creation" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(tempDir.resolve("t10"), testInstallation)
        val manager = DirectoryManager.getInstance(ideConfigRepository)
        val projectPath = ProjectPath("/test/project/path10")
        val project = ProjectMetadata(
            path = projectPath,
            lastOpened = "2025-12-25T10:00:00Z",
            openCount = 1
        )

        val dirs = manager.ensureProjectDirectories(project)

        // Verify plugins directory exists (even if no plugins copied)
        Files.exists(dirs.plugins) shouldBe true

        // Second call should not fail
        val dirs2 = manager.ensureProjectDirectories(project)
        Files.exists(dirs2.plugins) shouldBe true
    }

    val installation = IdeInstallation("/test/ide", "Test IDE")
    val project = ProjectMetadata(path = ProjectPath("/test/project/base"), lastOpened = "2025-12-25T10:00:00Z")

    afterTest {
        unmockkObject(ConfigLocator, PluginLocator)
    }

    fun mockLocators(config: Path?, plugins: Path?) {
        mockkObject(ConfigLocator, PluginLocator)
        every { ConfigLocator.findDefaultConfigDirectory() } returns config
        every { PluginLocator.findDefaultPluginsDirectory() } returns plugins
    }

    fun repositoryWith(baseConfig: Path? = null, basePlugins: Path? = null): IdeConfigRepository {
        val repository = IdeConfigRepository(createTempDir("test-dm"), installation)
        repository.save(
            IdeConfig(installation, baseConfigPath = baseConfig?.toString(), basePluginsPath = basePlugins?.toString())
        )
        return repository
    }

    fun Path.createFileWithParents(relative: String): Path =
        resolve(relative).also { it.parent.createDirectories(); it.createFile() }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun Path.relativeFiles(): List<String> =
        walk().filter { it.isRegularFile() }.map { relativize(it).toString().replace('\\', '/') }.sorted().toList()

    "base paths come from the configured paths first" {
        mockLocators(Paths.get("/located/config"), Paths.get("/located/plugins"))
        val manager = DirectoryManager.getInstance(
            repositoryWith(Paths.get("/configured/config"), Paths.get("/configured/plugins"))
        )

        manager.getBaseConfigPath() shouldBe Paths.get("/configured/config")
        manager.getBasePluginsPath() shouldBe Paths.get("/configured/plugins")
    }

    "base paths fall back to the locators" {
        mockLocators(Paths.get("/located/config"), Paths.get("/located/plugins"))
        val manager = DirectoryManager.getInstance(repositoryWith())

        manager.getBaseConfigPath() shouldBe Paths.get("/located/config")
        manager.getBasePluginsPath() shouldBe Paths.get("/located/plugins")
    }

    "base paths are null when neither is available" {
        mockLocators(null, null)
        val manager = DirectoryManager.getInstance(repositoryWith())

        manager.getBaseConfigPath() shouldBe null
        manager.getBasePluginsPath() shouldBe null
    }

    "first open copies base config without excluded entries" {
        mockLocators(null, null)
        val baseConfig = createTempDir("test-base-config")
        listOf(
            ".lock", "recentProjects.xml", "recentProjectDirectories.xml", "options/recentProjects.xml",
            "plugins/x.jar", "options/other.xml",
        ).forEach { baseConfig.createFileWithParents(it) }

        val dirs = DirectoryManager.getInstance(repositoryWith(baseConfig = baseConfig)).ensureProjectDirectories(project)

        dirs.config.relativeFiles() shouldContainExactly listOf("options/other.xml")
    }

    "first open copies base plugins" {
        mockLocators(null, null)
        val basePlugins = createTempDir("test-base-plugins")
        basePlugins.createFileWithParents("a.jar")

        val dirs = DirectoryManager.getInstance(repositoryWith(basePlugins = basePlugins)).ensureProjectDirectories(project)

        dirs.plugins.relativeFiles() shouldContainExactly listOf("a.jar")
    }

    "base plugins are not copied when plugins dir is not empty" {
        mockLocators(null, null)
        val basePlugins = createTempDir("test-base-plugins")
        basePlugins.createFileWithParents("a.jar")
        val manager = DirectoryManager.getInstance(repositoryWith(basePlugins = basePlugins))
        manager.getProjectRoot(project).resolve("plugins").createFileWithParents("existing.jar")

        val dirs = manager.ensureProjectDirectories(project)

        dirs.plugins.relativeFiles() shouldContainExactly listOf("existing.jar")
    }

    "without any base directories are created and nothing is copied" {
        mockLocators(null, null)

        val dirs = DirectoryManager.getInstance(repositoryWith()).ensureProjectDirectories(project)

        listOf(dirs.config, dirs.system, dirs.logs, dirs.plugins).forEach { it.exists() shouldBe true }
        dirs.root.relativeFiles().shouldBeEmpty()
    }

    "sync from base throws when no base is found" {
        mockLocators(null, null)
        val manager = DirectoryManager.getInstance(repositoryWith())

        shouldThrow<IllegalStateException> { manager.syncConfigFromBase(project) }
        shouldThrow<IllegalStateException> { manager.syncPluginsFromBase(project) }
    }
})
