package com.ideajuggler.core

import com.ideajuggler.config.ConfigRepository
import com.ideajuggler.platform.IntelliJLocator
import com.ideajuggler.platform.ProcessLauncher
import java.nio.file.Path
import java.nio.file.Paths

class IntelliJLauncher(
    private val configRepository: ConfigRepository,
) {
    private val directoryManager: DirectoryManager = DirectoryManager.getInstance(configRepository)
    private val baseVMOptionsTracker: BaseVMOptionsTracker = BaseVMOptionsTracker.getInstance(configRepository)
    private val projectManager: ProjectManager = ProjectManager.getInstance(configRepository)

    fun launch(projectId: String, projectPath: Path) {
        // 1. Ensure project directories exist
        val projectDirs = directoryManager.ensureProjectDirectories(projectId)

        // 2. Get base VM options path (if configured)
        val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()

        // 3. Ensure debug port is allocated (if base VM options contains JDWP)
        val debugPort = projectManager.ensureDebugPort(projectId)

        // 4. Generate or update VM options file
        val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptionsPath, projectDirs, debugPort)

        // 4. Find IntelliJ executable
        val intellijPath = getIntelliJPath()
            ?: throw IllegalStateException(
                "IntelliJ IDEA not found. Please configure the path using: idea-juggler config --intellij-path <path>"
            )

        // 5. Launch IntelliJ with custom VM options
        val environment = mapOf("IDEA_VM_OPTIONS" to vmOptionsFile.toString())
        ProcessLauncher.launch(intellijPath, listOf(projectPath.toString()), environment)

        println("Launched IntelliJ IDEA for project: ${projectPath.fileName}")
        println("Project ID: $projectId")
        println("VM options file: $vmOptionsFile")
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
