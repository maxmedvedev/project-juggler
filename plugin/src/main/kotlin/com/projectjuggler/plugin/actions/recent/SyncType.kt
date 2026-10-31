package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.plugin.BUNDLE
import com.projectjuggler.plugin.ProjectJugglerBundle
import org.jetbrains.annotations.PropertyKey

/**
 * Represents different types of project synchronization operations.
 * Each type specifies which settings should be synced across all tracked projects.
 * [displayName] is a short phrase from the bundle, interpolated into sync messages.
 * [cliFlag] is the matching sync-helper flag.
 */
sealed class SyncType(
    @PropertyKey(resourceBundle = BUNDLE) private val displayNameKey: String,
    val cliFlag: String,
) {
    val displayName: String get() = ProjectJugglerBundle.message(displayNameKey)

    val syncVmOptions: Boolean get() = this is All || this is VmOptions
    val syncConfig: Boolean get() = this is All || this is Config
    val syncPlugins: Boolean get() = this is All || this is Plugins

    object All : SyncType("sync.type.all", "--all")
    object VmOptions : SyncType("sync.type.vmoptions", "--vmoptions")
    object Config : SyncType("sync.type.config", "--config")
    object Plugins : SyncType("sync.type.plugins", "--plugins")
}
