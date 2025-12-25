package com.ideajuggler.util

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
    fun copyIfFirstOpen(source: Path, destination: Path): Boolean {
        // Check if source exists and is readable
        if (!source.exists() || !source.isDirectory()) {
            return false // Silently skip
        }

        // Check if destination is empty (first open detection)
        if (!isEmptyDirectory(destination)) {
            return false // Already has content, skip copying
        }

        try {
            copyDirectoryRecursively(source, destination)
            return true
        } catch (e: Exception) {
            // Silently fail - don't break project opening
            return false
        }
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
    private fun copyDirectoryRecursively(source: Path, destination: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                val targetDir = destination.resolve(source.relativize(dir))
                Files.createDirectories(targetDir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                val targetFile = destination.resolve(source.relativize(file))
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
