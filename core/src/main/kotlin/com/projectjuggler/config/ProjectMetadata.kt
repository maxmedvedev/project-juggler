package com.projectjuggler.config

import kotlinx.serialization.Serializable

@Serializable
data class ProjectMetadata(
    val path: ProjectPath,
    val lastOpened: String, // ISO-8601 timestamp
    val openCount: Int = 0,
    val debugPort: Int? = null,  // JDWP debug port (5001-14999), null when the base vmoptions has no JDWP agent
    /** SHA-256 of the base vmoptions used to write `idea.vmoptions`; null when written without a base */
    val baseVmOptionsHash: String? = null,
) {
    val name: String get() = path.name
    val id: ProjectId get() = path.id
}
