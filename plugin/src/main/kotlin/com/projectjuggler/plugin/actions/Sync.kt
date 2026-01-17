package com.projectjuggler.plugin.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.util.BundledSyncHelper
import com.projectjuggler.process.ProjectLauncher
import kotlin.io.path.Path

/**
 * Determines if the given project is the currently running project.
 * Returns true if syncing this project would require self-shutdown.
 */
fun isCurrentProject(ideConfigRepository: IdeConfigRepository, projectPath: ProjectPath): Boolean {
    val configPathStr = System.getProperty("idea.config.path") ?: return false
    val configPath = Path(configPathStr)

    // Check if this is an isolated project:
    // Path should be: ~/.project-juggler/v2/<ide>/projects/<project-id>/config
    val parts = configPath.toString().split("/").filter { it.isNotEmpty() }
    val projectsIndex = parts.indexOf("projects")

    if (projectsIndex >= 0 && projectsIndex + 2 < parts.size && parts[projectsIndex + 2] == "config") {
        val currentProjectId = parts[projectsIndex + 1]

        // Get the project ID for the target path
        val targetMetadata = ProjectManager.getInstance(ideConfigRepository).get(projectPath)
        return targetMetadata?.id?.id == currentProjectId
    }

    return false
}

/**
 * Common logic for self-shutdown sync operations.
 * Spawns sync-helper process and exits IntelliJ gracefully.
 */
fun performSelfShutdownSync(
    project: Project?,
    notificationMessage: String,
    helperArgs: List<String>,
    syncType: SyncType
) {
    try {
        showInfoNotification(notificationMessage, project)

        // Get bundled sync-helper executable
        val executable = BundledSyncHelper.getExecutable()

        val syncArg = when(syncType) {
            SyncType.All -> "--all"
            SyncType.VmOptions -> "--vmoptions"
            SyncType.Config -> "--config"
            SyncType.Plugins -> "--plugins"
        }

        // Spawn sync-helper process
        ProcessBuilder(executable.toString(), *(helperArgs + syncArg).toTypedArray())
            .inheritIO()
            .start()

        // Wait briefly to ensure sync-helper started
        Thread.sleep(100)

        // Exit IntelliJ
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().exit()
        }
    } catch (e: Exception) {
        showErrorNotification(
            "Failed to initiate self-shutdown sync: ${e.message}",
            project
        )
    }
}

/**
 * Common sync logic with progress indicator.
 * Performs sync for a list of projects with progress tracking and notifications.
 */
fun performSyncWithProgress(
    project: Project?,
    projects: List<ProjectMetadata>,
    syncType: SyncType,
    taskTitle: String,
    successMessage: (List<ProjectMetadata>) -> String,
    errorMessage: (Exception) -> String
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, taskTitle) {
        override fun run(indicator: ProgressIndicator) {
            try {
                indicator.isIndeterminate = projects.size == 1
                val launcher = ProjectLauncher.getInstance(IdeInstallationService.currentIdeConfigRepository)

                val syncOptions = SyncOptions(
                    stopIfRunning = true,
                    autoRestart = true,
                    shutdownTimeout = 60,
                    onProgress = { progress ->
                        when (progress) {
                            is SyncProgress.Stopping -> {
                                indicator.text = "Stopping IntelliJ..."
                            }

                            is SyncProgress.Syncing -> {
                                indicator.text = "Syncing ${syncType.displayName}..."
                            }

                            is SyncProgress.Restarting -> {
                                indicator.text = "Restarting IntelliJ..."
                            }

                            is SyncProgress.Error -> {
                                // Error handled in catch block
                            }
                        }
                    }
                )

                projects.forEachIndexed { index, projectMetadata ->
                    ProgressManager.checkCanceled()
                    indicator.text = ProjectJugglerBundle.message(
                        "progress.sync.project.type",
                        syncType.displayName,
                        projectMetadata.path.name
                    )

                    if (projects.size > 1) {
                        indicator.fraction = index.toDouble() / projects.size
                    }

                    launcher.syncProject(
                        projectMetadata,
                        syncVmOptions = syncType.syncVmOptions,
                        syncConfig = syncType.syncConfig,
                        syncPlugins = syncType.syncPlugins,
                        syncOptions
                    )
                }

                showInfoNotification(successMessage(projects), project)
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                showErrorNotification(errorMessage(e), project)
            }
        }
    })
}