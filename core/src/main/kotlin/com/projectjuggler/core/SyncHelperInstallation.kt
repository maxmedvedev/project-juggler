package com.projectjuggler.core

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.platform.Platform
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** An extracted sync-helper distribution (`bin/`, `lib/`) in [installDir] and the JVM in [javaHome] to run it on. */
@Serializable
data class SyncHelperInstallation(val javaHome: String, val installDir: String) {

    /**
     * The command line that runs the helper's [MAIN_CLASS]:
     * `<javaHome>/bin/java[.exe] -cp <cp> [-Dproject.juggler.base.dir=<baseDir>] <main class> <args>`, where `<cp>`
     * is the `*` wildcard in `<installDir>/lib`. `java` expands the wildcard itself, so no shell is needed.
     */
    fun command(baseDir: String?, args: List<String>, platform: Platform = Platform.current()): List<String> =
        buildList {
            add(java(platform).toString())
            add("-cp")
            add("${Paths.get(installDir).resolve("lib")}${File.separator}*")
            if (baseDir != null) add("-D${IdeRegistry.PROJECT_JUGGLER_BASE_DIR}=$baseDir")
            add(MAIN_CLASS)
            addAll(args)
        }

    private fun java(platform: Platform): Path =
        Paths.get(javaHome).resolve("bin").resolve(if (platform == Platform.WINDOWS) "java.exe" else "java")

    companion object {
        const val MAIN_CLASS = "com.projectjuggler.synchelper.SyncHelperMainKt"

        /** The helper jar; its presence marks a complete distribution. */
        const val MARKER = "lib/sync-helper.jar"
    }
}
