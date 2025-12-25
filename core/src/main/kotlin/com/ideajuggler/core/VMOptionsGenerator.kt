package com.ideajuggler.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

object VMOptionsGenerator {
    fun generate(
        baseVmOptionsPath: Path?,
        projectDirectories: ProjectDirectories,
        debugPort: Int?
    ): Path {
        val vmOptionsFile = projectDirectories.root.resolve("idea.vmoptions")

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

            // Add idea-juggler overrides
            appendLine("# idea-juggler overrides (auto-generated)")
            appendLine("-Didea.config.path=${projectDirectories.config}")
            appendLine("-Didea.system.path=${projectDirectories.system}")
            appendLine("-Didea.log.path=${projectDirectories.logs}")
            appendLine("-Didea.plugins.path=${projectDirectories.plugins}")
        }

        vmOptionsFile.writeText(content)
        return vmOptionsFile
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
