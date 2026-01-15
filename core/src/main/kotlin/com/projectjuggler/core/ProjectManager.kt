package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.util.PathUtils
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

class ProjectManager private constructor(
    private val ideConfigRepository: IdeConfigRepository,
) {

    fun registerOrUpdate(projectPath: ProjectPath): ProjectMetadata {
        val existing = ideConfigRepository.loadProjectMetadata(projectPath)

        val metadata = ProjectMetadata(
            path = projectPath,
            lastOpened = Instant.now().toString(),
            openCount = (existing?.openCount ?: 0) + 1,
            debugPort = existing?.debugPort  // Preserve existing debug port
        )

        ideConfigRepository.saveProjectMetadata(metadata)
        return metadata
    }

    /**
     * Ensures the project has a debug port allocated.
     * If the project already has a port, returns it.
     * If not, allocates a new port and updates the project metadata.
     * Returns null if all ports are exhausted.
     */
    fun ensureDebugPort(project: ProjectMetadata): Int? {
        // If already has a port, return it
        if (project.debugPort != null) {
            return project.debugPort
        }

        // Otherwise, allocate a new port
        val portAllocator = PortAllocator(this)
        val newPort = portAllocator.allocatePort() ?: return null

        // Update metadata with new port
        val updated = project.copy(debugPort = newPort)
        ideConfigRepository.saveProjectMetadata(updated)

        return newPort
    }

    fun listAll(): List<ProjectMetadata> {
        return ideConfigRepository.loadAllProjects().sortedByDescending { it.lastOpened }
    }

    fun remove(projectId: ProjectPath) {
        ideConfigRepository.deleteProjectMetadata(projectId)
    }

    // todo inline
    fun get(projectId: ProjectPath): ProjectMetadata? {
        return ideConfigRepository.loadProjectMetadata(projectId)
    }

    /**
     * Resolves a raw path string by expanding tildes and converting to Path.
     * This is the centralized location for all path expansion logic.
     *
     * @param rawPath The raw path string (may contain tildes, relative paths)
     * @return The resolved Path object with tilde expansion applied
     */
    fun resolvePath(rawPath: String): ProjectPath {
        val path = Path.of(rawPath)
        val resolved = PathUtils.expandTilde(path)

        // Normalize to absolute canonical path for consistent ID generation
        val normalized = resolved.toAbsolutePath().normalize()

        return ProjectPath(normalized.toString())
    }

    /**
     * Validates that a raw path string resolves to an existing filesystem path.
     *
     * @param rawPath The raw path string (may contain tildes, relative paths)
     * @return true if the resolved path exists in the filesystem, false otherwise
     */
    fun validatePathExists(rawPath: String): Boolean {
        val resolvedPath = resolvePath(rawPath)
        return resolvedPath.path.exists()
    }

    companion object {
        fun getInstance(ideConfigRepository: IdeConfigRepository) = ProjectManager(ideConfigRepository)

    }
}
