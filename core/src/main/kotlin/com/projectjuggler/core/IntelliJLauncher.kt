package com.projectjuggler.core

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.platform.IntelliJLocator
import com.projectjuggler.platform.ProcessLauncher
import java.nio.file.Path
import java.nio.file.Paths

class IntelliJLauncher(
    private val configRepository: ConfigRepository,
) {
    private val directoryManager: DirectoryManager = DirectoryManager.getInstance(configRepository)
    private val baseVMOptionsTracker: BaseVMOptionsTracker = BaseVMOptionsTracker.getInstance(configRepository)
    private val projectManager: ProjectManager = ProjectManager.getInstance(configRepository)

    fun launch(project: ProjectMetadata) {
        // 1. Ensure project directories exist
        val projectDirs = directoryManager.ensureProjectDirectories(project)

        // 2. Get base VM options path (if configured)
        val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()

        // 3. Ensure debug port is allocated (if base VM options contains JDWP)
        val debugPort = projectManager.ensureDebugPort(project)

        // 4. Generate or update VM options file
        val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptionsPath, projectDirs, debugPort)

        // 4. Find IntelliJ executable
        val intellijPath = getIntelliJPath()
            ?: throw IllegalStateException(
                "IntelliJ IDEA not found. Please configure the path using: project-juggler config --intellij-path <path>"
            )

        // 5. Launch IntelliJ with custom VM options
        val environment = mapOf("IDEA_VM_OPTIONS" to vmOptionsFile.toString())
        ProcessLauncher.launch(intellijPath, listOf(project.path.pathString), environment)

        println("Launched IntelliJ IDEA for project: ${project.name}")
        println("Project ID: ${project.id}")
        println("VM options file: $vmOptionsFile")
    }

    fun launchMain(projectPath: Path) {
        // Get IntelliJ executable path
        val intellijPath = getIntelliJPath()
            ?: throw IllegalStateException(
                "IntelliJ IDEA not found. Please configure the path using: project-juggler config --intellij-path <path>"
            )

        // Get base VM options path (if configured)
        val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()

        // Build environment - include IDEA_VM_OPTIONS if base vmoptions is configured
        val environment = if (baseVmOptionsPath != null) {
            mapOf("IDEA_VM_OPTIONS" to baseVmOptionsPath.toString())
        } else {
            emptyMap()
        }

        // Launch IntelliJ with the project path
        ProcessLauncher.launch(intellijPath, listOf(projectPath.toString()), environment)

        // Get project name for display
        val projectName = projectPath.fileName.toString()

        // Print status message
        if (baseVmOptionsPath != null) {
            println("Launched main project: $projectName (using base vmoptions)")
        } else {
            println("Launched main project: $projectName")
        }
    }

    private fun getIntelliJPath(): Path? {
        // First, check if path is configured
        val config = configRepository.load()
        if (config.intellijPath != null) {
            return Paths.get(config.intellijPath)
        }

        // Otherwise, try to auto-detect
        return IntelliJLocator.findIntelliJ()
    }

    companion object {
        fun getInstance(configRepository: ConfigRepository) = IntelliJLauncher(configRepository)
    }
}
