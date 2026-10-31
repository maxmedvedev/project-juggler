package com.projectjuggler.util

import com.projectjuggler.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

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

    /**
     * Resolves a raw path string the way every user-entered path is resolved: tilde expansion,
     * then absolute, then normalized.
     *
     * @throws java.nio.file.InvalidPathException if [rawPath] is not a valid path
     */
    fun resolveAbsolute(rawPath: String): Path = expandTilde(Path.of(rawPath)).toAbsolutePath().normalize()

    /**
     * The directory a project is rooted at.
     *
     * Projects can be opened by selecting a file (e.g. `MODULE.bazel`) instead of a directory; in
     * that case the enclosing directory is the project root. A path that does not exist is treated
     * as a file, so a remembered `.../my-repo/MODULE.bazel` still resolves to `.../my-repo`.
     *
     * @return The root directory, or null for a parentless path (e.g. a filesystem root)
     */
    fun projectRoot(path: Path): Path? = if (path.isDirectory()) path else path.parent

    /**
     * True when [a] and [b] name the same location: equal after `toAbsolutePath().normalize()`, or, when both
     * exist, equal real paths (so symlinks match). A failure to resolve real paths counts as different.
     */
    fun isSamePath(a: Path, b: Path): Boolean {
        val normalizedA = a.toAbsolutePath().normalize()
        val normalizedB = b.toAbsolutePath().normalize()
        if (normalizedA == normalizedB) return true
        if (!Files.exists(normalizedA) || !Files.exists(normalizedB)) return false
        return try {
            normalizedA.toRealPath() == normalizedB.toRealPath()
        } catch (e: Throwable) {
            System.err.println("Failed to resolve real paths of $normalizedA and $normalizedB: ${e.message}")
            false
        }
    }
}
