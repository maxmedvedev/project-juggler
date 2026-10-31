package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import java.nio.file.Path

/**
 * True when [projectPath]'s own IDE instance is the current IDE: the main project in the main instance, or an
 * isolated project whose `<projRoot>/config` is [currentConfigDir]. Creates nothing.
 */
internal fun belongsToCurrentIde(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    currentConfigDir: Path,
): Boolean =
    if (MainProjectService.isMainProject(repository, projectPath)) {
        MainProjectService.isRunningInMainInstance(repository, currentConfigDir)
    } else {
        val isolatedConfig = DirectoryManager.getInstance(repository).getProjectRoot(projectPath).resolve("config")
        isolatedConfig.toAbsolutePath().normalize() == currentConfigDir.toAbsolutePath().normalize()
    }
