package com.ideajuggler.core

import com.ideajuggler.util.HashUtils
import java.nio.file.Path
import kotlin.io.path.absolutePathString

object ProjectIdGenerator {
    fun generate(projectPath: Path): String {
        // Resolve to canonical path to handle symlinks, relative paths, etc.
        val canonicalPath = try {
            projectPath.toRealPath().absolutePathString()
        } catch (e: Exception) {
            // If path doesn't exist yet, use absolute path
            projectPath.toAbsolutePath().normalize().absolutePathString()
        }

        // Generate SHA-256 hash and take first 16 characters
        val hash = HashUtils.calculateStringHash(canonicalPath)
        return hash.take(16)
    }
}
