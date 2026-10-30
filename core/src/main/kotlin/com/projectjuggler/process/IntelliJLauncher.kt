package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.VMOptionsGenerator
import com.projectjuggler.di.getScope
import java.nio.file.Path
import java.nio.file.Paths

class IntelliJLauncher internal constructor(
    private val ideConfigRepository: IdeConfigRepository,
    private val processLauncher: ProcessLauncher
) {

    fun launch(project: ProjectMetadata, onFailure: ((LaunchFailure) -> Unit)? = null) {
        // 1. Ensure project directories exist
        val projectDirs = DirectoryManager.getInstance(ideConfigRepository).ensureProjectDirectories(project)

        // 2. Get base VM options path (if configured)
        val baseVmOptionsPath = BaseVMOptionsTracker.getInstance(ideConfigRepository).getBaseVmOptionsPath()

        // 3. Ensure debug port is allocated (only if base VM options contains JDWP)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val debugPort = projectManager.debugPortFor(project, baseVmOptionsPath)

        // 4. Generate or update VM options file; regenerate fully if the base changed since the last write
        val baseHash = VMOptionsGenerator.computeBaseHash(baseVmOptionsPath)
        val baseChanged = baseHash != project.baseVmOptionsHash
        val vmOptionsFile =
            VMOptionsGenerator.generate(baseVmOptionsPath, projectDirs, debugPort, forceRegenerate = baseChanged)
        if (baseChanged) projectManager.updateBaseVmOptionsHash(project, baseHash)

        // 5. Find IntelliJ executable
        // For IdeConfigRepository, IDE path comes from the installation
        val intellijPath = Paths.get(ideConfigRepository.installation.executablePath)

        // 6. Launch IntelliJ with custom VM options
        val environment = mapOf("IDEA_VM_OPTIONS" to vmOptionsFile.toString())
        processLauncher.launch(intellijPath, listOf(project.path.pathString), environment, onFailure)

        println("Launched IntelliJ IDEA for project: ${project.name}")
        println("Project ID: ${project.id}")
        println("VM options file: $vmOptionsFile")
    }

    fun launchMain(projectPath: Path, onFailure: ((LaunchFailure) -> Unit)? = null) {
        // Get IntelliJ executable path
        // For IdeConfigRepository, IDE path comes from the installation
        val intellijPath = Paths.get(ideConfigRepository.installation.executablePath)

        // Get base VM options path (if configured)
        val baseVmOptionsPath = BaseVMOptionsTracker.getInstance(ideConfigRepository).getBaseVmOptionsPath()

        // Build environment - include IDEA_VM_OPTIONS if base vmoptions is configured
        val environment = if (baseVmOptionsPath != null) {
            mapOf("IDEA_VM_OPTIONS" to baseVmOptionsPath.toString())
        } else {
            emptyMap()
        }

        // Launch IntelliJ with the project path
        processLauncher.launch(intellijPath, listOf(projectPath.toString()), environment, onFailure)

        // Get project name for display
        val projectName = ProjectPath(projectPath.toString()).name

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