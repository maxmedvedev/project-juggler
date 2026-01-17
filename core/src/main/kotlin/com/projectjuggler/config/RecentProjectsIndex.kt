package com.projectjuggler.config

import com.projectjuggler.di.getScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RecentProjectsIndex internal constructor(
    private val ideConfigRepository: IdeConfigRepository
) {
    private val recentFile = ideConfigRepository.baseDir.resolve("recent.json")

    private val json = Json {
        prettyPrint = true
    }

    fun recordOpen(projectPath: ProjectPath) {
        val projectId = projectPath.id

        val entries = loadEntries().toMutableList()
        entries.removeIf { it.projectId == projectId }
        entries.add(0, RecentEntry(projectId, java.time.Instant.now().toString()))

        val maxRecentProjects = ideConfigRepository.load().maxRecentProjects
        val trimmed = entries.take(maxRecentProjects)
        saveEntries(trimmed)
    }

    fun getRecent(limit: Int): List<ProjectMetadata> {
        val entries = loadEntries().take(limit)
        return entries.mapNotNull { entry ->
            ideConfigRepository.loadProjectMetadata(entry.projectId)
        }
    }

    private fun loadEntries(): List<RecentEntry> {
        if (!recentFile.exists()) return emptyList()
        return try {
            json.decodeFromString(recentFile.readText())
        } catch (e: SerializationException) {
            emptyList() // Return empty list if file is corrupted
        }
    }

    private fun saveEntries(entries: List<RecentEntry>) {
        Files.createDirectories(ideConfigRepository.baseDir)
        recentFile.writeText(json.encodeToString(entries))
    }

    fun remove(projectId: ProjectId) {
        val entries = loadEntries().filter { it.projectId != projectId }
        saveEntries(entries)
    }

    companion object {
        fun getInstance(ideConfigRepository: IdeConfigRepository): RecentProjectsIndex =
            ideConfigRepository.getScope().get()
    }
}

@Serializable
private data class RecentEntry(val projectId: ProjectId, val timestamp: String)
