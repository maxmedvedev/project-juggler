package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ShutdownSignalManager
import com.projectjuggler.core.StopRequestSignal
import com.projectjuggler.plugin.services.IdeInstallationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.io.path.Path

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
    private val processedRequestIds = mutableSetOf<String>()

    init {
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
        val configPath = System.getProperty("idea.config.path") ?: return null
        val configPathObj = Path(configPath)

        // Check if this is an isolated project:
        // Path should be: ~/.project-juggler/v2/<ide-dir>/projects/<project-id>/config
        val parts = configPathObj.toString().split("/").filter { it.isNotEmpty() }
        val projectsIndex = parts.indexOf("projects")

        if (projectsIndex < 0 || projectsIndex + 2 >= parts.size || parts[projectsIndex + 2] != "config") {
            return null
        }

        val projectId = parts[projectsIndex + 1]

        // Load the ProjectMetadata from the config repository
        return try {
            IdeInstallationService.currentIdeConfigRepository.loadProjectMetadata(ProjectId(projectId))
        } catch (e: Exception) {
            log.error("Error loading project metadata for ID: $projectId", e)
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
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
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

            // Check if already processed
            if (processedRequestIds.contains(signal.requestId)) {
                return
            }

            // Validate signal age (ignore signals older than 5 minutes)
            if (!isSignalValid(signal)) {
                log.warn("Ignoring stale stop signal: ${signal.requestId}")
                return
            }

            // Mark as processed
            processedRequestIds.add(signal.requestId)

            log.info("Received stop request: ${signal.requestId} (autoRestart=${signal.autoRestart})")

            // Initiate graceful shutdown
            withContext(Dispatchers.Main) {
                initiateGracefulShutdown(signal)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error("Error processing stop signal", e)
        }
    }

    /**
     * Validates if the signal is not too old.
     */
    private fun isSignalValid(signal: StopRequestSignal): Boolean {
        return try {
            val signalTime = Instant.parse(signal.timestamp)
            val now = Instant.now()
            val ageMinutes = (now.toEpochMilli() - signalTime.toEpochMilli()) / 1000 / 60
            ageMinutes <= 5
        } catch (e: Exception) {
            false
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
            log.error("Error cleaning up stale signals", e)
        }
    }
}