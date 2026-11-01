package com.projectjuggler.util

import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Utility for file locking operations.
 * Provides thread-safe and process-safe file operations using OS-level file locks.
 */
object FileLockUtils {
    /**
     * One lock per file for the threads of this JVM: an OS file lock belongs to the whole JVM, so a second thread's
     * `FileChannel.lock()` on the same file throws `OverlappingFileLockException` instead of waiting.
     */
    private val jvmLocks = ConcurrentHashMap<Path, ReentrantLock>()

    /**
     * Executes the given block while holding an exclusive lock on the specified file: first this JVM's lock for the
     * file, so threads wait for each other, then the OS lock, so processes do. Not reentrant: a nested call for the
     * same file on the same thread throws `OverlappingFileLockException`.
     * Creates the file and parent directories if they don't exist.
     *
     * @param file The file to lock
     * @param block The operation to perform while holding the lock
     * @return The result of the block execution
     */
    fun <T> withFileLock(file: Path, block: () -> T): T {
        // Ensure parent directory exists
        Files.createDirectories(file.parent)

        // Create file if it doesn't exist; another process may create it concurrently
        try {
            Files.createFile(file)
        } catch (e: FileAlreadyExistsException) {
            // already present, possibly just created by another process
        }

        val jvmLock = jvmLocks.computeIfAbsent(file.toAbsolutePath().normalize()) { ReentrantLock() }
        return jvmLock.withLock {
            RandomAccessFile(file.toFile(), "rw").use { raf ->
                raf.channel.lock().use {
                    block()
                }
            }
        }
    }
}
