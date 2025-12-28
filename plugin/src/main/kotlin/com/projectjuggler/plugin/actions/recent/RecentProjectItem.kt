package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.config.ProjectPath

sealed class PopupListItem

data class RecentProjectItem(
    val projectPath: ProjectPath,
    val gitBranch: String?, // null if not a git repo or error occurred
    val isOpen: Boolean = false, // true if project has running instance
) : PopupListItem()

object OpenFileChooserItem : PopupListItem()

object SyncAllProjectsItem : PopupListItem()
