package com.projectjuggler.plugin.services

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.showErrorNotification
import com.projectjuggler.plugin.showInfoNotification
import java.nio.file.Files
import java.nio.file.Path

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
    fun detectMismatches(repository: IdeConfigRepository): List<String> {
        // Don't use MainProjectService.isRunningInMainInstance() here because it compares
        // the current config dir with the stored baseConfigPath — which may itself be stale.
        // Instead, check if we're running as an isolated project instance.
        val currentConfigDir = PathManager.getConfigDir()
        if (currentConfigDir.startsWith(repository.baseDir)) {
            return emptyList()
        }

        val config = repository.load()

        val currentConfigPath = PathManager.getConfigDir().toString()
        val currentPluginsPath = PathManager.getPluginsDir().toString()
        val currentVmOptionsPath =
            (VMOptionsCopy.getUserOptionsFile() ?: VMOptionsCopy.getPlatformOptionsFile()).toString()

        val mismatches = mutableListOf<String>()

        if (config.baseConfigPath != null && config.baseConfigPath != currentConfigPath) {
            mismatches.add("Config")
        }
        if (config.basePluginsPath != null && config.basePluginsPath != currentPluginsPath) {
            mismatches.add("Plugins")
        }
        if (config.baseVmOptionsPath != null && config.baseVmOptionsPath != currentVmOptionsPath) {
            mismatches.add("VM Options")
        }

        return mismatches
    }

    /**
     * Updates all stored paths to the current IDE paths.
     */
    fun updatePaths(repository: IdeConfigRepository, project: Project?) {
        try {
            val currentConfigPath = PathManager.getConfigDir().toString()
            val currentPluginsPath = PathManager.getPluginsDir().toString()
            val currentVmOptionsPath =
                (VMOptionsCopy.getUserOptionsFile() ?: VMOptionsCopy.getPlatformOptionsFile()).toString()

            repository.update { config ->
                config.copy(
                    baseConfigPath = currentConfigPath,
                    basePluginsPath = currentPluginsPath,
                    baseVmOptionsPath = if (Files.exists(Path.of(currentVmOptionsPath))) currentVmOptionsPath
                    else config.baseVmOptionsPath,
                )
            }

            // Update VM options hash for the new path
            val vmPath = Path.of(currentVmOptionsPath)
            if (Files.exists(vmPath)) {
                BaseVMOptionsTracker.getInstance(repository).updateHash()
            }

            LOG.info("Updated IDE paths to current values")
            showInfoNotification(ProjectJugglerBundle.message("notification.success.paths.updated"), project)
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            LOG.error("Failed to update IDE paths", e)
            showErrorNotification(
                ProjectJugglerBundle.message("notification.error.paths.update.failed", e.message ?: "Unknown error"),
                project
            )
        }
    }
}