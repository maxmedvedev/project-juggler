package com.ideajuggler.core

import java.nio.file.Files
import java.nio.file.Path

class DirectoryManager(private val baseDir: Path) {

    data class ProjectDirectories(
        val root: Path,
        val config: Path,
        val system: Path,
        val logs: Path,
        val plugins: Path
    )

    fun ensureProjectDirectories(projectId: String): ProjectDirectories {
        val root = baseDir.resolve("projects").resolve(projectId)

        val directories = ProjectDirectories(
            root = root,
            config = root.resolve("config"),
            system = root.resolve("system"),
            logs = root.resolve("logs"),
            plugins = root.resolve("plugins")
        )

        // Create all directories
        Files.createDirectories(directories.config)
        Files.createDirectories(directories.system)
        Files.createDirectories(directories.logs)
        Files.createDirectories(directories.plugins)

        return directories
    }

    fun cleanProject(projectId: String) {
        val projectRoot = baseDir.resolve("projects").resolve(projectId)
        if (Files.exists(projectRoot)) {
            projectRoot.toFile().deleteRecursively()
        }
    }

    fun getProjectRoot(projectId: String): Path {
        return baseDir.resolve("projects").resolve(projectId)
    }
}
