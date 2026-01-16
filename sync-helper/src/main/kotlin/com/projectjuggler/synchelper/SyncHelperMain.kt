package com.projectjuggler.synchelper

import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SyncOptions

/**
 * Minimal sync helper for self-shutdown sync operations.
 * Called by the plugin when syncing the currently running IDE.
 *
 * Usage: sync-helper --path <project-path> [--all|--vmoptions|--config|--plugins]
 *    or: sync-helper --all-projects [--all|--vmoptions|--config|--plugins]
 */
fun main(args: Array<String>) {
    val argMap = parseArgs(args)

    val allProjects = "all-projects" in argMap
    val pathString = argMap["path"]

    if (!allProjects && pathString == null) {
        error("Either --path or --all-projects is required")
    }
    if (allProjects && pathString != null) {
        error("Cannot specify both --path and --all-projects")
    }

    val syncAll = "all" in argMap
    val syncVmOptions = syncAll || "vmoptions" in argMap
    val syncConfig = syncAll || "config" in argMap
    val syncPlugins = syncAll || "plugins" in argMap

    val configRepository = ConfigRepository.create()
    val projectLauncher = ProjectLauncher.getInstance(configRepository)

    val syncOptions = SyncOptions(
        stopIfRunning = true,
        autoRestart = true,
        shutdownTimeout = 60
    )

    val projects: List<ProjectMetadata> = if (allProjects) {
        configRepository.loadAllProjects().also {
            if (it.isEmpty()) error("No tracked projects found")
        }
    } else {
        val projectManager = ProjectManager.getInstance(configRepository)
        val projectPath = projectManager.resolvePath(pathString!!)
        val project = projectManager.get(projectPath)
            ?: error("Project not found: $pathString")
        listOf(project)
    }

    for (project in projects) {
        projectLauncher.syncProject(
            project,
            syncVmOptions,
            syncConfig,
            syncPlugins,
            syncOptions
        )
    }
}

private fun parseArgs(args: Array<String>): Map<String, String?> {
    val result = mutableMapOf<String, String?>()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--path" -> if (i + 1 < args.size) result["path"] = args[++i]
            "--all-projects" -> result["all-projects"] = null
            "--all" -> result["all"] = null
            "--vmoptions" -> result["vmoptions"] = null
            "--config" -> result["config"] = null
            "--plugins" -> result["plugins"] = null
        }
        i++
    }
    return result
}
