package com.projectjuggler.synchelper

import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.di.KoinInit
import com.projectjuggler.process.ProjectLauncher

/**
 * Minimal sync helper for self-shutdown sync operations.
 * Called by the plugin when syncing the currently running IDE.
 *
 * Usage: sync-helper --ide <ide-path> --path <project-path> [--all|--vmoptions|--config|--plugins]
 *    or: sync-helper --ide <ide-path> --all-projects [--all|--vmoptions|--config|--plugins]
 */
fun main(args: Array<String>) {
    // Initialize Koin DI
    KoinInit.init()

    val argMap = parseArgs(args)

    val idePath = argMap["ide"] ?: error("--ide <ide-path> is required")
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

    val ideRegistry = IdeRegistry.getInstance()
    val installation = IdeInstallation(idePath, "IDE")
    val ideConfigRepository = ideRegistry.getRepository(installation)
    val projectLauncher = ProjectLauncher.getInstance(ideConfigRepository)

    val syncOptions = SyncOptions(
        stopIfRunning = true,
        autoRestart = true,
        shutdownTimeout = 60
    )

    val projects: List<ProjectMetadata> = if (allProjects) {
        ideConfigRepository.loadAllProjects().also {
            if (it.isEmpty()) error("No tracked projects found")
        }
    } else {
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
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
            "--ide" -> if (i + 1 < args.size) result["ide"] = args[++i]
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
