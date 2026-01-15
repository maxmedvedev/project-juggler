package com.projectjuggler.plugin.actions.recent

/**
 * Represents different types of project synchronization operations.
 * Each type specifies which settings should be synced across all tracked projects.
 */
sealed class SyncType(val displayName: String) {
    val syncVmOptions: Boolean get() = this is All || this is VmOptions
    val syncConfig: Boolean get() = this is All || this is Config
    val syncPlugins: Boolean get() = this is All || this is Plugins

    object All : SyncType("all settings")
    object VmOptions : SyncType("VM options")
    object Config : SyncType("configuration")
    object Plugins : SyncType("plugins")
}
