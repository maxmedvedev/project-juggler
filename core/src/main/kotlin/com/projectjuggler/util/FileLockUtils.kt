package com.projectjuggler.util

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Utility for file locking operations.
 * Provides thread-safe and process-safe file operations using OS-level file locks.
 */
object FileLockUtils {
    /**
     * Executes the given block while holding an exclusive lock on the specified file.
     * Creates the file and parent directories if they don't exist.
     *
     * @param file The file to lock
     * @param block The operation to perform while holding the lock
     * @return The result of the block execution
     */
    fun <T> withFileLock(file: Path, block: () -> T): T {
        // Ensure parent directory exists
        Files.createDirectories(file.parent)

        // Create file if it doesn't exist
        if (!file.exists()) {
            Files.createFile(file)
        }

        return RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.channel.lock().use {
                block()
            }
        }
    }
}
