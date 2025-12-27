package com.projectjuggler.config

import kotlinx.serialization.Serializable

@Serializable
data class GlobalConfig(
    val intellijPath: String? = null,
    val baseVmOptionsPath: String? = null,
    val baseVmOptionsHash: String? = null,
    val maxRecentProjects: Int = 10,
    val basePluginsPath: String? = null,
    val baseConfigPath: String? = null,
    val mainProjectPath: String? = null,
) {
    companion object {
        fun default() = GlobalConfig()
    }
}
