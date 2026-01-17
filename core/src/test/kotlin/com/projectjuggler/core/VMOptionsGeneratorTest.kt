package com.projectjuggler.core

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.test.createTempDir
import com.projectjuggler.test.createTempFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VMOptionsGeneratorTest : StringSpec({
    val tempDir = createTempDir("test-project")
    val baseVmOptions = createTempFile("base", ".vmoptions")

    "should generate VM options file without base file" {
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t1"),
            config = tempDir.resolve("t1/config"),
            system = tempDir.resolve("t1/system"),
            logs = tempDir.resolve("t1/logs"),
            plugins = tempDir.resolve("t1/plugins")
        )

        val vmOptionsFile = VMOptionsGenerator.generate(null, projectDirs, null)

        Files.exists(vmOptionsFile) shouldBe true
        val content = vmOptionsFile.readText()

        content shouldContain "-Didea.config.path=${projectDirs.config}"
        content shouldContain "-Didea.system.path=${projectDirs.system}"
        content shouldContain "-Didea.log.path=${projectDirs.logs}"
        content shouldContain "-Didea.plugins.path=${projectDirs.plugins}"
    }

    "should include base VM options when provided" {
        baseVmOptions.writeText("""
            -Xms256m
            -Xmx2048m
            -XX:ReservedCodeCacheSize=512m
        """.trimIndent())

        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t2"),
            config = tempDir.resolve("t2/config"),
            system = tempDir.resolve("t2/system"),
            logs = tempDir.resolve("t2/logs"),
            plugins = tempDir.resolve("t2/plugins")
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
    }

    "should filter out existing idea path properties from base file" {
        val vmOptions3 = createTempFile("base3", ".vmoptions")
        vmOptions3.writeText("""
            -Xms256m
            -Didea.config.path=/old/config
            -Didea.system.path=/old/system
            -Xmx2048m
            -Didea.log.path=/old/logs
            -Didea.plugins.path=/old/plugins
        """.trimIndent())

        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t3"),
            config = tempDir.resolve("t3/config"),
            system = tempDir.resolve("t3/system"),
            logs = tempDir.resolve("t3/logs"),
            plugins = tempDir.resolve("t3/plugins")
        )

        val vmOptionsFile = VMOptionsGenerator.generate(vmOptions3, projectDirs, null)
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
    }

    "should create VM options file in project root" {
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t4"),
            config = tempDir.resolve("t4/config"),
            system = tempDir.resolve("t4/system"),
            logs = tempDir.resolve("t4/logs"),
            plugins = tempDir.resolve("t4/plugins")
        )

        val vmOptionsFile = VMOptionsGenerator.generate(null, projectDirs, null)

        vmOptionsFile.parent shouldBe tempDir.resolve("t4")
        vmOptionsFile.fileName.toString() shouldBe "idea.vmoptions"
    }

    "should overwrite existing VM options file" {
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t5"),
            config = tempDir.resolve("t5/config"),
            system = tempDir.resolve("t5/system"),
            logs = tempDir.resolve("t5/logs"),
            plugins = tempDir.resolve("t5/plugins")
        )

        // Generate first time
        val vmOptionsFile1 = VMOptionsGenerator.generate(null, projectDirs, null)
        val content1 = vmOptionsFile1.readText()

        // Generate second time with different paths
        val newProjectDirs = ProjectDirectories(
            root = tempDir.resolve("t5"),
            config = tempDir.resolve("t5/new-config"),
            system = tempDir.resolve("t5/new-system"),
            logs = tempDir.resolve("t5/new-logs"),
            plugins = tempDir.resolve("t5/new-plugins")
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
    }

    "should handle empty base VM options file" {
        val vmOptions6 = createTempFile("base6", ".vmoptions")
        vmOptions6.writeText("")

        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t6"),
            config = tempDir.resolve("t6/config"),
            system = tempDir.resolve("t6/system"),
            logs = tempDir.resolve("t6/logs"),
            plugins = tempDir.resolve("t6/plugins")
        )

        val vmOptionsFile = VMOptionsGenerator.generate(vmOptions6, projectDirs, null)
        val content = vmOptionsFile.readText()

        // Should still have project-specific overrides
        content shouldContain "-Didea.config.path="
        content shouldContain "-Didea.system.path="
        content shouldContain "-Didea.log.path="
        content shouldContain "-Didea.plugins.path="
    }

    "should preserve comments and blank lines from base file" {
        val vmOptions7 = createTempFile("base7", ".vmoptions")
        vmOptions7.writeText("""
            # This is a comment
            -Xms256m

            # Another comment
            -Xmx2048m
        """.trimIndent())

        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t7"),
            config = tempDir.resolve("t7/config"),
            system = tempDir.resolve("t7/system"),
            logs = tempDir.resolve("t7/logs"),
            plugins = tempDir.resolve("t7/plugins")
        )

        val vmOptionsFile = VMOptionsGenerator.generate(vmOptions7, projectDirs, null)
        val content = vmOptionsFile.readText()

        content shouldContain "# This is a comment"
        content shouldContain "# Another comment"
    }

    "should replace JDWP port with unique port based on project ID" {
        val tempDir1 = createTempDir("test-project-1")
        val tempDir2 = createTempDir("test-project-2")
        val vmOptions8 = createTempFile("base8", ".vmoptions")

        vmOptions8.writeText("""
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

        val vmFile1 = VMOptionsGenerator.generate(vmOptions8, projectDirs1, debugPort1)
        val vmFile2 = VMOptionsGenerator.generate(vmOptions8, projectDirs2, debugPort2)

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
    }

    "should handle JDWP with different address formats" {
        val vmOptions9 = createTempFile("base9", ".vmoptions")
        val debugPort = 5100
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t9"),
            config = tempDir.resolve("t9/config"),
            system = tempDir.resolve("t9/system"),
            logs = tempDir.resolve("t9/logs"),
            plugins = tempDir.resolve("t9/plugins")
        )

        // Test address=*:PORT format
        vmOptions9.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009")
        val content1 = VMOptionsGenerator.generate(vmOptions9, projectDirs, debugPort, forceRegenerate = true).readText()
        content1 shouldContain "address=*:5100"
        content1 shouldNotContain "address=*:5009"

        // Test address=PORT format
        vmOptions9.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5009")
        val content2 = VMOptionsGenerator.generate(vmOptions9, projectDirs, debugPort, forceRegenerate = true).readText()
        content2 shouldContain "address=5100"
        content2 shouldNotContain "address=5009"

        // Test address=localhost:PORT format
        vmOptions9.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5009")
        val content3 = VMOptionsGenerator.generate(vmOptions9, projectDirs, debugPort, forceRegenerate = true).readText()
        content3 shouldContain "address=localhost:5100"
        content3 shouldNotContain "address=localhost:5009"

        // Test address=127.0.0.1:PORT format
        vmOptions9.writeText("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5009")
        val content4 = VMOptionsGenerator.generate(vmOptions9, projectDirs, debugPort, forceRegenerate = true).readText()
        content4 shouldContain "address=127.0.0.1:5100"
        content4 shouldNotContain "address=127.0.0.1:5009"
    }

    "should produce stable ports for same project ID" {
        val vmOptions10 = createTempFile("base10", ".vmoptions")
        vmOptions10.writeText("""
            -Xms256m
            -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009
        """.trimIndent())

        val debugPort = 5200
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t10"),
            config = tempDir.resolve("t10/config"),
            system = tempDir.resolve("t10/system"),
            logs = tempDir.resolve("t10/logs"),
            plugins = tempDir.resolve("t10/plugins")
        )

        // Generate twice with same debug port
        val vmFile1 = VMOptionsGenerator.generate(vmOptions10, projectDirs, debugPort)
        val vmFile2 = VMOptionsGenerator.generate(vmOptions10, projectDirs, debugPort)

        val content1 = vmFile1.readText()
        val content2 = vmFile2.readText()

        // Ports should be identical
        val port1 = extractPort(content1)
        val port2 = extractPort(content2)
        port1 shouldBe 5200
        port2 shouldBe 5200
        port1 shouldBe port2
    }

    "should not modify non-JDWP options when JDWP is present" {
        val vmOptions11 = createTempFile("base11", ".vmoptions")
        vmOptions11.writeText("""
            -Xms256m
            -Xmx2048m
            -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5009
            -XX:ReservedCodeCacheSize=512m
            # This is a comment with address=5009 in it
        """.trimIndent())

        val debugPort = 5300
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t11"),
            config = tempDir.resolve("t11/config"),
            system = tempDir.resolve("t11/system"),
            logs = tempDir.resolve("t11/logs"),
            plugins = tempDir.resolve("t11/plugins")
        )

        val vmFile = VMOptionsGenerator.generate(vmOptions11, projectDirs, debugPort)
        val content = vmFile.readText()

        // All non-JDWP options should remain unchanged
        content shouldContain "-Xms256m"
        content shouldContain "-Xmx2048m"
        content shouldContain "-XX:ReservedCodeCacheSize=512m"
        content shouldContain "# This is a comment with address=5009 in it"
    }

    "should pass custom project juggler base dir to spawned instances via VM options" {
        val customBaseDir = tempDir.resolve("custom-pj-base")
        Files.createDirectories(customBaseDir)

        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t12"),
            config = tempDir.resolve("t12/config"),
            system = tempDir.resolve("t12/system"),
            logs = tempDir.resolve("t12/logs"),
            plugins = tempDir.resolve("t12/plugins")
        )

        // Set custom base dir via system property
        val originalValue = System.getProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
        try {
            System.setProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR, customBaseDir.toString())

            val vmFile = VMOptionsGenerator.generate(null, projectDirs, null)
            val content = vmFile.readText()

            // Should contain the custom base dir in project.juggler.base.dir property
            content shouldContain "-D${IdeRegistry.PROJECT_JUGGLER_BASE_DIR}=$customBaseDir"
        } finally {
            // Restore original value
            if (originalValue != null) {
                System.setProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR, originalValue)
            } else {
                System.clearProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
            }
        }
    }

    "should use default base dir when no custom dir is set" {
        val projectDirs = ProjectDirectories(
            root = tempDir.resolve("t13"),
            config = tempDir.resolve("t13/config"),
            system = tempDir.resolve("t13/system"),
            logs = tempDir.resolve("t13/logs"),
            plugins = tempDir.resolve("t13/plugins")
        )

        // Ensure no custom base dir is set
        val originalValue = System.getProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)
        try {
            System.clearProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR)

            val vmFile = VMOptionsGenerator.generate(null, projectDirs, null)
            val content = vmFile.readText()

            // Should contain the default base dir (user.home/.project-juggler)
            val expectedDefaultDir = System.getProperty("user.home") + "/.project-juggler"
            content shouldContain "-D${IdeRegistry.PROJECT_JUGGLER_BASE_DIR}=$expectedDefaultDir"
        } finally {
            // Restore original value
            if (originalValue != null) {
                System.setProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR, originalValue)
            }
        }
    }
})

private fun extractPort(content: String): Int {
    val regex = Regex("""address=[^:]*:?(\d+)""")
    val match = regex.find(content) ?: error("No JDWP port found in content")
    return match.groupValues[1].toInt()
}
