package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.di.getScope
import com.projectjuggler.platform.Platform
import com.projectjuggler.util.FileLockUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * An extracted sync-helper distribution (`bin/`, `lib/`) in [installDir] and the JVM in [javaHome] to run it on.
 * Each IDE records its own in its repository ([SyncHelperInstallationStore]), so a helper started for another
 * IDE's data can hand over to that IDE's helper.
 */
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

    /** Whether the JVM and the helper jar are still there (the IDE may be gone, or its extraction deleted). */
    fun isUsable(platform: Platform = Platform.current()): Boolean =
        java(platform).isRegularFile() && Paths.get(installDir).resolve(MARKER).isRegularFile()

    private fun java(platform: Platform): Path =
        Paths.get(javaHome).resolve("bin").resolve(if (platform == Platform.WINDOWS) "java.exe" else "java")

    companion object {
        const val MAIN_CLASS = "com.projectjuggler.synchelper.SyncHelperMainKt"

        /** The helper jar; its presence marks a complete distribution. */
        const val MARKER = "lib/sync-helper.jar"
    }
}

/** Stores the IDE's own [SyncHelperInstallation] in `<ideDir>/sync-helper.json`. */
class SyncHelperInstallationStore internal constructor(private val configRepository: IdeConfigRepository) {

    fun write(installation: SyncHelperInstallation) {
        val file = getFile()
        FileLockUtils.withFileLock(file) {
            file.writeText(ShutdownSignalManager.json.encodeToString(installation))
        }
    }

    /** The recorded installation, or null when there is none, and also for an empty or corrupt file. */
    fun read(): SyncHelperInstallation? {
        val file = getFile()
        if (!file.exists()) return null
        return FileLockUtils.withFileLock(file) {
            val text = file.readText()
            if (text.isBlank()) return@withFileLock null
            try {
                ShutdownSignalManager.json.decodeFromString<SyncHelperInstallation>(text)
            } catch (e: Throwable) {
                System.err.println("[SyncHelperInstallationStore] Ignoring corrupt $file: $e")
                null
            }
        }
    }

    internal fun getFile(): Path = configRepository.baseDir.resolve(FILE_NAME)

    companion object {
        const val FILE_NAME = "sync-helper.json"

        fun getInstance(ideConfigRepository: IdeConfigRepository): SyncHelperInstallationStore =
            ideConfigRepository.getScope().get()
    }
}
