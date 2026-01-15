package com.projectjuggler.config

import kotlinx.serialization.Serializable

/**
 * Legacy v1 configuration format.
 * Used only for migration from v1 to v2 directory structure.
 * This class should not be used for new code - use [IdeConfig] instead.
 */
@Serializable
data class LegacyGlobalConfig(
    val intellijPath: String? = null,
    val baseVmOptionsPath: String? = null,
    val baseVmOptionsHash: String? = null,
    val maxRecentProjects: Int = 10,
    val basePluginsPath: String? = null,
    val baseConfigPath: String? = null,
    val mainProjectPath: String? = null,
) {
    companion object {
        fun default() = LegacyGlobalConfig()
    }
}
