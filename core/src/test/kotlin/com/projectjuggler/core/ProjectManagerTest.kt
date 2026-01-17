package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path

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
})
