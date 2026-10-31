package com.projectjuggler.plugin.services

import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.NotificationHandler
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.rethrowIfControlFlow
import com.projectjuggler.plugin.userMessage
import com.projectjuggler.util.PathUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects and fixes mismatches between stored IDE paths and actual current paths.
 * Used by the startup check ([IdePathsStartupCheck]) and the recent projects popup.
 * Paths are compared with [PathUtils.isSamePath], so trailing slashes, `.` segments and symlinks are not drift.
 */
internal object IdePathSyncDetector {
    private val LOG = logger<IdePathSyncDetector>()

    /**
     * Returns the kinds of stored paths that are out of sync, in [IdePathKind] order.
     * Empty list means everything is in sync or this is not the main IDE instance.
     */
    fun detectMismatches(
        repository: IdeConfigRepository,
        current: CurrentIdePaths = CurrentIdePaths.detect(),
    ): List<IdePathKind> {
        if (!isMainInstance(repository, current)) return emptyList()

        val config = repository.load()
        return IdePathKind.entries.filter { kind ->
            val stored = kind.stored(config)
            stored != null && !PathUtils.isSamePath(Path.of(stored), kind.current(current))
        }
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
     * Fills the base paths from the current IDE, in the main instance only: each kind whose stored path is unset
     * or lies under `<ideDir>/projects/` (an isolated dir captured by mistake) gets the current path. The vmoptions
     * path is only set when the current file exists. Nothing is written when no kind needs filling.
     */
    fun autoPopulateIfNeeded(
        repository: IdeConfigRepository,
        current: CurrentIdePaths = CurrentIdePaths.detect(),
    ) {
        try {
            if (!isMainInstance(repository, current)) return

            val directoryManager = DirectoryManager.getInstance(repository)
            fun needsFill(config: IdeConfig, kind: IdePathKind): Boolean {
                if (kind == IdePathKind.VM_OPTIONS && !Files.exists(current.vmOptionsFile)) return false
                val stored = kind.stored(config) ?: return true
                return directoryManager.isIsolatedConfigDir(Path.of(stored))
            }

            if (IdePathKind.entries.none { needsFill(repository.load(), it) }) return

            var filled = emptyList<IdePathKind>()
            repository.update { config ->
                filled = IdePathKind.entries.filter { needsFill(config, it) }
                filled.fold(config) { updated, kind -> kind.withStored(updated, kind.current(current).toString()) }
            }

            if (IdePathKind.VM_OPTIONS in filled) {
                BaseVMOptionsTracker.getInstance(repository).updateHash()
            }

            LOG.info("Auto-populated ${filled.joinToString { it.name }} for IDE: ${repository.installation.displayName}")
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            LOG.error("Failed to auto-populate config", e)
        }
    }

    // Don't use MainProjectService.isRunningInMainInstance() here because it compares
    // the current config dir with the stored baseConfigPath, which may itself be stale.
    // Instead, check if we're running as an isolated project instance.
    private fun isMainInstance(repository: IdeConfigRepository, current: CurrentIdePaths): Boolean =
        !DirectoryManager.getInstance(repository).isIsolatedConfigDir(current.configDir)
}
