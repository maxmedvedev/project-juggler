package com.projectjuggler.plugin.model

import com.projectjuggler.config.ProjectMetadata

sealed class PopupListItem

data class RecentProjectItem(
    val metadata: ProjectMetadata,
    val gitBranch: String?, // null if not a git repo or error occurred
    val displayText: String // Formatted text for display and search
) : PopupListItem()

object OpenFileChooserItem : PopupListItem()

object SyncAllProjectsItem : PopupListItem()
