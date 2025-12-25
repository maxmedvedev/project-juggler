package com.ideajuggler.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VMOptionsGeneratorTest : StringSpec({
    val generator = VMOptionsGenerator()

    "should generate VM options file without base file" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = generator.generate(null, projectDirs)

            Files.exists(vmOptionsFile) shouldBe true
            val content = vmOptionsFile.readText()

            content shouldContain "-Didea.config.path=${projectDirs.config}"
            content shouldContain "-Didea.system.path=${projectDirs.system}"
            content shouldContain "-Didea.log.path=${projectDirs.logs}"
            content shouldContain "-Didea.plugins.path=${projectDirs.plugins}"
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should include base VM options when provided" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("""
                -Xms256m
                -Xmx2048m
                -XX:ReservedCodeCacheSize=512m
            """.trimIndent())

            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = generator.generate(baseVmOptions, projectDirs)
            val content = vmOptionsFile.readText()

            // Should contain base options
            content shouldContain "-Xms256m"
            content shouldContain "-Xmx2048m"
            content shouldContain "-XX:ReservedCodeCacheSize=512m"

            // Should contain project-specific overrides
            content shouldContain "-Didea.config.path=${projectDirs.config}"
            content shouldContain "-Didea.system.path=${projectDirs.system}"
            content shouldContain "-Didea.log.path=${projectDirs.logs}"
            content shouldContain "-Didea.plugins.path=${projectDirs.plugins}"
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should filter out existing idea path properties from base file" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("""
                -Xms256m
                -Didea.config.path=/old/config
                -Didea.system.path=/old/system
                -Xmx2048m
                -Didea.log.path=/old/logs
                -Didea.plugins.path=/old/plugins
            """.trimIndent())

            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = generator.generate(baseVmOptions, projectDirs)
            val content = vmOptionsFile.readText()

            // Should NOT contain old paths
            content shouldNotContain "/old/config"
            content shouldNotContain "/old/system"
            content shouldNotContain "/old/logs"
            content shouldNotContain "/old/plugins"

            // Should contain new paths
            content shouldContain "-Didea.config.path=${projectDirs.config}"
            content shouldContain "-Didea.system.path=${projectDirs.system}"
            content shouldContain "-Didea.log.path=${projectDirs.logs}"
            content shouldContain "-Didea.plugins.path=${projectDirs.plugins}"

            // Should still contain other options
            content shouldContain "-Xms256m"
            content shouldContain "-Xmx2048m"
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should create VM options file in project root" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = generator.generate(null, projectDirs)

            vmOptionsFile.parent shouldBe tempDir
            vmOptionsFile.fileName.toString() shouldBe "idea.vmoptions"
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should overwrite existing VM options file" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            // Generate first time
            val vmOptionsFile1 = generator.generate(null, projectDirs)
            val content1 = vmOptionsFile1.readText()

            // Generate second time with different paths
            val newProjectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("new-config"),
                system = tempDir.resolve("new-system"),
                logs = tempDir.resolve("new-logs"),
                plugins = tempDir.resolve("new-plugins")
            )
            val vmOptionsFile2 = generator.generate(null, newProjectDirs)
            val content2 = vmOptionsFile2.readText()

            // Files should be the same, but content different
            vmOptionsFile1 shouldBe vmOptionsFile2
            content1 shouldNotBe content2

            // New content should have new paths
            content2 shouldContain "new-config"
            content2 shouldContain "new-system"
            content2 shouldContain "new-logs"
            content2 shouldContain "new-plugins"
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should handle empty base VM options file" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            // Empty file
            baseVmOptions.writeText("")

            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = generator.generate(baseVmOptions, projectDirs)
            val content = vmOptionsFile.readText()

            // Should still have project-specific overrides
            content shouldContain "-Didea.config.path="
            content shouldContain "-Didea.system.path="
            content shouldContain "-Didea.log.path="
            content shouldContain "-Didea.plugins.path="
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should preserve comments and blank lines from base file" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("""
                # This is a comment
                -Xms256m

                # Another comment
                -Xmx2048m
            """.trimIndent())

            val projectDirs = VMOptionsGenerator.ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = generator.generate(baseVmOptions, projectDirs)
            val content = vmOptionsFile.readText()

            content shouldContain "# This is a comment"
            content shouldContain "# Another comment"
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }
})
