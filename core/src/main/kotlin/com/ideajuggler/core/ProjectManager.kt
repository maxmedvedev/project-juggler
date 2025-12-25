package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectMetadata
import java.nio.file.Path
import java.time.Instant

class ProjectManager(
    private val configRepository: ConfigRepository,
) {

    fun registerOrUpdate(projectId: String, projectPath: Path): ProjectMetadata {
        val existing = configRepository.loadProjectMetadata(projectId)

        val metadata = ProjectMetadata(
            id = projectId,
            path = projectPath.toString(),
            name = projectPath.fileName.toString(),
            lastOpened = Instant.now().toString(),
            openCount = (existing?.openCount ?: 0) + 1,
            debugPort = existing?.debugPort  // Preserve existing debug port
        )

        configRepository.saveProjectMetadata(projectId, metadata)
        return metadata
    }

    /**
     * Ensures the project has a debug port allocated.
     * If the project already has a port, returns it.
     * If not, allocates a new port and updates the project metadata.
     * Returns null if all ports are exhausted.
     */
    fun ensureDebugPort(projectId: String): Int? {
        val existing = configRepository.loadProjectMetadata(projectId) ?: return null

        // If already has a port, return it
        if (existing.debugPort != null) {
            return existing.debugPort
        }

        // Otherwise, allocate a new port
        val portAllocator = PortAllocator(this)
        val newPort = portAllocator.allocatePort() ?: return null

        // Update metadata with new port
        val updated = existing.copy(debugPort = newPort)
        configRepository.saveProjectMetadata(projectId, updated)

        return newPort
    }

    fun listAll(): List<ProjectMetadata> {
        return configRepository.loadAllProjects().sortedByDescending { it.lastOpened }
    }

    fun remove(projectId: String) {
        configRepository.deleteProjectMetadata(projectId)
    }

    fun get(projectId: String): ProjectMetadata? {
        return configRepository.loadProjectMetadata(projectId)
    }

    fun findByPath(projectPath: Path): ProjectMetadata? {
        val projectId = ProjectIdGenerator.generate(projectPath)
        return configRepository.loadProjectMetadata(projectId)
    }

    companion object {
        fun getInstance(configRepository: ConfigRepository) = ProjectManager(configRepository)
    }
}
