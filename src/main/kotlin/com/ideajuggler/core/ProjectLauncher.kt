package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.platform.IntelliJLocator
import com.ideajuggler.platform.ProcessLauncher
import java.nio.file.Path
import java.nio.file.Paths

class ProjectLauncher(
    private val baseDir: Path
) {
    private val configRepository = ConfigRepository(baseDir)
    private val projectManager = ProjectManager(configRepository)
    private val directoryManager = DirectoryManager(baseDir)
    private val baseVMOptionsTracker = BaseVMOptionsTracker(configRepository)
    private val intellijLocator = IntelliJLocator
    private val processLauncher = ProcessLauncher()
    private val intellijLauncher = IntelliJLauncher(
        configRepository,
        directoryManager,
        baseVMOptionsTracker,
        processLauncher
    )
    private val recentProjectsIndex = RecentProjectsIndex(baseDir)

    /**
     * Launch a project by path, handling base VM options changes and project registration
     */
    fun launchByPath(projectPath: Path, onBaseVmOptionsChanged: () -> Unit = {}) {
        val projectId = ProjectIdGenerator.generate(projectPath)

        // Check if base VM options changed
        if (baseVMOptionsTracker.hasChanged()) {
            onBaseVmOptionsChanged()
            regenerateAllProjects()
            baseVMOptionsTracker.updateHash()
        }

        // Register or update project metadata
        projectManager.registerOrUpdate(projectId, projectPath)

        // Record in recent projects
        recentProjectsIndex.recordOpen(projectId)

        // Launch IntelliJ
        intellijLauncher.launch(projectId, projectPath)
    }

    /**
     * Launch a project by ID and path (for when ID is already known)
     */
    fun launchById(projectId: String, projectPath: Path) {
        // Register or update project metadata
        projectManager.registerOrUpdate(projectId, projectPath)

        // Record in recent projects
        recentProjectsIndex.recordOpen(projectId)

        // Launch IntelliJ
        intellijLauncher.launch(projectId, projectPath)
    }

    private fun regenerateAllProjects() {
        val projects = projectManager.listAll()
        val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()

        projects.forEach { project ->
            val projectDirs = directoryManager.ensureProjectDirectories(project.id)
            VMOptionsGenerator.generate(
                baseVmOptionsPath,
                VMOptionsGenerator.ProjectDirectories(
                    root = projectDirs.root,
                    config = projectDirs.config,
                    system = projectDirs.system,
                    logs = projectDirs.logs,
                    plugins = projectDirs.plugins
                )
            )
        }
    }

    companion object {
        fun create(): ProjectLauncher {
            val baseDir = Paths.get(System.getProperty("user.home"), ".idea-juggler")
            return ProjectLauncher(baseDir)
        }
    }
}
