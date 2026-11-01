package com.projectjuggler.core

import com.projectjuggler.platform.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class SyncHelperInstallationTest : StringSpec({
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
