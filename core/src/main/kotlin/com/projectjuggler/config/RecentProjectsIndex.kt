package com.projectjuggler.config

import com.projectjuggler.di.getScope
import com.projectjuggler.util.FileLockUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        // Read config.json before taking the recents lock: locks are always taken config first, then recents.
        val maxRecentProjects = ideConfigRepository.load().maxRecentProjects

        FileLockUtils.withFileLock(recentFile) {
            val entries = loadEntries().toMutableList()
            entries.removeIf { it.projectId == projectId }
            entries.add(0, RecentEntry(projectId, java.time.Instant.now().toString()))
            saveEntries(entries.take(maxRecentProjects))
        }
    }

    fun getRecent(limit: Int): List<ProjectMetadata> {
        val entries = if (recentFile.exists()) FileLockUtils.withFileLock(recentFile) { loadEntries() } else emptyList()
        return entries.take(limit).mapNotNull { entry ->
            ideConfigRepository.loadProjectMetadata(entry.projectId)
        }
    }

    fun remove(projectId: ProjectId) {
        FileLockUtils.withFileLock(recentFile) {
            saveEntries(loadEntries().filter { it.projectId != projectId })
        }
    }

    /** Reads the entries; must be called under the `recent.json` lock. */
    private fun loadEntries(): List<RecentEntry> {
        val text = recentFile.readText()
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString(text)
        } catch (e: Throwable) {
            System.err.println("[RecentProjectsIndex] Ignoring corrupt $recentFile: ${e.message}")
            emptyList()
        }
    }

    /** Writes the entries; must be called under the `recent.json` lock. */
    private fun saveEntries(entries: List<RecentEntry>) {
        recentFile.writeText(json.encodeToString(entries))
    }

    companion object {
        fun getInstance(ideConfigRepository: IdeConfigRepository): RecentProjectsIndex =
            ideConfigRepository.getScope().get()
    }
}

@Serializable
private data class RecentEntry(val projectId: ProjectId, val timestamp: String)
