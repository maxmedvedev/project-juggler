package com.projectjuggler.plugin.services

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/**
 * The directories and VM options file of the running IDE, as the plugin compares and stores them.
 * [detect] reads them from `PathManager`; tests build the instance directly.
 */
internal data class CurrentIdePaths(
    val configDir: Path,
    val pluginsDir: Path,
    val vmOptionsFile: Path,
) {
    companion object {
        fun detect() = CurrentIdePaths(
            configDir = PathManager.getConfigDir(),
            pluginsDir = PathManager.getPluginsDir(),
            // todo detect when a user-options file appears
            vmOptionsFile = VMOptionsCopy.getUserOptionsFile() ?: VMOptionsCopy.getPlatformOptionsFile(),
        )
    }
}
