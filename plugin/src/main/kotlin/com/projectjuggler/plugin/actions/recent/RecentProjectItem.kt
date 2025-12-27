package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import java.nio.file.Path

sealed class PopupListItem

data class RecentProjectItem(
    val metadata: ProjectMetadata,
    val gitBranch: String?, // null if not a git repo or error occurred
    val displayText: String // Formatted text for display and search
) : PopupListItem()

data class MainProjectItem(
    val path: ProjectPath,
    val gitBranch: String?
) : PopupListItem()

object OpenFileChooserItem : PopupListItem()

object SyncAllProjectsItem : PopupListItem()
