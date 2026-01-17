package com.projectjuggler.core

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.IdeRegistry.Companion.PROJECT_JUGGLER_BASE_DIR
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

object VMOptionsGenerator {
    fun generate(
        baseVmOptionsPath: Path?,
        projectDirectories: ProjectDirectories,
        debugPort: Int?,
        forceRegenerate: Boolean = false
    ): Path {
        val vmOptionsFile = projectDirectories.root.resolve("idea.vmoptions")

        // If file exists and we're not forcing regeneration, only update the override section
        if (Files.exists(vmOptionsFile) && !forceRegenerate) {
            updateOverrides(vmOptionsFile, projectDirectories)
            return vmOptionsFile
        }

        // Ensure root directory exists
        Files.createDirectories(projectDirectories.root)

        // Generate from scratch
        val content = buildString {
            // Include base VM options if provided
            if (baseVmOptionsPath != null && Files.exists(baseVmOptionsPath)) {
                appendLine("# Base VM options from: $baseVmOptionsPath")
                val baseOptions = filterBaseOptions(baseVmOptionsPath.readLines(), debugPort)
                baseOptions.forEach { line ->
                    appendLine(line)
                }
                appendLine()
            }

            // Add project-juggler overrides
            appendLine("# project-juggler overrides (auto-generated)")
            appendLine("-Didea.config.path=${projectDirectories.config}")
            appendLine("-Didea.system.path=${projectDirectories.system}")
            appendLine("-Didea.log.path=${projectDirectories.logs}")
            appendLine("-Didea.plugins.path=${projectDirectories.plugins}")
            appendLine("-D$PROJECT_JUGGLER_BASE_DIR=${IdeRegistry.getDefaultBaseDir()}")
        }

        vmOptionsFile.writeText(content)
        return vmOptionsFile
    }

    private fun updateOverrides(vmOptionsFile: Path, projectDirectories: ProjectDirectories) {
        val lines = vmOptionsFile.readLines().toMutableList()
        var overrideStartIndex = -1

        // Find the start of the override section
        for (i in lines.indices) {
            if (lines[i].trim() == "# project-juggler overrides (auto-generated)") {
                overrideStartIndex = i
                break
            }
        }

        if (overrideStartIndex == -1) {
            // Override section doesn't exist, append it
            lines.add("")
            lines.add("# project-juggler overrides (auto-generated)")
            lines.add("-Didea.config.path=${projectDirectories.config}")
            lines.add("-Didea.system.path=${projectDirectories.system}")
            lines.add("-Didea.log.path=${projectDirectories.logs}")
            lines.add("-Didea.plugins.path=${projectDirectories.plugins}")
        } else {
            // Update the override section
            val newOverrides = listOf(
                "# project-juggler overrides (auto-generated)",
                "-Didea.config.path=${projectDirectories.config}",
                "-Didea.system.path=${projectDirectories.system}",
                "-Didea.log.path=${projectDirectories.logs}",
                "-Didea.plugins.path=${projectDirectories.plugins}"
            )

            // Remove old override lines
            var endIndex = overrideStartIndex + 1
            while (endIndex < lines.size && lines[endIndex].trim().startsWith("-Didea.")) {
                endIndex++
            }

            // Replace with new overrides
            lines.subList(overrideStartIndex, endIndex).clear()
            lines.addAll(overrideStartIndex, newOverrides)
        }

        vmOptionsFile.writeText(lines.joinToString("\n") + "\n")
    }

    private fun filterBaseOptions(lines: List<String>, debugPort: Int?): List<String> {
        // Filter out any existing idea.*.path properties to avoid duplicates
        return lines
            .filter { line -> filterPathSettings(line) }
            .map { line -> replaceJdwpPort(line, debugPort) } // Replace JDWP port if debug port is allocated
    }

    private fun filterPathSettings(line: String): Boolean {
        val trimmed = line.trim()
        return !trimmed.startsWith("-Didea.config.path=") &&
                !trimmed.startsWith("-Didea.system.path=") &&
                !trimmed.startsWith("-Didea.log.path=") &&
                !trimmed.startsWith("-Didea.plugins.path=")
    }

    private fun replaceJdwpPort(line: String, newPort: Int?): String {
        if (newPort == null) {
            return line
        }

        // Match JDWP option line
        if (!line.trim().startsWith("-agentlib:jdwp=")) {
            return line
        }

        // Replace port in various formats:
        // address=*:PORT, address=PORT, address=host:PORT
        val jdwpPattern = Regex("""(address=)([*\w.-]*:)?(\d+)""")
        return jdwpPattern.replace(line) { matchResult ->
            val prefix = matchResult.groupValues[1]  // "address="
            val host = matchResult.groupValues[2]     // "*:", "localhost:", or empty
            "${prefix}${host}${newPort}"
        }
    }
}
