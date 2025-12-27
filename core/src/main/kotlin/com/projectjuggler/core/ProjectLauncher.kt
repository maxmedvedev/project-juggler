package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex

class ProjectLauncher(
    private val configRepository: ConfigRepository
) {
    private val projectManager = ProjectManager.getInstance(configRepository)
    private val directoryManager = DirectoryManager.getInstance(configRepository)
    private val baseVMOptionsTracker = BaseVMOptionsTracker.getInstance(configRepository)
    private val intellijLauncher = IntelliJLauncher.getInstance(configRepository)
    private val recentProjectsIndex = RecentProjectsIndex.getInstance(configRepository)

    /**
     * Checks if the given project path is the configured main project.
     */
    private fun isMainProject(projectPath: ProjectPath): Boolean {
        val config = configRepository.load()
        val mainProjectPath = config.mainProjectPath ?: return false

        // Resolve main project path to normalized form
        val normalizedMainPath = projectManager.resolvePath(mainProjectPath)

        // Compare normalized path strings
        return projectPath.pathString == normalizedMainPath.pathString
    }

    /**
     * Launch a project by ID and path (for when ID is already known)
     */
    fun launch(
        messageOutput: MessageOutput,
        projectPath: ProjectPath,
    ) {
        // Check if this is the main project
        if (isMainProject(projectPath)) {
            messageOutput.echo("Opening main project: ${projectPath.name}")
            intellijLauncher.launchMain(projectPath.path)
            return
        }

        // For isolated projects: check if base VM options changed
        if (baseVMOptionsTracker.hasChanged()) {
            messageOutput.echo("Note: Base VM options have changed. Use 'project-juggler sync <project>' to update project settings.")
            baseVMOptionsTracker.updateHash()
        }

        // Register or update project metadata
        val project = projectManager.registerOrUpdate(projectPath)

        // Record in recent projects
        recentProjectsIndex.recordOpen(projectPath)

        // Launch IntelliJ with isolated configuration
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
