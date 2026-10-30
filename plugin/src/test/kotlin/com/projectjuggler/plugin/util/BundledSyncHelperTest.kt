package com.projectjuggler.plugin.util

import com.projectjuggler.platform.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

@OptIn(ExperimentalPathApi::class)
class BundledSyncHelperTest : StringSpec({

    // BundledSyncHelper never removes what it extracts (plugin-spec gap 11); the spec cleans up after itself
    val extractedDirs = mutableListOf<Path>()

    fun extractedDir(executable: Path): Path = executable.parent.parent.also { extractedDirs.add(it) }

    afterSpec {
        extractedDirs.filter { it.exists() }.forEach { it.deleteRecursively() }
    }

    "extracts the bundled distribution to a temp directory" {
        val executable = BundledSyncHelper.getExecutable()
        val helperDir = extractedDir(executable)

        executable.isRegularFile() shouldBe true
        executable.parent.name shouldBe "bin"
        executable.name shouldBe if (Platform.isWindows()) "sync-helper.bat" else "sync-helper"
        helperDir.name shouldStartWith "sync-helper-"
        if (!Platform.isWindows()) {
            Files.isExecutable(executable) shouldBe true
        }
        helperDir.resolve("lib").listDirectoryEntries("*.jar").shouldNotBeEmpty()
    }

    "returns the cached executable on the second call" {
        val first = BundledSyncHelper.getExecutable()
        extractedDir(first)

        BundledSyncHelper.getExecutable() shouldBe first
    }

    "re-extracts when the cached directory is gone" {
        val first = BundledSyncHelper.getExecutable()
        extractedDir(first).deleteRecursively()

        val second = BundledSyncHelper.getExecutable()
        extractedDir(second)

        second shouldNotBe first
        second.isRegularFile() shouldBe true
    }
})
