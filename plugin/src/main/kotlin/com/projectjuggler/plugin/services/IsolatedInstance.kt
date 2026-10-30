package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.ProjectManager
import kotlin.io.path.Path

/**
 * Detects whether this IDE runs as an isolated Project Juggler instance, from the `idea.config.path` property
 * that `IntelliJLauncher` points at `<ideDir>/projects/<id>/config`.
 */
internal object IsolatedInstance {
    const val CONFIG_PATH_PROPERTY = "idea.config.path"

    /** `.../projects/<id>/config` -> id; null otherwise. Splits on '/' exactly like today (plugin-spec gap 2). */
    fun projectIdFromConfigPath(configPath: String): ProjectId? {
        val parts = Path(configPath).toString().split("/").filter { it.isNotEmpty() }
        val projectsIndex = parts.indexOf("projects")
        if (projectsIndex < 0 || projectsIndex + 2 >= parts.size || parts[projectsIndex + 2] != "config") {
            return null
        }
        return ProjectId(parts[projectsIndex + 1])
    }

    /** The project this isolated instance runs, or null in the main instance (no isolated config path). */
    fun currentProjectId(): ProjectId? = System.getProperty(CONFIG_PATH_PROPERTY)?.let(::projectIdFromConfigPath)

    /**
     * True when [projectPath] is the project this instance runs, i.e. when syncing it would stop this IDE.
     * False when not isolated or when the project has no metadata.
     */
    fun isCurrentProject(
        repository: IdeConfigRepository,
        projectPath: ProjectPath,
        currentProjectId: ProjectId? = currentProjectId(),
    ): Boolean {
        val currentId = currentProjectId ?: return false
        return ProjectManager.getInstance(repository).get(projectPath)?.id == currentId
    }
}
