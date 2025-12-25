package com.ideajuggler.config

import kotlinx.serialization.Serializable

@Serializable
data class GlobalConfig(
    val intellijPath: String? = null,
    val baseVmOptionsPath: String? = null,
    val baseVmOptionsHash: String? = null,
    val maxRecentProjects: Int = 10
) {
    companion object {
        fun default() = GlobalConfig()
    }
}
