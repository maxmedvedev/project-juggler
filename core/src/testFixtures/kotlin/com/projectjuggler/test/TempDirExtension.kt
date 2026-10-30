package com.projectjuggler.test

import io.kotest.core.TestConfiguration
import io.kotest.core.listeners.TestListener
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/**
 * Kotest extension for automatic cleanup of temporary directories.
 * Directories are cleaned up after the spec finishes (not after each test).
 */
@OptIn(ExperimentalPathApi::class)
class TempDirExtension(private val path: Path) : TestListener {
    override suspend fun afterSpec(spec: Spec) {
        withContext(Dispatchers.IO) {
            if (Files.isDirectory(path)) {
                path.deleteRecursively()
            } else {
                Files.deleteIfExists(path)
            }
        }
    }
}

/**
 * Creates a temporary directory with the given prefix and registers it for automatic cleanup
 * when the spec finishes.
 *
 * Usage:
 * ```
 * class MyTest : StringSpec({
 *     val baseDir = createTempDir("base")
 *
 *     "test" {
 *         baseDir.resolve("file")  // use it
 *     }
 * }) // auto cleanup when spec finishes
 * ```
 */
fun TestConfiguration.createTempDir(prefix: String): Path {
    val dir = createTempDirectory(prefix)
    extensions(TempDirExtension(dir))
    return dir
}

/**
 * Creates a temporary file with the given prefix and suffix and registers it for automatic cleanup
 * when the spec finishes.
 */
fun StringSpec.createTempFile(prefix: String, suffix: String): Path {
    val file = kotlin.io.path.createTempFile(prefix, suffix)
    extensions(TempDirExtension(file))
    return file
}
