package com.ideajuggler.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.config.RecentProjectsIndex
import com.ideajuggler.core.*
import com.ideajuggler.platform.IntelliJLocator
import com.ideajuggler.platform.ProcessLauncher
import java.nio.file.Paths
import kotlin.io.path.exists

class OpenCommand : CliktCommand(
    name = "open",
    help = "Open a project with dedicated IntelliJ instance"
) {
    private val projectPath by argument(help = "Path to project directory")
        .path(mustExist = true, canBeFile = false, canBeDir = true)

    override fun run() {
        val baseDir = Paths.get(System.getProperty("user.home"), ".idea-juggler")

        // Initialize dependencies
        val configRepository = ConfigRepository(baseDir)
        val projectIdGenerator = ProjectIdGenerator()
        val projectManager = ProjectManager(configRepository, projectIdGenerator)
        val directoryManager = DirectoryManager(baseDir)
        val baseVMOptionsTracker = BaseVMOptionsTracker(configRepository)
        val vmOptionsGenerator = VMOptionsGenerator()
        val intellijLocator = IntelliJLocator()
        val processLauncher = ProcessLauncher()
        val intellijLauncher = IntelliJLauncher(
            configRepository,
            directoryManager,
            vmOptionsGenerator,
            baseVMOptionsTracker,
            intellijLocator,
            processLauncher
        )
        val recentProjectsIndex = RecentProjectsIndex(baseDir)

        // Generate project ID
        val projectId = projectIdGenerator.generate(projectPath)

        // Check if base VM options changed
        if (baseVMOptionsTracker.hasChanged()) {
            echo("Base VM options changed, regenerating configurations for all projects...")
            regenerateAllProjects(
                configRepository,
                projectManager,
                directoryManager,
                baseVMOptionsTracker,
                vmOptionsGenerator
            )
            baseVMOptionsTracker.updateHash()
        }

        // Register or update project metadata
        projectManager.registerOrUpdate(projectId, projectPath)

        // Record in recent projects
        recentProjectsIndex.recordOpen(projectId)

        // Launch IntelliJ
        intellijLauncher.launch(projectId, projectPath)
    }

    private fun regenerateAllProjects(
        configRepository: ConfigRepository,
        projectManager: ProjectManager,
        directoryManager: DirectoryManager,
        baseVMOptionsTracker: BaseVMOptionsTracker,
        vmOptionsGenerator: VMOptionsGenerator
    ) {
        val projects = projectManager.listAll()
        val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()

        projects.forEach { project ->
            val projectDirs = directoryManager.ensureProjectDirectories(project.id)
            vmOptionsGenerator.generate(
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
}
