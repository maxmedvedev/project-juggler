package com.projectjuggler.config

import com.projectjuggler.util.HashUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents an IntelliJ IDE installation.
 * The installation path is used as the unique identifier since IDE version strings
 * can be duplicated (e.g., multiple IntelliJ 2025.1 installations).
 *
 * @param executablePath path to the executable
 * @param displayName human-readable name of the IDE
 */
@Serializable
data class IdeInstallation(
    val executablePath: String,
    val displayName: String
) {
    /**
     * Unique identifier based on the installation path hash.
     * Using 16 characters of SHA-256 for uniqueness.
     */
    @Transient
    val id: String = HashUtils.calculateStringHash(executablePath).take(16)

    /**
     * Directory name for this IDE's configuration storage.
     * Format: "sanitized-name-hash8chars"
     * Example: "IntelliJ-IDEA-2025.1-a1b2c3d4"
     */
    @Transient
    val directoryName: String = "${sanitizedName}-${id.take(8)}"

    private val sanitizedName: String
        get() = displayName
            .replace(" ", "-")
            .replace(Regex("[^a-zA-Z0-9-.]"), "")
}
