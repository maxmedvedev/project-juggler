package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.VMOptionsGenerator
import com.projectjuggler.di.getScope
import java.nio.file.Path
import java.nio.file.Paths

class IntelliJLauncher internal constructor(
    private val ideConfigRepository: IdeConfigRepository
) {

    fun launch(project: ProjectMetadata) {
        // 1. Ensure project directories exist
        val projectDirs = DirectoryManager.getInstance(ideConfigRepository).ensureProjectDirectories(project)

        // 2. Get base VM options path (if configured)
        val baseVmOptionsPath = BaseVMOptionsTracker.getInstance(ideConfigRepository).getBaseVmOptionsPath()

        // 3. Ensure debug port is allocated (if base VM options contains JDWP)
        val debugPort = ProjectManager.getInstance(ideConfigRepository).ensureDebugPort(project)

        // 4. Generate or update VM options file
        val vmOptionsFile = VMOptionsGenerator.generate(baseVmOptionsPath, projectDirs, debugPort)

        // 4. Find IntelliJ executable
        // For IdeConfigRepository, IDE path comes from the installation
        val intellijPath = Paths.get(ideConfigRepository.installation.executablePath)
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
        // For IdeConfigRepository, IDE path comes from the installation
        val intellijPath = Paths.get(ideConfigRepository.installation.executablePath)
            ?: throw IllegalStateException(
                "IntelliJ IDEA not found. Please configure the path using: project-juggler config --intellij-path <path>"
            )

        // Get base VM options path (if configured)
        val baseVmOptionsPath = BaseVMOptionsTracker.getInstance(ideConfigRepository).getBaseVmOptionsPath()

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

    companion object {
        fun getInstance(ideConfigRepository: IdeConfigRepository): IntelliJLauncher =
            ideConfigRepository.getScope().get()
    }
}