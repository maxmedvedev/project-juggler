package com.ideajuggler.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigRepository(private val baseDir: Path) {
    private val configFile = baseDir.resolve("config.json")
    private val projectsDir = baseDir.resolve("projects")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun load(): GlobalConfig {
        if (!configFile.exists()) {
            return GlobalConfig.default()
        }
        return withFileLock(configFile) {
            json.decodeFromString<GlobalConfig>(configFile.readText())
        }
    }

    fun save(config: GlobalConfig) {
        Files.createDirectories(baseDir)
        withFileLock(configFile) {
            configFile.writeText(json.encodeToString(config))
        }
    }

    fun update(transform: (GlobalConfig) -> GlobalConfig) {
        val config = load()
        val updated = transform(config)
        save(updated)
    }

    fun saveProjectMetadata(projectId: String, metadata: ProjectMetadata) {
        val projectDir = projectsDir.resolve(projectId)
        val metadataFile = projectDir.resolve("metadata.json")
        Files.createDirectories(projectDir)
        withFileLock(metadataFile) {
            metadataFile.writeText(json.encodeToString(metadata))
        }
    }

    fun loadProjectMetadata(projectId: String): ProjectMetadata? {
        val metadataFile = projectsDir.resolve(projectId).resolve("metadata.json")
        if (!metadataFile.exists()) return null
        return withFileLock(metadataFile) {
            json.decodeFromString<ProjectMetadata>(metadataFile.readText())
        }
    }

    fun loadAllProjects(): List<ProjectMetadata> {
        if (!projectsDir.exists()) return emptyList()
        return Files.list(projectsDir).use { stream ->
            stream.toList().mapNotNull { projectDir ->
                val metadataFile = projectDir.resolve("metadata.json")
                if (metadataFile.exists()) {
                    try {
                        json.decodeFromString<ProjectMetadata>(metadataFile.readText())
                    } catch (e: Exception) {
                        null // Skip corrupted metadata
                    }
                } else null
            }
        }
    }

    fun deleteProjectMetadata(projectId: String) {
        val metadataFile = projectsDir.resolve(projectId).resolve("metadata.json")
        if (metadataFile.exists()) {
            Files.delete(metadataFile)
        }
    }

    private fun <T> withFileLock(file: Path, block: () -> T): T {
        // Ensure parent directory exists
        Files.createDirectories(file.parent)

        // Create file if it doesn't exist
        if (!file.exists()) {
            Files.createFile(file)
        }

        return RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.channel.lock().use {
                block()
            }
        }
    }
}
