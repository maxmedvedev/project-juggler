package com.projectjuggler.plugin.services

import com.intellij.ide.impl.ProjectUtil
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.util.ProjectLockUtils
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

/** Whether a project is open in the current IDE, as the platform sees it. */
internal fun isOpenInCurrentIde(projectPath: ProjectPath): Boolean = ProjectUtil.findProject(projectPath.path) != null

/**
 * Open state for display: the platform's view ([openInCurrentIde]) for the current IDE's own projects, where the
 * `.lock` is the current IDE's own and says nothing about the project; the `.lock` for projects of other IDEs.
 * For the main project seen from an isolated IDE this means "the main IDE is running".
 */
internal fun isProjectRunning(
    repository: IdeConfigRepository,
    projectPath: ProjectPath,
    currentConfigDir: Path,
    openInCurrentIde: (ProjectPath) -> Boolean = ::isOpenInCurrentIde,
): Boolean =
    if (belongsToCurrentIde(repository, projectPath, currentConfigDir)) openInCurrentIde(projectPath)
    else ProjectLockUtils.isProjectOpen(repository, projectPath)
