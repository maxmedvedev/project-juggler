package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class ProjectManagerTest : StringSpec({

    "should expand tilde in path strings" {
        val baseDir = createTempDirectory("test-pm")
        val projectDir = createTempDirectory("test-project")

        try {
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

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir.toFile().deleteRecursively()
        }
    }

    "should handle absolute paths without expansion" {
        val baseDir = createTempDirectory("test-pm")
        val projectDir = createTempDirectory("test-project")

        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
            val projectManager = ProjectManager.getInstance(ideConfigRepository)

            val resolvedProjectPath = projectManager.resolvePath(projectDir.toString())
            resolvedProjectPath shouldNotBe null
            // ID format is now projectname-hash16chars
            resolvedProjectPath.id.id.substringAfterLast("-").length shouldBe 16 // hash truncated to 16 chars

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir.toFile().deleteRecursively()
        }
    }

    "should validate path existence correctly" {
        val baseDir = createTempDirectory("test-pm")
        val projectDir = createTempDirectory("test-project")

        try {
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

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir.toFile().deleteRecursively()
        }
    }

    "should resolve tilde paths correctly" {
        val baseDir = createTempDirectory("test-pm")
        val projectDir = createTempDirectory("test-project")

        try {
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

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir.toFile().deleteRecursively()
        }
    }

    "should handle relative paths" {
        val baseDir = createTempDirectory("test-pm")

        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)
            val projectManager = ProjectManager.getInstance(ideConfigRepository)

            // Relative path (not starting with /)
            val relativePath = "some/relative/path"

            // Should not throw exception
            val resolved = projectManager.resolvePath(relativePath)
            resolved shouldNotBe null

        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    "should generate consistent IDs regardless of tilde usage" {
        val baseDir = createTempDirectory("test-pm")
        val projectDir = createTempDirectory("test-project")

        try {
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

        } finally {
            baseDir.toFile().deleteRecursively()
            projectDir.toFile().deleteRecursively()
        }
    }
})
