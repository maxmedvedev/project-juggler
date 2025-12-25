package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.ProjectMetadata
import java.nio.file.Path
import java.time.Instant

class ProjectManager(
    private val configRepository: ConfigRepository,
    private val projectIdGenerator: ProjectIdGenerator
) {

    fun registerOrUpdate(projectId: String, projectPath: Path): ProjectMetadata {
        val existing = configRepository.loadProjectMetadata(projectId)

        val metadata = ProjectMetadata(
            id = projectId,
            path = projectPath.toString(),
            name = projectPath.fileName.toString(),
            lastOpened = Instant.now().toString(),
            openCount = (existing?.openCount ?: 0) + 1
        )

        configRepository.saveProjectMetadata(projectId, metadata)
        return metadata
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
        val projectId = projectIdGenerator.generate(projectPath)
        return configRepository.loadProjectMetadata(projectId)
    }
}
