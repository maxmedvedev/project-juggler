package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import java.nio.file.Files

/**
 * Detects and fixes mismatches between stored IDE paths and actual current paths.
 * Used by both the startup checker and the recent projects popup.
 */
internal object IdePathSyncDetector {
    private val LOG = logger<IdePathSyncDetector>()

    /**
     * Returns a list of human-readable labels for paths that are out of sync.
     * Empty list means everything is in sync or this is not the main IDE instance.
     */
    fun detectMismatches(
        repository: IdeConfigRepository,
        current: CurrentIdePaths = CurrentIdePaths.detect(),
    ): List<String> {
        // Don't use MainProjectService.isRunningInMainInstance() here because it compares
        // the current config dir with the stored baseConfigPath — which may itself be stale.
        // Instead, check if we're running as an isolated project instance.
        if (current.configDir.startsWith(repository.baseDir)) {
            return emptyList()
        }

        val config = repository.load()

        val currentConfigPath = current.configDir.toString()
        val currentPluginsPath = current.pluginsDir.toString()
        val currentVmOptionsPath = current.vmOptionsFile.toString()

        val mismatches = mutableListOf<String>()

        if (config.baseConfigPath != null && config.baseConfigPath != currentConfigPath) {
            mismatches.add(ProjectJugglerBundle.message("ide.path.kind.config"))
        }
        if (config.basePluginsPath != null && config.basePluginsPath != currentPluginsPath) {
            mismatches.add(ProjectJugglerBundle.message("ide.path.kind.plugins"))
        }
        if (config.baseVmOptionsPath != null && config.baseVmOptionsPath != currentVmOptionsPath) {
            mismatches.add(ProjectJugglerBundle.message("ide.path.kind.vmoptions"))
        }

        return mismatches
    }

    /**
     * Updates all stored paths to the current IDE paths.
     */
    fun updatePaths(
        repository: IdeConfigRepository,
        notificationHandler: NotificationHandler,
        current: CurrentIdePaths = CurrentIdePaths.detect(),
    ) {
        try {
            val currentConfigPath = current.configDir.toString()
            val currentPluginsPath = current.pluginsDir.toString()
            val currentVmOptionsPath = current.vmOptionsFile.toString()

            repository.update { config ->
                config.copy(
                    baseConfigPath = currentConfigPath,
                    basePluginsPath = currentPluginsPath,
                    baseVmOptionsPath = if (Files.exists(current.vmOptionsFile)) currentVmOptionsPath
                    else config.baseVmOptionsPath,
                )
            }

            // Update VM options hash for the new path
            if (Files.exists(current.vmOptionsFile)) {
                BaseVMOptionsTracker.getInstance(repository).updateHash()
            }

            LOG.info("Updated IDE paths to current values")
            notificationHandler.showInfoNotification(ProjectJugglerBundle.message("notification.success.paths.updated"))
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            LOG.error("Failed to update IDE paths", e)
            notificationHandler.showErrorNotification(
                ProjectJugglerBundle.message("notification.error.paths.update.failed", e.userMessage())
            )
        }
    }

    /**
     * Auto-populate config with detected paths if this is first run.
     */
    fun autoPopulateIfNeeded(
        repository: IdeConfigRepository,
        current: CurrentIdePaths = CurrentIdePaths.detect(),
    ) {
        try {
            val config = repository.load()

            // Skip if already configured
            if (config.baseConfigPath != null) {
                return
            }

            val vmOptionsPath = current.vmOptionsFile

            repository.update { currentConfig ->
                var updated = currentConfig

                if (Files.exists(vmOptionsPath)) {
                    updated = updated.copy(baseVmOptionsPath = vmOptionsPath.toString())
                }

                updated = updated.copy(baseConfigPath = current.configDir.toString())
                updated = updated.copy(basePluginsPath = current.pluginsDir.toString())

                updated
            }

            // Update VM options hash if path exists
            if (Files.exists(vmOptionsPath)) {
                BaseVMOptionsTracker.getInstance(repository).updateHash()
            }

            LOG.info("Auto-populated config for IDE: ${repository.installation.displayName}")
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            LOG.error("Failed to auto-populate config", e)
        }
    }
}
