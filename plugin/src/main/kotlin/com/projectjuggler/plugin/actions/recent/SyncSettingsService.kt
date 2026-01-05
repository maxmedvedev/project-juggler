package com.projectjuggler.plugin.actions.recent

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner.project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportProgressScope
import com.intellij.util.application
import com.projectjuggler.config.ConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.ProjectLauncher
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import com.projectjuggler.plugin.util.BundledCliManager
import kotlinx.coroutines.*
import kotlin.io.path.Path

@Service(Service.Level.APP)
internal class SyncSettingsService(
    private val scope: CoroutineScope
) {

    companion object {
        fun getInstance(): SyncSettingsService {
            return application.service<SyncSettingsService>()
        }
    }

    private val configRepository = ConfigRepository.create()

    fun syncAllProjects(project: Project?, syncType: SyncType) {
        scope.launch {
            syncAllProjectsWithTypeImpl(project, syncType)
        }
    }

    fun syncProject(project: Project?, projectPath: ProjectPath, syncType: SyncType) {
        scope.launch {
            syncSingleProjectWithTypeImpl(projectPath, syncType)
        }
    }

    private suspend fun syncAllProjectsWithTypeImpl(project: Project?, syncType: SyncType) {
        if (isSyncingAllIncludingMe()) {
            handleSelfShutdownSyncAll(syncType)
            return
        }

        val allProjects = configRepository.loadAllProjects()
        performSyncWithProgress(
            project = project,
            projects = allProjects,
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message("progress.sync.all.projects.type", syncType.displayName),
            successMessage = {
                ProjectJugglerBundle.message(
                    "notification.success.sync.all.projects.type",
                    syncType.displayName,
                    it.size
                )
            },
            errorMessage = { e ->
                ProjectJugglerBundle.message("notification.error.sync.projects.failed", e.message ?: "")
            }
        )
    }

    private fun isSyncingAllIncludingMe(): Boolean {
        // Check if current project is in the list (self-shutdown case)
        val allProjects = configRepository.loadAllProjects()
        val currentProjectInList = allProjects.any { isCurrentProject(configRepository, it.path) }
        return currentProjectInList
    }

    /**
     * Handles syncing all projects when current project is in the list.
     * Spawns CLI with --all-projects and shuts down.
     */
    private suspend fun handleSelfShutdownSyncAll(syncType: SyncType) {
        performSelfShutdownSync(
            notificationMessage = "IntelliJ will close to sync all projects and reopen automatically...",
            cliArgs = listOf("sync", "--all-projects"),
            syncType = syncType
        )
    }

    private suspend fun syncSingleProjectWithTypeImpl(projectPath: ProjectPath, syncType: SyncType) {
        // Check if syncing current project (self-shutdown case)
        if (isCurrentProject(configRepository, projectPath)) {
            handleSelfShutdownSync(projectPath, syncType)
            return
        }

        val metadata = ProjectManager.getInstance(configRepository).get(projectPath) ?: return
        performSyncWithProgress(
            project = project,
            projects = listOf(metadata),
            syncType = syncType,
            taskTitle = ProjectJugglerBundle.message(
                "progress.sync.project.type",
                syncType.displayName,
                projectPath.name
            ),
            successMessage = { projects ->
                ProjectJugglerBundle.message(
                    "notification.success.sync.single.project.type",
                    syncType.displayName,
                    projects.first().path.name
                )
            },
            errorMessage = { e ->
                ProjectJugglerBundle.message("notification.error.sync.settings.failed", e.message ?: "")
            }
        )
    }

    /**
     * Common sync logic with progress indicator.
     * Performs sync for a list of projects with progress tracking and notifications.
     */
    private suspend fun performSyncWithProgress(
        project: Project,
        projects: List<ProjectMetadata>,
        syncType: SyncType,
        taskTitle: String,
        successMessage: (List<ProjectMetadata>) -> String,
        errorMessage: (Exception) -> String
    ) {
        withBackgroundProgress(project, taskTitle) {
            reportProgressScope(projects.size) { reporter ->
                try {
                    val launcher = ProjectLauncher(configRepository)

                    projects.forEach { projectMetadata ->
                        val stepText = ProjectJugglerBundle.message(
                            "progress.sync.project.type",
                            syncType.displayName,
                            projectMetadata.path.name
                        )

                        reporter.itemStep(stepText) {
                            val syncOptions = SyncOptions(
                                stopIfRunning = true,
                                autoRestart = true,
                                shutdownTimeout = 60,
                                onProgress = { _ ->
                                    // Progress updates from syncProject are handled by the reporter
                                }
                            )

                            launcher.syncProject(
                                projectMetadata,
                                syncVmOptions = syncType.syncVmOptions,
                                syncConfig = syncType.syncConfig,
                                syncPlugins = syncType.syncPlugins,
                                syncOptions
                            )
                        }
                    }

                    showInfoNotification(successMessage(projects), project)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    showErrorNotification(errorMessage(e), project)
                }
            }
        }
    }

    /**
     * Handles syncing the current project by spawning CLI and shutting down.
     */
    private suspend fun handleSelfShutdownSync(projectPath: ProjectPath, syncType: SyncType) {
        performSelfShutdownSync(
            notificationMessage = "IntelliJ will close to sync ${syncType.displayName} and reopen automatically...",
            cliArgs = listOf("sync", "--path", projectPath.pathString),
            syncType = syncType
        )
    }

    /**
     * Common logic for self-shutdown sync operations.
     * Spawns CLI process and exits IntelliJ gracefully.
     */
    private suspend fun performSelfShutdownSync(
        notificationMessage: String,
        cliArgs: List<String>,
        syncType: SyncType
    ) = withContext(Dispatchers.IO) {
        try {
            showInfoNotification(notificationMessage, project)

            // Get bundled CLI executable
            val cliExecutable = BundledCliManager.getCliExecutable()

            val arg = when (syncType) {
                SyncType.All -> "--all"
                SyncType.VmOptions -> "--vmoptions"
                SyncType.Config -> "--config"
                SyncType.Plugins -> "--plugins"
            }

            // Spawn CLI process
            ProcessBuilder(cliExecutable.toString(), *(cliArgs + arg).toTypedArray())
                .inheritIO()
                .start()

            // Wait briefly to ensure CLI started
            delay(100)

            // Exit IntelliJ
            withContext(Dispatchers.EDT) {
                application.exit()
            }
        } catch (e: Exception) {
            showErrorNotification(
                "Failed to initiate self-shutdown sync: ${e.message}",
                project
            )
        }
    }

    /**
     * Determines if the given project is the currently running project.
     * Returns true if syncing this project would require self-shutdown.
     */
    private fun isCurrentProject(configRepository: ConfigRepository, projectPath: ProjectPath): Boolean {
        val configPathStr = System.getProperty("idea.config.path") ?: return false
        val configPath = Path(configPathStr)

        // Check if this is an isolated project:
        // Path should be: ~/.project-juggler/projects/<project-id>/config
        val parts = configPath.toString().split("/").filter { it.isNotEmpty() }
        val projectsIndex = parts.indexOf("projects")

        if (projectsIndex >= 0 && projectsIndex + 2 < parts.size && parts[projectsIndex + 2] == "config") {
            val currentProjectId = parts[projectsIndex + 1]

            // Get the project ID for the target path
            val targetMetadata = ProjectManager.getInstance(configRepository).get(projectPath)
            return targetMetadata?.id?.id == currentProjectId
        }

        return false
    }
}