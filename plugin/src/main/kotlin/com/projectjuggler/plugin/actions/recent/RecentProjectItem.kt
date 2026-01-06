package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.config.ProjectPath

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

/**
 * Represents actions that can be performed on a specific recent project.
 */
sealed interface ProjectAction {
    object OpenProject : ProjectAction
    data class SyncSettings(val syncType: SyncType) : ProjectAction
    object RemoveProject : ProjectAction
}

sealed class PopupListItem

data class RecentProjectItem(
    val projectPath: ProjectPath,
    val gitBranch: String?, // null if not a git repo or error occurred
    val isOpen: Boolean = false, // true if project has running instance
) : PopupListItem()

object OpenFileChooserItem : PopupListItem()

data class SyncProjectsItem(val syncType: SyncType) : PopupListItem()
