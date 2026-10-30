package com.projectjuggler.plugin.actions.recent

/**
 * Represents different types of project synchronization operations.
 * Each type specifies which settings should be synced across all tracked projects.
 * [cliFlag] is the matching sync-helper flag.
 */
sealed class SyncType(val displayName: String, val cliFlag: String) {
    val syncVmOptions: Boolean get() = this is All || this is VmOptions
    val syncConfig: Boolean get() = this is All || this is Config
    val syncPlugins: Boolean get() = this is All || this is Plugins

    object All : SyncType("all settings", "--all")
    object VmOptions : SyncType("VM options", "--vmoptions")
    object Config : SyncType("configuration", "--config")
    object Plugins : SyncType("plugins", "--plugins")
}
