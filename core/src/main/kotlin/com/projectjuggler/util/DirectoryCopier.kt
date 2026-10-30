package com.projectjuggler.util

import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object DirectoryCopier {

    /**
     * Copy directory contents from source to destination.
     * Only copies if destination is empty (first open detection).
     * Silently skips if source doesn't exist or isn't readable.
     * A failed copy is rolled back (destination emptied) so the next call retries.
     *
     * @param source Source directory
     * @param destination Destination directory
     * @return true if directory was copied, false if skipped or failed
     */
    fun copyIfFirstOpen(
        source: Path,
        destination: Path,
        excludedDirectories: Set<String> = emptySet(),
        excludedFiles: Set<String> = emptySet(),
        excludedPatterns: Set<String> = emptySet(),
    ): Boolean {
        // Check if source exists and is readable
        if (!source.exists() || !source.isDirectory()) {
            return false // Silently skip
        }

        // Check if destination is empty (first open detection)
        if (!isEmptyDirectory(destination)) {
            return false // Already has content, skip copying
        }

        val exclusions = Exclusions(excludedDirectories, excludedFiles, excludedPatterns)
        try {
            copyDirectoryRecursively(source, destination, exclusions)
            return true
        } catch (e: Throwable) {
            // The destination was empty before, so everything in it is our partial copy
            runCatching { clearDirectory(destination, Exclusions.NONE) }
            System.err.println("[DirectoryCopier] copy from $source to $destination failed: $e")
            return false
        }
    }

    /**
     * Force copy directory contents from source to destination.
     * This deletes the existing destination contents before copying; excluded entries already present
     * in the destination are preserved.
     *
     * @param source Source directory
     * @param destination Destination directory
     */
    fun copy(
        source: Path,
        destination: Path,
        excludedDirectories: Set<String> = emptySet(),
        excludedFiles: Set<String> = emptySet(),
        excludedPatterns: Set<String> = emptySet(),
    ) {
        // Check if source exists and is readable
        if (!source.exists() || !source.isDirectory()) {
            throw IllegalArgumentException("Source directory does not exist or is not a directory: $source")
        }

        val exclusions = Exclusions(excludedDirectories, excludedFiles, excludedPatterns)
        clearDirectory(destination, exclusions)
        copyDirectoryRecursively(source, destination, exclusions)
    }

    /**
     * Delete all contents of a directory except excluded entries, keeping the directory itself.
     */
    private fun clearDirectory(root: Path, exclusions: Exclusions) {
        if (!root.exists()) {
            return
        }

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && exclusions.excludesDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!exclusions.excludesFile(root, file)) {
                    Files.delete(file)
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                // Keep the root, and any directory still holding excluded entries
                if (dir != root && isEmptyDirectory(dir)) {
                    Files.delete(dir)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Check if a directory is empty.
     */
    private fun isEmptyDirectory(dir: Path): Boolean {
        if (!dir.exists()) {
            return true // Treat non-existent as empty
        }

        if (!dir.isDirectory()) {
            return false
        }

        return Files.list(dir).use { stream ->
            stream.findAny().isEmpty
        }
    }

    /**
     * Recursively copy directory contents.
     */
    private fun copyDirectoryRecursively(source: Path, destination: Path, exclusions: Exclusions) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                // Skip excluded directories (like plugins, which are stored separately)
                if (exclusions.excludesDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE
                }

                val targetDir = destination.resolve(source.relativize(dir))
                Files.createDirectories(targetDir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                if (exclusions.excludesFile(source, file)) {
                    return FileVisitResult.CONTINUE
                }

                val targetFile = destination.resolve(source.relativize(file))
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Entries skipped by both the copy and the clear: directory names, file names,
     * and file paths relative to the walked root (forward slashes).
     */
    private data class Exclusions(
        val directories: Set<String>,
        val files: Set<String>,
        val patterns: Set<String>,
    ) {
        fun excludesDirectory(dir: Path): Boolean = dir.fileName?.toString() in directories

        fun excludesFile(root: Path, file: Path): Boolean =
            file.fileName.toString() in files ||
                root.relativize(file).toString().replace(File.separator, "/") in patterns

        companion object {
            val NONE = Exclusions(emptySet(), emptySet(), emptySet())
        }
    }
}
