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
     *
     * @param source Source directory
     * @param destination Destination directory
     * @return true if directory was copied, false if skipped
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

        try {
            copyDirectoryRecursively(source, destination, excludedDirectories, excludedFiles, excludedPatterns)
            return true
        } catch (e: Exception) {
            // Silently fail - don't break project opening
            return false
        }
    }

    /**
     * Force copy directory contents from source to destination.
     * This will delete all existing files in the destination before copying.
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

        // Clear destination directory before copying
        clearDirectory(destination)

        copyDirectoryRecursively(source, destination, excludedDirectories, excludedFiles, excludedPatterns)
    }

    /**
     * Delete all contents of a directory while keeping the directory itself.
     */
    private fun clearDirectory(root: Path) {
        if (!root.exists()) {
            return
        }

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                // Don't delete the root directory itself
                if (dir != root) {
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
    private fun copyDirectoryRecursively(source: Path,
                                         destination: Path,
                                         excludedDirectories: Set<String> = emptySet(),
                                         excludedFiles: Set<String> = emptySet(),
                                         excludedPatterns: Set<String> = emptySet(),
    ) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                // Skip excluded directories (like plugins, which are stored separately)
                val dirName = dir.fileName?.toString()
                if (dirName != null && dirName in excludedDirectories) {
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
                val fileName = file.fileName.toString()

                // Check filename exclusions
                if (fileName in excludedFiles) {
                    return FileVisitResult.CONTINUE
                }

                // Check path pattern exclusions
                val relativePath = source.relativize(file).toString()
                    .replace(File.separator, "/")  // Normalize to forward slashes
                if (relativePath in excludedPatterns) {
                    return FileVisitResult.CONTINUE
                }

                val targetFile = destination.resolve(source.relativize(file))
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
