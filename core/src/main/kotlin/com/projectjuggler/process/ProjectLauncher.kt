package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.core.ShutdownResult
import com.projectjuggler.core.ShutdownSignalManager
import com.projectjuggler.core.ShutdownWaiter
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.core.VMOptionsGenerator
import com.projectjuggler.util.ProjectLockUtils

class ProjectLauncher private constructor(
    private val ideConfigRepository: IdeConfigRepository,
) {
    private val projectManager = ProjectManager.getInstance(ideConfigRepository)
    private val directoryManager = DirectoryManager.getInstance(ideConfigRepository)
    private val baseVMOptionsTracker = BaseVMOptionsTracker.getInstance(ideConfigRepository)
    private val intellijLauncher = IntelliJLauncher.getInstance(ideConfigRepository)
    private val recentProjectsIndex = RecentProjectsIndex.getInstance(ideConfigRepository)

    /**
     * Checks if the given project path is the configured main project.
     */
    private fun isMainProject(projectPath: ProjectPath): Boolean {
        val config = ideConfigRepository.load()
        val mainProjectPath = config.mainProjectPath ?: return false

        // Resolve main project path to normalized form
        val normalizedMainPath = projectManager.resolvePath(mainProjectPath)

        // Compare normalized path strings
        return projectPath.pathString == normalizedMainPath.pathString
    }

    /**
     * Launch a project by ID and path (for when ID is already known)
     */
    fun launch(projectPath: ProjectPath) {
        // Check if this is the main project
        if (isMainProject(projectPath)) {
            intellijLauncher.launchMain(projectPath.path)
            return
        }

        // For isolated projects: check if base VM options changed
        if (baseVMOptionsTracker.hasChanged()) {
            baseVMOptionsTracker.updateHash()
        }

        // Register or update project metadata
        val project = projectManager.registerOrUpdate(projectPath)

        // Record in recent projects
        recentProjectsIndex.recordOpen(projectPath)

        // Launch IntelliJ with isolated configuration
        intellijLauncher.launch(project)
    }

    /**
     * Synchronize a project's settings with base settings (vmoptions, config, plugins)
     *
     * @param project Project to sync
     * @param syncVmOptions Whether to sync VM options
     * @param syncConfig Whether to sync config directory
     * @param syncPlugins Whether to sync plugins directory
     * @param options Sync options (stop/restart behavior, timeout, progress callbacks)
     * @throws SyncException if sync fails or times out
     */
    fun syncProject(
        project: ProjectMetadata,
        syncVmOptions: Boolean,
        syncConfig: Boolean,
        syncPlugins: Boolean,
        options: SyncOptions = SyncOptions.DEFAULT
    ) {
        doWithLock(project, options) {
            doSync(project, syncVmOptions, syncConfig, syncPlugins)
        }
    }

    private fun doWithLock(
        project: ProjectMetadata,
        options: SyncOptions = SyncOptions.DEFAULT,
        block: () -> Unit
    ) {
        val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)

        // Try to acquire sync lock to prevent concurrent syncs
        val syncLock = signalManager.acquireSyncLock(project)
            ?: throw SyncException("Sync already in progress for project '${project.name}'. Please wait for it to complete.")

        try {
            // Check if project is currently running
            val wasRunning = ProjectLockUtils.isProjectOpen(ideConfigRepository, project.path)

            // If running and should stop, request shutdown and wait
            if (wasRunning && options.stopIfRunning) {
                requestShutdownAndWait(project, options, signalManager)
            }

            // Perform sync operations
            options.onProgress(SyncProgress.Syncing)

            block()

            // Auto-restart if requested and was running
            if (wasRunning && options.stopIfRunning && options.autoRestart) {
                options.onProgress(SyncProgress.Restarting)
                try {
                    intellijLauncher.launch(project)
                } catch (e: Exception) {
                    // Log warning but don't fail the sync
                    options.onProgress(SyncProgress.Error("Failed to restart IntelliJ: ${e.message}"))
                }
            }
        } finally {
            // Cleanup signal files and release lock
            signalManager.cleanup(project)
            syncLock.close()
        }
    }

    private fun doSync(
        project: ProjectMetadata,
        syncVmOptions: Boolean,
        syncConfig: Boolean,
        syncPlugins: Boolean
    ) {
        val projectDirs = directoryManager.ensureProjectDirectories(project)

        if (syncVmOptions) {
            val baseVmOptionsPath = baseVMOptionsTracker.getBaseVmOptionsPath()
                ?: throw IllegalStateException("Base VM options path not configured. Configure it using: project-juggler config --base-vmoptions <path>")

            val debugPort = projectManager.ensureDebugPort(project)
            VMOptionsGenerator.generate(
                baseVmOptionsPath,
                projectDirs,
                debugPort,
                forceRegenerate = true
            )
        }

        if (syncConfig) {
            directoryManager.syncConfigFromBase(project)
        }

        if (syncPlugins) {
            directoryManager.syncPluginsFromBase(project)
        }
    }

    /**
     * Requests shutdown of a running IntelliJ instance and waits for it to complete.
     */
    private fun requestShutdownAndWait(
        project: ProjectMetadata,
        options: SyncOptions,
        signalManager: ShutdownSignalManager
    ) {
        // Determine what's being synced for diagnostic purposes
        val syncTypes = mutableListOf<String>()
        // Note: We don't have access to the boolean flags here
        // For now, just use a generic message
        syncTypes.add("settings")

        // Create stop request signal
        signalManager.createStopRequest(
            project = project,
            autoRestart = options.autoRestart,
            syncTypes = syncTypes
        )

        // Wait for shutdown
        val result = ShutdownWaiter.waitForShutdown(
            ideConfigRepository = ideConfigRepository,
            projectPath = project.path,
            timeoutSeconds = options.shutdownTimeout
        ) { secondsElapsed ->
            options.onProgress(SyncProgress.Stopping(secondsElapsed))
        }

        when (result) {
            is ShutdownResult.Success -> {
                // Give a small delay to ensure file handles are released
                Thread.sleep(500)
            }
            is ShutdownResult.Timeout -> {
                throw SyncException(
                    """
                    IntelliJ did not shut down within ${options.shutdownTimeout} seconds for project '${project.name}'.

                    This may happen if:
                    - You have unsaved changes and cancelled the shutdown dialog
                    - IntelliJ is not responding
                    - The shutdown is taking longer than expected

                    Options:
                    - Save your changes and close IntelliJ manually, then retry
                    - Use a longer timeout value
                    - Run sync without stop/restart (may cause file conflicts)
                    """.trimIndent()
                )
            }
        }
    }

    companion object {
        fun getInstance(ideConfigRepository: IdeConfigRepository): ProjectLauncher =
            ProjectLauncher(ideConfigRepository)
    }
}

/**
 * Exception thrown when sync operation fails.
 */
class SyncException(message: String) : Exception(message)