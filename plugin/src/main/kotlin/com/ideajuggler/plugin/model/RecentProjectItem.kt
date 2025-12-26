package com.ideajuggler.plugin.model

import com.ideajuggler.config.ProjectMetadata

data class RecentProjectItem(
    val metadata: ProjectMetadata,
    val gitBranch: String?, // null if not a git repo or error occurred
    val displayText: String // Formatted text for display and search
)
