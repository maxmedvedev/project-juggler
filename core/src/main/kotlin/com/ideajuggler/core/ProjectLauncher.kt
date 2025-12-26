package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import java.nio.file.Path

class ProjectLauncher(
    configRepository: ConfigRepository
) {
    private val projectManager = ProjectManager.getInstance(configRepository)
    private val directoryManager = DirectoryManager.getInstance(configRepository)
    private val baseVMOptionsTracker = BaseVMOptionsTracker.getInstance(configRepository)
    private val intellijLauncher = IntelliJLauncher.getInstance(configRepository)
    private val recentProjectsIndex = RecentProjectsIndex.getInstance(configRepository)

    /**
     * Launch a project by ID and path (for when ID is already known)
     */
    fun launch(
        messageOutput: MessageOutput,
        projectPath: Path,
        projectId: String = ProjectIdGenerator.generate(projectPath)
    ) {
        // Check if base VM options changed
        if (baseVMOptionsTracker.hasChanged()) {
            messageOutput.echo("Note: Base VM options have changed. Use 'idea-juggler sync <project>' to update project settings.")
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
     * Synchronize a project's settings with base settings (vmoptions, config, plugins)
     */
    fun syncProject(projectId: String, syncVmOptions: Boolean, syncConfig: Boolean, syncPlugins: Boolean) {
        val projectDirs = directoryManager.ensureProjectDirectories(projectId)

        if (syncVmOptions) {
            val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()
                ?: throw IllegalStateException("Base VM options path not configured. Configure it using: idea-juggler config --base-vmoptions <path>")

            val debugPort = projectManager.ensureDebugPort(projectId)
            VMOptionsGenerator.generate(
                baseVmOptionsPath,
                projectDirs,
                debugPort,
                forceRegenerate = true
            )
        }

        if (syncConfig) {
            directoryManager.syncConfigFromBase(projectId)
        }

        if (syncPlugins) {
            directoryManager.syncPluginsFromBase(projectId)
        }
    }

    companion object {
        fun getInstance(configRepository: ConfigRepository): ProjectLauncher = ProjectLauncher(configRepository)
    }
}
