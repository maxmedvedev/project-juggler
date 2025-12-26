package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex

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
        projectPath: ProjectPath,
    ) {
        // Check if base VM options changed
        if (baseVMOptionsTracker.hasChanged()) {
            messageOutput.echo("Note: Base VM options have changed. Use 'project-juggler sync <project>' to update project settings.")
            baseVMOptionsTracker.updateHash()
        }

        // Register or update project metadata
        val project = projectManager.registerOrUpdate(projectPath)

        // Record in recent projects
        recentProjectsIndex.recordOpen(projectPath)

        // Launch IntelliJ
        intellijLauncher.launch(project)
    }

    /**
     * Synchronize a project's settings with base settings (vmoptions, config, plugins)
     */
    fun syncProject(project: ProjectMetadata, syncVmOptions: Boolean, syncConfig: Boolean, syncPlugins: Boolean) {
        val projectDirs = directoryManager.ensureProjectDirectories(project)

        if (syncVmOptions) {
            val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()
                ?: throw IllegalStateException("Base VM options path not configured. Configure it using: project-juggler config --base-vmoptions <path>")

            val debugPort = projectManager.ensureDebugPort(project)
            VMOptionsGenerator.generate(
                baseVmOptionsPath,
                projectDirs,
                debugPort,
                forceRegenerate = true
            )
        }

        if (syncConfig) {
            directoryManager.syncConfigFromBase(project)
        }

        if (syncPlugins) {
            directoryManager.syncPluginsFromBase(project)
        }
    }

    companion object {
        fun getInstance(configRepository: ConfigRepository): ProjectLauncher = ProjectLauncher(configRepository)
    }
}
