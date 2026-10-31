package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.ShutdownSignalManager
import com.projectjuggler.core.StopRequestSignal
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.services.IdeInstallationService
import com.projectjuggler.plugin.services.IsolatedInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level service that monitors for shutdown signals and initiates graceful shutdown.
 *
 * [start] is called from [ShutdownSignalAppListener] at application start, so an isolated IDE on the welcome screen
 * is monitored too; [ShutdownSignalServiceStartupActivity] calls it as a fallback for dynamic plugin loading. In an
 * isolated instance it polls the signals directory for stop requests and exits the IDE when one is acted on (see
 * [StopRequestTracker]). The `autoRestart` and `syncTypes` fields of a request are only logged: restarting is the
 * requester's job.
 */
@Service(Service.Level.APP)
class ShutdownSignalService(scope: CoroutineScope) {

    private val log = logger<ShutdownSignalService>()
    private val scope = scope + Dispatchers.IO
    private val started = AtomicBoolean()
    private val stopRequests = StopRequestTracker()

    /** Starts detection and monitoring once, in a `Dispatchers.IO` coroutine; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val project = detectCurrentProject()
            if (project == null) {
                log.info("ShutdownSignalService: Not an isolated project, monitoring disabled")
                return@launch
            }
            log.info("ShutdownSignalService initialized for project: ${project.name} (${project.id.id})")

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
     * Determines the current project from the idea.config.path system property.
     * Returns null if not running as an isolated project or if detection fails.
     */
    private fun detectCurrentProject(): ProjectMetadata? {
        return try {
            val projectId = IsolatedInstance.currentProjectId() ?: return null
            loadProjectMetadata(projectId)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.error("Error detecting the isolated project", e)
            null
        }
    }

    private fun loadProjectMetadata(projectId: ProjectId): ProjectMetadata? {
        return try {
            IdeInstallationService.currentIdeConfigRepository.loadProjectMetadata(projectId)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.error("Error loading project metadata for ID: ${projectId.id}", e)
            null
        }
    }

    /**
     * Checks for stop signal and initiates shutdown if found.
     */
    private suspend fun checkForStopSignal(project: ProjectMetadata) {
        try {
            val signalManager = ShutdownSignalManager.getInstance(IdeInstallationService.currentIdeConfigRepository)

            val signal = signalManager.readStopRequest(project) ?: return

            // Acted on once per request id and only while not older than its timeoutSeconds
            if (!stopRequests.shouldAct(signal)) {
                return
            }

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
     * Exits IntelliJ without the "Confirm exit" dialog, so an unattended IDE does not block the requester until it
     * times out. Settings are still saved, and IntelliJ still asks when background tasks are running.
     */
    private fun initiateGracefulShutdown(signal: StopRequestSignal) {
        log.info(
            "Received stop request: ${signal.requestId} (autoRestart=${signal.autoRestart}, " +
                "syncTypes=${signal.syncTypes}, timeoutSeconds=${signal.timeoutSeconds}); initiating graceful shutdown"
        )
        ApplicationManagerEx.getApplicationEx().exit(/* force = */ false, /* exitConfirmed = */ true)
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
