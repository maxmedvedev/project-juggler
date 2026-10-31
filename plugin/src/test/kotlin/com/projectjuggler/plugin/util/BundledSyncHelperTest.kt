package com.projectjuggler.plugin.util

import com.projectjuggler.platform.Platform
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime

@OptIn(ExperimentalPathApi::class)
class BundledSyncHelperTest : StringSpec({

    val tempDir = createTempDir("bundled-helper")
    val executableName = if (Platform.isWindows()) "sync-helper.bat" else "sync-helper"

    fun newRoot(): Path = createTempDirectory(tempDir, "root")

    fun legacyDir(parent: Path, name: String, jarName: String, withBin: Boolean, modified: Instant): Path {
        val dir = parent.resolve(name)
        if (withBin) dir.resolve("bin").createDirectories()
        dir.resolve("lib").createDirectories().resolve(jarName).createFile()
        dir.setLastModifiedTime(FileTime.from(modified))
        return dir
    }

    "removes legacy temp dirs once they are old and look like a helper extraction" {
        BundledSyncHelper.resetLegacyCleanupForTests()
        val legacyTempDir = createTempDirectory(tempDir, "tmp")
        val twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS)
        val old = legacyDir(legacyTempDir, "sync-helper-old", "sync-helper.jar", withBin = true, twoHoursAgo)
        val fresh = legacyDir(legacyTempDir, "sync-helper-fresh", "sync-helper.jar", withBin = true, Instant.now())
        val foreign = legacyDir(legacyTempDir, "sync-helper-foreign", "other.jar", withBin = false, twoHoursAgo)

        BundledSyncHelper.getExecutable(newRoot(), legacyTempDir)

        old.exists() shouldBe false
        fresh.exists() shouldBe true
        foreign.exists() shouldBe true
    }

    "extracts the bundled distribution into a fingerprint directory under the root" {
        val root = newRoot()

        val executable = BundledSyncHelper.getExecutable(root, null)

        val helperDir = root.resolve(BundledSyncHelper.fingerprint())
        executable shouldBe helperDir.resolve("bin").resolve(executableName)
        executable.isRegularFile() shouldBe true
        if (!Platform.isWindows()) {
            Files.isExecutable(executable) shouldBe true
        }
        helperDir.resolve("lib/sync-helper.jar").isRegularFile() shouldBe true
        helperDir.resolve("lib/core.jar").isRegularFile() shouldBe true
        root.listDirectoryEntries() shouldContainExactly listOf(helperDir)
    }

    "reuses the extraction on the second call" {
        val root = newRoot()
        val first = BundledSyncHelper.getExecutable(root, null)
        val coreJar = first.parent.parent.resolve("lib/core.jar")
        val oldTime = FileTime.from(Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
        coreJar.setLastModifiedTime(oldTime)

        BundledSyncHelper.getExecutable(root, null) shouldBe first
        coreJar.getLastModifiedTime() shouldBe oldTime
    }

    "re-extracts to the same path when the extraction was deleted" {
        val root = newRoot()
        val first = BundledSyncHelper.getExecutable(root, null)
        first.parent.parent.deleteRecursively()

        val second = BundledSyncHelper.getExecutable(root, null)

        second shouldBe first
        second.isRegularFile() shouldBe true
    }

    "removes other fingerprints and leftover temp dirs after an extraction" {
        val root = newRoot()
        val fingerprint = BundledSyncHelper.fingerprint()
        root.resolve("0000000000000000/bin").createDirectories().resolve("x").createFile()
        root.resolve("$fingerprint.tmp-123").createDirectories()

        BundledSyncHelper.getExecutable(root, null)

        root.listDirectoryEntries().map { it.name } shouldContainExactly listOf(fingerprint)
    }

    "returns an existing extraction as is and leaves its siblings alone" {
        val root = newRoot()
        val fingerprint = BundledSyncHelper.fingerprint()
        val executable = root.resolve(fingerprint).resolve("bin").createDirectories().resolve(executableName)
            .createFile()
        val sibling = root.resolve("0000000000000000").createDirectories()

        BundledSyncHelper.getExecutable(root, null) shouldBe executable
        root.listDirectoryEntries() shouldContainExactlyInAnyOrder listOf(executable.parent.parent, sibling)
    }

    "creates nothing in the system temp dir" {
        val systemTemp = Path(System.getProperty("java.io.tmpdir"))
        fun helperTempDirs() = systemTemp.listDirectoryEntries("sync-helper-*").toSet()
        val before = helperTempDirs()

        BundledSyncHelper.getExecutable(newRoot(), null)

        helperTempDirs() shouldBe before
    }

    "parallel calls extract once and return the same path" {
        val root = newRoot()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) { executor.submit(Callable { BundledSyncHelper.getExecutable(root, null) }) }
                .map { it.get(60, TimeUnit.SECONDS) }

            results.toSet().size shouldBe 1
            results.first().isRegularFile() shouldBe true
            root.listDirectoryEntries().size shouldBe 1
        } finally {
            executor.shutdownNow()
        }
    }

    "fingerprint is stable and 16 hex chars" {
        val fingerprint = BundledSyncHelper.fingerprint()

        fingerprint shouldMatch Regex("[0-9a-f]{16}")
        BundledSyncHelper.fingerprint() shouldBe fingerprint
    }

    "start writes the helper output to the log file" {
        val executable = BundledSyncHelper.getExecutable(newRoot(), null)
        val logFile = tempDir.resolve("logs").resolve(BundledSyncHelper.LOG_FILE_NAME)

        val process = BundledSyncHelper.start(executable, listOf("--bogus"), logFile)

        process.waitFor(60, TimeUnit.SECONDS) shouldBe true
        logFile.isRegularFile() shouldBe true
        logFile.fileSize() shouldBeGreaterThan 0L
    }
})
