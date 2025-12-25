package com.ideajuggler.config

import kotlinx.serialization.Serializable

@Serializable
data class ProjectMetadata(
    val id: String,
    val path: String,
    val name: String,
    val lastOpened: String, // ISO-8601 timestamp
    val openCount: Int = 0
)
