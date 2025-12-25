package com.ideajuggler.util

import com.ideajuggler.platform.Platform
import java.nio.file.Path
import kotlin.io.path.Path

object PathUtils {
    /**
     * Expands tilde (~) to the user's home directory.
     *
     * Handles:
     * - ~/path -> /home/user/path
     * - ~ -> /home/user
     * - /absolute/path -> /absolute/path (unchanged)
     *
     * @param path The path potentially containing tilde
     * @return The expanded Path
     */
    fun expandTilde(path: Path): Path {
        if (!Platform.isUnix()) {
            return path
        }

        // Check if path starts with ~ using Path API
        if (path.nameCount == 0 || path.getName(0).toString() != "~") {
            return path
        }

        val homeDir = Path(System.getProperty("user.home"))

        // If it's just "~", return home directory
        if (path.nameCount == 1) {
            return homeDir
        }

        // Otherwise, resolve remaining path components relative to home
        // Skip the first component (which is "~") and resolve the rest
        return path.subpath(1, path.nameCount).fold(homeDir) { acc, component ->
            acc.resolve(component)
        }
    }
}
