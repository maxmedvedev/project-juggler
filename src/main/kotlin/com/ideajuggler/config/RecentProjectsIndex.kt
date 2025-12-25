package com.ideajuggler.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RecentProjectsIndex(private val baseDir: Path) {
    private val recentFile = baseDir.resolve("recent.json")
    private val configRepository = ConfigRepository(baseDir)

    private val json = Json {
        prettyPrint = true
    }

    @Serializable
    data class RecentEntry(val projectId: String, val timestamp: String)

    fun recordOpen(projectId: String) {
        val entries = loadEntries().toMutableList()
        entries.removeIf { it.projectId == projectId }
        entries.add(0, RecentEntry(projectId, java.time.Instant.now().toString()))

        val config = configRepository.load()
        val trimmed = entries.take(config.maxRecentProjects)
        saveEntries(trimmed)
    }

    fun getRecent(limit: Int): List<ProjectMetadata> {
        val entries = loadEntries().take(limit)
        return entries.mapNotNull { entry ->
            configRepository.loadProjectMetadata(entry.projectId)
        }
    }

    private fun loadEntries(): List<RecentEntry> {
        if (!recentFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<RecentEntry>>(recentFile.readText())
        } catch (e: Exception) {
            emptyList() // Return empty list if file is corrupted
        }
    }

    private fun saveEntries(entries: List<RecentEntry>) {
        Files.createDirectories(baseDir)
        recentFile.writeText(json.encodeToString(entries))
    }

    fun remove(projectId: String) {
        val entries = loadEntries().filter { it.projectId != projectId }
        saveEntries(entries)
    }
}
