package com.projectjuggler.plugin.util

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.core.SyncHelperInstallation
import com.projectjuggler.platform.Platform
import com.projectjuggler.plugin.rethrowIfControlFlow
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Manages extraction and execution of the bundled sync-helper distribution.
 *
 * The distribution is extracted once per content fingerprint into [defaultRoot] and reused across IDE sessions.
 * The helper runs on the IDE's own JVM with its main class (see [command]); the `bin/` start scripts stay in the
 * bundle for manual runs only.
 */
@OptIn(ExperimentalPathApi::class)
object BundledSyncHelper {
    const val LOG_FILE_NAME = "sync-helper.log"
    const val MAIN_CLASS = SyncHelperInstallation.MAIN_CLASS

    private const val RESOURCE_ROOT = "sync-helper"
    private const val MARKER = SyncHelperInstallation.MARKER
    private val BUNDLED_DIRS = listOf("bin", "lib")
    private val LEGACY_MIN_AGE: Duration = Duration.ofHours(1)

    private val log = logger<BundledSyncHelper>()
    private val legacyCleanupDone = AtomicBoolean(false)

    /** The bundled content cannot change while this class is loaded, so the fingerprint is computed once. */
    private val cachedFingerprint by lazy { withBundledEntries(::fingerprintOf) }

    /**
     * Gets the directory of the extracted sync-helper distribution (with `bin/` and `lib/`), extracting the bundled
     * distribution if this content has not been extracted yet. Must not be called on EDT.
     *
     * @throws IllegalStateException if sync-helper cannot be extracted or found
     */
    @RequiresBackgroundThread
    fun getInstallDir(): Path = getInstallDir(defaultRoot(), Path(System.getProperty("java.io.tmpdir")))

    /**
     * Returns `<root>/<fingerprint>`, extracting into it first if needed (`lib/sync-helper.jar` marks a complete
     * extraction). After an extraction, other entries of [root] and (once per JVM) legacy `sync-helper-*` dirs in
     * [legacyTempDir] are removed.
     */
    @Synchronized
    internal fun getInstallDir(root: Path, legacyTempDir: Path?): Path {
        val fingerprint = fingerprint()
        val target = root.resolve(fingerprint)
        if (isComplete(target)) return target

        extractTo(root, target)
        bestEffort("remove stale sync-helper extractions in $root") {
            root.listDirectoryEntries().filter { it != target }.forEach(::deleteQuietly)
        }
        if (legacyTempDir != null && legacyCleanupDone.compareAndSet(false, true)) {
            cleanUpLegacyTempDirs(legacyTempDir)
        }

        log.info("sync-helper extracted to: $target")
        return target
    }

    /**
     * Starts the helper with [args], stdout and stderr in [logFile] (overwritten) and stdin closed.
     * Must not be called on EDT.
     */
    @RequiresBackgroundThread
    fun start(args: List<String>, logFile: Path = PathManager.getLogDir().resolve(LOG_FILE_NAME)): Process =
        start(getInstallDir(), args, logFile)

    internal fun start(installDir: Path, args: List<String>, logFile: Path): Process {
        val command = command(
            installDir,
            javaHome = Path(System.getProperty("java.home")),
            baseDir = System.getProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR),
            args = args
        )
        Files.createDirectories(logFile.parent)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        process.outputStream.close()
        log.info("Started sync-helper: ${command.joinToString(" ")}; output in $logFile")
        return process
    }

    internal fun defaultRoot(): Path =
        PathManager.getSystemDir().resolve("project-juggler").resolve("sync-helper")

    /** First 16 hex chars of a SHA-256 over the bundled `bin` and `lib` entries (names, sizes and checksums). */
    internal fun fingerprint(): String = cachedFingerprint

    internal fun resetLegacyCleanupForTests() {
        legacyCleanupDone.set(false)
    }

    /**
     * The command line that runs the helper from [installDir] on the JVM in [javaHome]
     * ([SyncHelperInstallation.command]). Using the IDE's JVM avoids depending on a system `java`, and setting no
     * `JAVA_HOME` keeps the JBR out of the relaunched IDE's environment.
     */
    internal fun command(
        installDir: Path,
        javaHome: Path,
        baseDir: String?,
        args: List<String>,
        platform: Platform = Platform.current(),
    ): List<String> = SyncHelperInstallation(javaHome.toString(), installDir.toString()).command(baseDir, args, platform)

    private fun isComplete(dir: Path): Boolean = dir.resolve(MARKER).isRegularFile()

    /**
     * Extracts into a `<fingerprint>.tmp-*` sibling of [target] and moves it into place atomically, so a crash
     * mid-extraction never leaves a directory that looks complete.
     */
    private fun extractTo(root: Path, target: Path) {
        Files.createDirectories(root)
        val tmp = Files.createTempDirectory(root, "${target.fileName}.tmp-")
        log.info("Extracting bundled sync-helper to: $target")
        try {
            withBundledEntries { entries ->
                entries.forEach { entry ->
                    val file = tmp.resolve(entry.relativePath)
                    Files.createDirectories(file.parent)
                    entry.copyTo(file)
                }
            }

            if (!isComplete(tmp)) {
                throw IllegalStateException("sync-helper jar not found at: ${tmp.resolve(MARKER)}")
            }

            // An incomplete target (jar missing) would block the move
            if (target.exists()) target.deleteRecursively()
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // Someone else completed the same extraction first; use theirs
                if (!isComplete(target)) throw e
                deleteQuietly(tmp)
            }
        } catch (e: Throwable) {
            deleteQuietly(tmp)
            e.rethrowIfControlFlow()
            log.error("Failed to extract bundled sync-helper", e)
            throw IllegalStateException("Failed to extract bundled sync-helper distribution", e)
        }
    }

    /**
     * Deletes `sync-helper-*` dirs that earlier plugin versions extracted into [tempDir]: recognized by `bin/` and
     * `lib/sync-helper.jar`, and only when older than [LEGACY_MIN_AGE] (another IDE may be mid-sync from one).
     */
    private fun cleanUpLegacyTempDirs(tempDir: Path) {
        val cutoff = Instant.now().minus(LEGACY_MIN_AGE)
        bestEffort("list legacy sync-helper dirs in $tempDir") {
            tempDir.listDirectoryEntries("sync-helper-*").forEach { dir ->
                bestEffort("remove legacy sync-helper dir $dir") {
                    val isLegacy = dir.resolve("bin").isDirectory() &&
                        isComplete(dir) &&
                        dir.getLastModifiedTime().toInstant() < cutoff
                    if (isLegacy) {
                        dir.deleteRecursively()
                        log.info("Removed legacy sync-helper dir: $dir")
                    }
                }
            }
        }
    }

    private fun deleteQuietly(path: Path) {
        bestEffort("delete $path") { path.deleteRecursively() }
    }

    /** Runs a cleanup step whose failure (e.g. Windows holding a file) must not fail the caller. */
    private inline fun bestEffort(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.info("Failed to $what", e)
        }
    }

    /** A regular file of the bundled distribution, relative to `sync-helper/` (e.g. `lib/core.jar`). */
    private interface BundledEntry {
        val relativePath: String

        /** Identifies the content: size and CRC from the jar's central directory, or a hash of the file. */
        fun contentId(): String

        fun copyTo(target: Path)
    }

    private fun fingerprintOf(entries: List<BundledEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { digest.update("${it.relativePath}:${it.contentId()}\n".toByteArray()) }
        return digest.digest().toHex().take(16)
    }

    /**
     * Enumerates the regular files under `sync-helper/bin` and `sync-helper/lib`, sorted by relative path, from the
     * plugin jar or (dev mode) the resource directory. Fingerprint and extraction both walk this list.
     */
    private fun <T> withBundledEntries(block: (List<BundledEntry>) -> T): T {
        val openJars = mutableListOf<JarFile>()
        try {
            val entries = BUNDLED_DIRS.flatMap { dir ->
                val resourcePath = "$RESOURCE_ROOT/$dir"
                val resourceUrl = javaClass.classLoader.getResource(resourcePath)
                    ?: throw IllegalStateException("Resource not found: $resourcePath")
                log.debug("Reading resource: $resourcePath from $resourceUrl")
                if (resourceUrl.protocol == "jar") {
                    jarEntries(JarFile(jarPath(resourceUrl)).also(openJars::add), resourcePath, dir)
                } else {
                    fileEntries(Path.of(resourceUrl.toURI()), dir)
                }
            }
            return block(entries.sortedBy { it.relativePath })
        } finally {
            openJars.forEach(JarFile::close)
        }
    }

    private fun jarPath(resourceUrl: URL): String =
        resourceUrl.toString().substringAfter("jar:file:").substringBefore("!")

    private fun jarEntries(jarFile: JarFile, resourcePath: String, dir: String): List<BundledEntry> =
        jarFile.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("$resourcePath/") }
            .map { entry ->
                object : BundledEntry {
                    override val relativePath = "$dir/${entry.name.removePrefix("$resourcePath/")}"
                    override fun contentId() = "${entry.size}:${entry.crc}"
                    override fun copyTo(target: Path) {
                        jarFile.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                    }
                }
            }
            .toList()

    private fun fileEntries(sourceDir: Path, dir: String): List<BundledEntry> {
        if (!sourceDir.isDirectory()) {
            throw IllegalStateException("Source directory does not exist: $sourceDir")
        }
        val files = Files.walk(sourceDir).use { paths -> paths.filter { it.isRegularFile() }.toList() }
        return files.map { source ->
            object : BundledEntry {
                override val relativePath = "$dir/${sourceDir.relativize(source).invariantSeparatorsPathString}"
                override fun contentId() =
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)).toHex()
                override fun copyTo(target: Path) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
