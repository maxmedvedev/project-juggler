package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
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
import com.projectjuggler.core.SyncTypeNames
import com.projectjuggler.core.VMOptionsGenerator
import com.projectjuggler.di.getScope
import com.projectjuggler.util.ProjectLockUtils

class ProjectLauncher internal constructor(
    private val ideConfigRepository: IdeConfigRepository,
    private val projectManager: ProjectManager = ProjectManager.getInstance(ideConfigRepository),
    private val directoryManager: DirectoryManager = DirectoryManager.getInstance(ideConfigRepository),
    private val baseVMOptionsTracker: BaseVMOptionsTracker = BaseVMOptionsTracker.getInstance(ideConfigRepository),
    private val intellijLauncher: IntelliJLauncher = IntelliJLauncher.getInstance(ideConfigRepository),
    private val recentProjectsIndex: RecentProjectsIndex = RecentProjectsIndex.getInstance(ideConfigRepository),
) {

    /**
     * Launch a project by ID and path (for when ID is already known)
     *
     * @param projectPath The project to launch
     * @param onFailure Optional callback invoked if the process fails shortly after start
     */
    fun launch(projectPath: ProjectPath, onFailure: ((LaunchFailure) -> Unit)? = null) {
        if (MainProjectService.isMainProject(ideConfigRepository, projectPath)) {
            intellijLauncher.launchMain(projectPath.path, onFailure)
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
        intellijLauncher.launch(project, onFailure)
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
        if (MainProjectService.isMainProject(ideConfigRepository, project.path)) {
            throw SyncException(
                "'${project.name}' is the main project and uses base settings directly; there is nothing to sync."
            )
        }

        val syncTypes = SyncTypeNames.of(syncVmOptions, syncConfig, syncPlugins)

        doWithLock(project, options, syncTypes) {
            doSync(project, syncVmOptions, syncConfig, syncPlugins)
        }
    }

    private fun doWithLock(
        project: ProjectMetadata,
        options: SyncOptions,
        syncTypes: List<String>,
        block: () -> Unit
    ) {
        val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)

        // Try to acquire sync lock to prevent concurrent syncs
        val syncLock = signalManager.acquireSyncLock(project)
            ?: throw SyncException("Sync already in progress for project '${project.name}'. Please wait for it to complete.")

        try {
            // Check if project is currently running
            val wasRunning = ProjectLockUtils.isProjectOpen(ideConfigRepository, project.path)

            // If running and should stop, request shutdown and wait. Stays false on a timeout: the IDE still runs.
            var stopped = false
            if (wasRunning && options.stopIfRunning) {
                requestShutdownAndWait(project, options, syncTypes, signalManager)
                stopped = true
            }

            try {
                // Perform sync operations
                options.onProgress(SyncProgress.Syncing)

                block()
            } finally {
                // Relaunch the IDE we stopped whatever the sync outcome; a block() exception propagates afterwards
                if (stopped && options.autoRestart) {
                    restart(project, options)
                }
            }
        } finally {
            // Cleanup signal files and release lock
            signalManager.cleanup(project)
            syncLock.close()
        }
    }

    /**
     * Relaunches [project] after a sync stopped it. Failures are reported through [SyncOptions.onProgress] as
     * [SyncProgress.Error] and never thrown, so they do not hide the sync outcome.
     */
    private fun restart(project: ProjectMetadata, options: SyncOptions) {
        options.onProgress(SyncProgress.Restarting)
        try {
            // May run on the process watch thread after syncProject has returned
            launch(project.path) { failure ->
                val output = failure.describe()
                options.onProgress(SyncProgress.Error(
                    "IntelliJ exited with code ${failure.exitCode} after restart of '${project.name}'" +
                        if (output.isBlank()) "" else ": $output"
                ))
            }
        } catch (e: Throwable) {
            options.onProgress(SyncProgress.Error("Failed to restart IntelliJ: ${e.message}"))
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

            val debugPort = projectManager.debugPortFor(project, baseVmOptionsPath)
            VMOptionsGenerator.generate(
                baseVmOptionsPath,
                projectDirs,
                debugPort,
                forceRegenerate = true
            )
            projectManager.updateBaseVmOptionsHash(project, VMOptionsGenerator.computeBaseHash(baseVmOptionsPath))
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
        syncTypes: List<String>,
        signalManager: ShutdownSignalManager
    ) {
        // Create stop request signal
        signalManager.createStopRequest(
            project = project,
            autoRestart = options.autoRestart,
            syncTypes = syncTypes,
            timeoutSeconds = options.shutdownTimeout
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
            ideConfigRepository.getScope().get()
    }
}

/**
 * Exception thrown when sync operation fails.
 */
class SyncException(message: String) : Exception(message)