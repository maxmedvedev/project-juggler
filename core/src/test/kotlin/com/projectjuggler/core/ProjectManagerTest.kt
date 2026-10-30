package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ProjectManagerTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("test-pm")
    val projectDir = createTempDir("test-project")

    "should expand tilde in path strings" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        // Test path resolution with tilde
        val homeDir = System.getProperty("user.home")
        val relativePath = Path.of(homeDir).relativize(projectDir)
        val tildePath = "~/$relativePath"

        // Should expand tilde when resolving path
        val resolvedProjectPath = projectManager.resolvePath(tildePath)
        resolvedProjectPath shouldNotBe null

        // Should be same as non-tilde path
        val regularProjectPath = projectManager.resolvePath(projectDir.toString())
        resolvedProjectPath.id shouldBe regularProjectPath.id
    }

    "should handle absolute paths without expansion" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        val resolvedProjectPath = projectManager.resolvePath(projectDir.toString())
        resolvedProjectPath shouldNotBe null
        // ID format is now projectname-hash16chars
        resolvedProjectPath.id.id.substringAfterLast("-").length shouldBe 16 // hash truncated to 16 chars
    }

    "should validate path existence correctly" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        // Existing path should validate
        projectManager.validatePathExists(projectDir.toString()) shouldBe true

        // Non-existent path should not validate
        projectManager.validatePathExists("/tmp/non-existent-xyz-123-abc-def") shouldBe false

        // Tilde path should work
        val homeDir = System.getProperty("user.home")
        val relativePath = Path.of(homeDir).relativize(projectDir)
        val tildePath = "~/$relativePath"

        projectManager.validatePathExists(tildePath) shouldBe true
    }

    "should resolve tilde paths correctly" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        // Create a path relative to home
        val homeDir = System.getProperty("user.home")
        val relativePath = Path.of(homeDir).relativize(projectDir)
        val tildePath = "~/$relativePath"

        // Resolve should expand the tilde
        val resolved = projectManager.resolvePath(tildePath)

        // The resolved path should expand to an equivalent path
        // Compare absolute, normalized paths to account for symlinks
        resolved.path.toAbsolutePath().normalize().toString() shouldBe
            projectDir.toAbsolutePath().normalize().toString()
    }

    "should handle relative paths" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        // Relative path (not starting with /)
        val relativePath = "some/relative/path"

        // Should not throw exception
        val resolved = projectManager.resolvePath(relativePath)
        resolved shouldNotBe null
    }

    "should generate consistent IDs regardless of tilde usage" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        // Create tilde path
        val homeDir = System.getProperty("user.home")
        val relativePath = Path.of(homeDir).relativize(projectDir)
        val tildePath = "~/$relativePath"

        // Resolve both tilde and absolute path
        val tildeProjectPath = projectManager.resolvePath(tildePath)
        val absoluteProjectPath = projectManager.resolvePath(projectDir.toString())

        // IDs should be identical
        tildeProjectPath.id shouldBe absoluteProjectPath.id
    }

    "listSyncable should exclude the main project" {
        val testInstallation = IdeInstallation("/test/ide", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(createTempDir("test-pm-syncable"), testInstallation)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)

        val mainPath = projectManager.resolvePath(projectDir.toString())
        val otherPath = projectManager.resolvePath(createTempDir("test-project-other").toString())
        projectManager.registerOrUpdate(mainPath)
        projectManager.registerOrUpdate(otherPath)
        MainProjectService.setMainProject(ideConfigRepository, mainPath)

        projectManager.listAll().map { it.path } shouldContainExactlyInAnyOrder listOf(mainPath, otherPath)
        projectManager.listSyncable().map { it.path } shouldContainExactly listOf(otherPath)
    }

    fun newRepository() = IdeConfigRepository(createTempDir("test-pm-repo"), IdeInstallation("/test/ide", "Test IDE"))

    "registerOrUpdate increments openCount and preserves a saved debugPort" {
        val repository = newRepository()
        val projectManager = ProjectManager.getInstance(repository)
        val path = ProjectPath(projectDir.toString())

        projectManager.registerOrUpdate(path).openCount shouldBe 1
        repository.saveProjectMetadata(repository.loadProjectMetadata(path)!!.copy(debugPort = 7001))
        val updated = projectManager.registerOrUpdate(path)

        updated.openCount shouldBe 2
        updated.debugPort shouldBe 7001
        repository.loadProjectMetadata(path) shouldBe updated
    }

    "registerOrUpdate preserves baseVmOptionsHash" {
        val repository = newRepository()
        val projectManager = ProjectManager.getInstance(repository)
        val path = ProjectPath(projectDir.toString())

        projectManager.updateBaseVmOptionsHash(projectManager.registerOrUpdate(path), "abc123")

        projectManager.registerOrUpdate(path).baseVmOptionsHash shouldBe "abc123"
    }

    "updateBaseVmOptionsHash keeps a debugPort saved after the project was loaded" {
        val repository = newRepository()
        val projectManager = ProjectManager.getInstance(repository)
        val loaded = projectManager.registerOrUpdate(ProjectPath("/work/repo"))
        repository.saveProjectMetadata(loaded.copy(debugPort = 7001))

        val updated = projectManager.updateBaseVmOptionsHash(loaded, "abc123")

        updated.debugPort shouldBe 7001
        updated.baseVmOptionsHash shouldBe "abc123"
        repository.loadProjectMetadata(loaded.path) shouldBe updated
    }

    "listAll sorts by lastOpened descending" {
        val repository = newRepository()
        val old = ProjectMetadata(ProjectPath("/work/old"), "2026-01-01T00:00:00Z")
        val newest = ProjectMetadata(ProjectPath("/work/newest"), "2026-03-01T00:00:00Z")
        val middle = ProjectMetadata(ProjectPath("/work/middle"), "2026-02-01T00:00:00Z")
        listOf(old, newest, middle).forEach(repository::saveProjectMetadata)

        ProjectManager.getInstance(repository).listAll() shouldContainExactly listOf(newest, middle, old)
    }

    "ensureDebugPort returns an existing port without saving" {
        val repository = newRepository()
        val metadata = ProjectMetadata(ProjectPath("/work/repo"), "2026-01-01T00:00:00Z", debugPort = 7001)

        ProjectManager.getInstance(repository).ensureDebugPort(metadata) shouldBe 7001

        repository.loadProjectMetadata(metadata.path) shouldBe null
    }

    "ensureDebugPort saves an allocated port when missing" {
        val repository = newRepository()
        val projectManager = ProjectManager.getInstance(repository)
        val metadata = projectManager.registerOrUpdate(ProjectPath("/work/repo"))

        val port = projectManager.ensureDebugPort(metadata)

        port.shouldNotBeNull()
        repository.loadProjectMetadata(metadata.path)?.debugPort shouldBe port
    }

    "remove deletes metadata only" {
        val repository = newRepository()
        val projectManager = ProjectManager.getInstance(repository)
        val path = ProjectPath("/work/repo")
        projectManager.registerOrUpdate(path)
        val projectRoot = repository.baseDir.resolve("projects").resolve(path.id.id)
        Files.createDirectories(projectRoot.resolve("config"))

        projectManager.remove(path)

        projectManager.get(path) shouldBe null
        projectRoot.resolve("config").exists() shouldBe true
    }

    "get returns null for an unknown path" {
        ProjectManager.getInstance(newRepository()).get(ProjectPath("/work/unknown")) shouldBe null
    }
})
