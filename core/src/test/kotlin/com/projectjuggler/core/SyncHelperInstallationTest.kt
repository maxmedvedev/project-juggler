package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.platform.Platform
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class SyncHelperInstallationTest : StringSpec({
    extensions(KoinTestExtension())

    val tempDir = createTempDir("sync-helper-installation")
    var counter = 0

    fun newStore(): SyncHelperInstallationStore = SyncHelperInstallationStore.getInstance(
        IdeConfigRepository(tempDir.resolve("repo-${counter++}"), IdeInstallation("/test/ide", "Test IDE"))
    )

    /** A fake JBR and helper distribution, with only the files [SyncHelperInstallation.isUsable] looks for. */
    fun fakeInstallation(withJava: Boolean = true, withJar: Boolean = true): SyncHelperInstallation {
        val root = tempDir.resolve("install-${counter++}")
        val javaHome = root.resolve("jbr")
        val installDir = root.resolve("helper")
        if (withJava) javaHome.resolve("bin").createDirectories().resolve("java").createFile()
        if (withJar) installDir.resolve("lib").createDirectories().resolve("sync-helper.jar").createFile()
        return SyncHelperInstallation(javaHome.toString(), installDir.toString())
    }

    "a written installation reads back" {
        val store = newStore()
        val installation = SyncHelperInstallation("/jbr", "/helper")

        store.write(installation)

        store.read() shouldBe installation
    }

    "a missing, empty or corrupt record reads as null" {
        val store = newStore()
        store.read().shouldBeNull()

        store.getFile().parent.createDirectories()
        store.getFile().writeText("")
        store.read().shouldBeNull()

        store.getFile().writeText("{ not json")
        store.read().shouldBeNull()
    }

    "an installation is usable only with its java and its jar" {
        fakeInstallation().isUsable(Platform.LINUX) shouldBe true
        fakeInstallation(withJava = false).isUsable(Platform.LINUX) shouldBe false
        fakeInstallation(withJar = false).isUsable(Platform.LINUX) shouldBe false
    }

    "command runs the main class on the given JVM on Unix" {
        val command = SyncHelperInstallation("/jbr", "/helper").command(null, listOf("--ide", "/ide"), Platform.MACOS)

        command shouldContainExactly listOf(
            Paths.get("/jbr/bin/java").toString(),
            "-cp",
            "${Path.of("/helper").resolve("lib")}${File.separator}*",
            SyncHelperInstallation.MAIN_CLASS,
            "--ide",
            "/ide"
        )
    }

    "command uses java.exe on Windows and passes the base dir on" {
        val command = SyncHelperInstallation("/jbr", "/helper").command("/sandbox", listOf("--all"), Platform.WINDOWS)

        command shouldContainExactly listOf(
            Paths.get("/jbr/bin/java.exe").toString(),
            "-cp",
            "${Path.of("/helper").resolve("lib")}${File.separator}*",
            "-Dproject.juggler.base.dir=/sandbox",
            SyncHelperInstallation.MAIN_CLASS,
            "--all"
        )
    }
})
