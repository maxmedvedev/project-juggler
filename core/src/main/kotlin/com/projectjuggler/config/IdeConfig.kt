package com.projectjuggler.config

import kotlinx.serialization.Serializable

/**
 * Per-IDE configuration.
 * Each IDE installation has its own config file with these settings.
 */
@Serializable
data class IdeConfig(
    val installation: IdeInstallation,
    val baseVmOptionsPath: String? = null,
    val baseVmOptionsHash: String? = null,
    val maxRecentProjects: Int = 10,
    val basePluginsPath: String? = null,
    val baseConfigPath: String? = null,
    val mainProjectPath: String? = null,
) {
    companion object {
        fun default(installation: IdeInstallation) = IdeConfig(installation = installation)
    }
}
