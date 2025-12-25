package com.ideajuggler.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class VMOptionsGenerator {

    data class ProjectDirectories(
        val root: Path,
        val config: Path,
        val system: Path,
        val logs: Path,
        val plugins: Path
    )

    fun generate(baseVmOptionsPath: Path?, projectDirectories: ProjectDirectories): Path {
        val vmOptionsFile = projectDirectories.root.resolve("idea.vmoptions")

        val content = buildString {
            // Include base VM options if provided
            if (baseVmOptionsPath != null && Files.exists(baseVmOptionsPath)) {
                appendLine("# Base VM options from: ${baseVmOptionsPath}")
                val baseOptions = filterBaseOptions(baseVmOptionsPath.readLines())
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

    private fun filterBaseOptions(lines: List<String>): List<String> {
        // Filter out any existing idea.*.path properties to avoid duplicates
        return lines.filter { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("-Didea.config.path=") &&
            !trimmed.startsWith("-Didea.system.path=") &&
            !trimmed.startsWith("-Didea.log.path=") &&
            !trimmed.startsWith("-Didea.plugins.path=")
        }
    }
}
