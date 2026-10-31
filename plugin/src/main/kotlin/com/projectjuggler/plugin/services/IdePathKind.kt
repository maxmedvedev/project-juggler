package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.plugin.ProjectJugglerBundle
import java.nio.file.Path

/**
 * The three base paths stored per IDE installation, so they can be iterated instead of handled one by one.
 */
internal enum class IdePathKind(private val key: String, val expectsDirectory: Boolean) {
    CONFIG("ide.path.kind.config", expectsDirectory = true),
    PLUGINS("ide.path.kind.plugins", expectsDirectory = true),
    VM_OPTIONS("ide.path.kind.vmoptions", expectsDirectory = false);

    /** The user-visible label, from the bundle. */
    val displayName: String
        get() = ProjectJugglerBundle.message(key)

    /** The stored base path of this kind, or null when unset. */
    fun stored(config: IdeConfig): String? = when (this) {
        CONFIG -> config.baseConfigPath
        PLUGINS -> config.basePluginsPath
        VM_OPTIONS -> config.baseVmOptionsPath
    }

    /** The path of this kind in the running IDE. */
    fun current(paths: CurrentIdePaths): Path = when (this) {
        CONFIG -> paths.configDir
        PLUGINS -> paths.pluginsDir
        VM_OPTIONS -> paths.vmOptionsFile
    }

    /** [config] with the stored path of this kind replaced by [path] (null unsets it). */
    fun withStored(config: IdeConfig, path: String?): IdeConfig = when (this) {
        CONFIG -> config.copy(baseConfigPath = path)
        PLUGINS -> config.copy(basePluginsPath = path)
        VM_OPTIONS -> config.copy(baseVmOptionsPath = path)
    }
}

/** The display names joined with ", ", as the popup item and the startup balloon show them. */
internal fun List<IdePathKind>.joinDisplayNames(): String = joinToString { it.displayName }
