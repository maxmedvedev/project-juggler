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
    "should generate VM options file without base file" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = VMOptionsGenerator.generate(null, projectDirs, null)

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

            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptions, projectDirs, null)
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

            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptions, projectDirs, null)
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
            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = VMOptionsGenerator.generate(null, projectDirs, null)

            vmOptionsFile.parent shouldBe tempDir
            vmOptionsFile.fileName.toString() shouldBe "idea.vmoptions"
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should overwrite existing VM options file" {
        val tempDir = createTempDirectory("test-project")
        try {
            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            // Generate first time
            val vmOptionsFile1 = VMOptionsGenerator.generate(null, projectDirs, null)
            val content1 = vmOptionsFile1.readText()

            // Generate second time with different paths
            val newProjectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("new-config"),
                system = tempDir.resolve("new-system"),
                logs = tempDir.resolve("new-logs"),
                plugins = tempDir.resolve("new-plugins")
            )
            val vmOptionsFile2 = VMOptionsGenerator.generate(null, newProjectDirs, null)
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

            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptions, projectDirs, null)
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

            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptions, projectDirs, null)
            val content = vmOptionsFile.readText()

            content shouldContain "# This is a comment"
            content shouldContain "# Another comment"
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should replace JDWP port with unique port based on project ID" {
        val tempDir1 = createTempDirectory("test-project-1")
        val tempDir2 = createTempDirectory("test-project-2")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("""
                -Xms256m
                -Xmx2048m
                -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009
            """.trimIndent())

            val debugPort1 = 5001
            val debugPort2 = 5002

            val projectDirs1 = ProjectDirectories(
                root = tempDir1,
                config = tempDir1.resolve("config"),
                system = tempDir1.resolve("system"),
                logs = tempDir1.resolve("logs"),
                plugins = tempDir1.resolve("plugins")
            )

            val projectDirs2 = ProjectDirectories(
                root = tempDir2,
                config = tempDir2.resolve("config"),
                system = tempDir2.resolve("system"),
                logs = tempDir2.resolve("logs"),
                plugins = tempDir2.resolve("plugins")
            )

            val vmFile1 = VMOptionsGenerator.generate(baseVmOptions, projectDirs1, debugPort1)
            val vmFile2 = VMOptionsGenerator.generate(baseVmOptions, projectDirs2, debugPort2)

            val content1 = vmFile1.readText()
            val content2 = vmFile2.readText()

            // Both should have JDWP enabled
            content1 shouldContain "-agentlib:jdwp="
            content2 shouldContain "-agentlib:jdwp="

            // Ports should be replaced with allocated ports
            val port1 = extractPort(content1)
            val port2 = extractPort(content2)
            port1 shouldBe 5001
            port2 shouldBe 5002
            port1 shouldNotBe 5009  // Should not be the original port
            port2 shouldNotBe 5009
        } finally {
            tempDir1.toFile().deleteRecursively()
            tempDir2.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should handle JDWP with different address formats" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            val debugPort = 5100
            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            // Test address=*:PORT format
            baseVmOptions.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009")
            val content1 = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort).readText()
            content1 shouldContain "address=*:5100"
            content1 shouldNotContain "address=*:5009"

            // Test address=PORT format
            baseVmOptions.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5009")
            val content2 = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort).readText()
            content2 shouldContain "address=5100"
            content2 shouldNotContain "address=5009"

            // Test address=localhost:PORT format
            baseVmOptions.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5009")
            val content3 = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort).readText()
            content3 shouldContain "address=localhost:5100"
            content3 shouldNotContain "address=localhost:5009"

            // Test address=127.0.0.1:PORT format
            baseVmOptions.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5009")
            val content4 = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort).readText()
            content4 shouldContain "address=127.0.0.1:5100"
            content4 shouldNotContain "address=127.0.0.1:5009"
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should produce stable ports for same project ID" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("""
                -Xms256m
                -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009
            """.trimIndent())

            val debugPort = 5200
            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            // Generate twice with same debug port
            val vmFile1 = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort)
            val vmFile2 = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort)

            val content1 = vmFile1.readText()
            val content2 = vmFile2.readText()

            // Ports should be identical
            val port1 = extractPort(content1)
            val port2 = extractPort(content2)
            port1 shouldBe 5200
            port2 shouldBe 5200
            port1 shouldBe port2
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }

    "should not modify non-JDWP options when JDWP is present" {
        val tempDir = createTempDirectory("test-project")
        val baseVmOptions = createTempFile("base", ".vmoptions")

        try {
            baseVmOptions.writeText("""
                -Xms256m
                -Xmx2048m
                -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009
                -XX:ReservedCodeCacheSize=512m
                # This is a comment with address=5009 in it
            """.trimIndent())

            val debugPort = 5300
            val projectDirs = ProjectDirectories(
                root = tempDir,
                config = tempDir.resolve("config"),
                system = tempDir.resolve("system"),
                logs = tempDir.resolve("logs"),
                plugins = tempDir.resolve("plugins")
            )

            val vmFile = VMOptionsGenerator.generate(baseVmOptions, projectDirs, debugPort)
            val content = vmFile.readText()

            // All non-JDWP options should remain unchanged
            content shouldContain "-Xms256m"
            content shouldContain "-Xmx2048m"
            content shouldContain "-XX:ReservedCodeCacheSize=512m"
            content shouldContain "# This is a comment with address=5009 in it"
        } finally {
            tempDir.toFile().deleteRecursively()
            Files.deleteIfExists(baseVmOptions)
        }
    }
})

private fun extractPort(content: String): Int {
    val regex = Regex("""address=[^:]*:?(\d+)""")
    val match = regex.find(content) ?: error("No JDWP port found in content")
    return match.groupValues[1].toInt()
}
