package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ShutdownSignalManager
import com.projectjuggler.core.StopRequestSignal
import com.projectjuggler.di.KoinInit
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IsolatedInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

/**
 * Application-level service that monitors for shutdown signals and initiates graceful shutdown.
 *
 * This service runs in the background and polls the signals directory for stop requests.
 * When a stop request is detected, it initiates a graceful shutdown of the IntelliJ instance.
 */
@Service(Service.Level.APP)
class ShutdownSignalService(scope: CoroutineScope) {

    private val log = logger<ShutdownSignalService>()
    private val scope = scope + Dispatchers.IO
    private var pollingJob: Job? = null
    private val stopRequests = StopRequestTracker()

    init {
        KoinInit.init(pluginModule)

        // Only start monitoring if this is an isolated project
        val project = getCurrentProjectId()
        if (project != null) {
            log.info("ShutdownSignalService initialized for project: ${project.name} (${project.id.id})")
            startMonitoring(project)
        } else {
            log.info("ShutdownSignalService: Not an isolated project, monitoring disabled")
        }
    }

    /**
     * Determines the current project from the idea.config.path system property.
     * Returns null if not running as an isolated project.
     */
    private fun getCurrentProjectId(): ProjectMetadata? {
        val projectId = IsolatedInstance.currentProjectId() ?: return null

        // Load the ProjectMetadata from the config repository
        return try {
            IdeInstallationService.currentIdeConfigRepository.loadProjectMetadata(projectId)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.error("Error loading project metadata for ID: ${projectId.id}", e)
            null
        }
    }

    /**
     * Starts monitoring for shutdown signals.
     */
    private fun startMonitoring(project: ProjectMetadata) {
        pollingJob = scope.launch {
            // Clean up stale signals on startup
            cleanupStaleSignals(project)

            // Poll for signals every 1 second
            while (isActive) {
                try {
                    checkForStopSignal(project)
                } catch (e: Throwable) {
                    e.rethrowIfControlFlow()
                    log.error("Error checking for stop signal", e)
                }

                delay(1000) // Poll every second
            }
        }
    }

    /**
     * Checks for stop signal and initiates shutdown if found.
     */
    private suspend fun checkForStopSignal(project: ProjectMetadata) {
        try {
            val signalManager = ShutdownSignalManager.getInstance(IdeInstallationService.currentIdeConfigRepository)

            val signal = signalManager.readStopRequest(project) ?: return

            // Acted on once per request id and only while fresh
            if (!stopRequests.shouldAct(signal)) {
                return
            }

            log.info("Received stop request: ${signal.requestId} (autoRestart=${signal.autoRestart})")

            // Initiate graceful shutdown
            withContext(Dispatchers.Main) {
                initiateGracefulShutdown(signal)
            }
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.error("Error processing stop signal", e)
        }
    }

    /**
     * Initiates graceful shutdown of IntelliJ.
     * This allows the user to save unsaved changes.
     */
    private fun initiateGracefulShutdown(signal: StopRequestSignal) {
        log.info("Initiating graceful shutdown")

        // Use ApplicationManager to exit gracefully
        // This will trigger save dialogs if there are unsaved changes
        ApplicationManager.getApplication().exit()
    }

    /**
     * Cleans up stale signal files on startup.
     */
    private fun cleanupStaleSignals(project: ProjectMetadata) {
        try {
            val signalManager = ShutdownSignalManager.getInstance(IdeInstallationService.currentIdeConfigRepository)
            signalManager.cleanupStaleSignals(project, maxAgeMinutes = 5)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.error("Error cleaning up stale signals", e)
        }
    }
}
