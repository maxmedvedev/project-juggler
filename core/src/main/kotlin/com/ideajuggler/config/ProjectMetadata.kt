package com.ideajuggler.config

import kotlinx.serialization.Serializable

@Serializable
data class ProjectMetadata(
    val path: ProjectPath,
    val lastOpened: String, // ISO-8601 timestamp
    val openCount: Int = 0,
    val debugPort: Int? = null,  // JDWP debug port (5000-15000), null if not allocated
) {
    val name: String get() = path.name
    val id: ProjectId get() = path.id
}
