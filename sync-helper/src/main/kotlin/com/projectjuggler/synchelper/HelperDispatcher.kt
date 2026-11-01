package com.projectjuggler.synchelper

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.core.SyncHelperInstallation
import com.projectjuggler.core.SyncHelperInstallationStore
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Set in the environment of a helper started by [HelperDispatcher], which then never dispatches again. */
internal const val DISPATCHED_ENV = "PROJECT_JUGGLER_HELPER_DISPATCHED"

/**
 * Hands a command over to the helper the IDE recorded for itself ([SyncHelperInstallationStore]), so every IDE's
 * data is handled by that IDE's own helper and JVM, whichever IDE published the `<base>/bin/sync-helper` launcher.
 *
 * @param ownInstallDir This helper's distribution dir, or null when unknown (not run from a jar in `<dir>/lib`)
 * @param alreadyDispatched Whether this helper was itself started by a dispatch
 * @param startHelper Runs a command line with extra environment variables and returns its exit code
 */
internal class HelperDispatcher(
    private val ownInstallDir: Path? = detectOwnInstallDir(),
    private val alreadyDispatched: Boolean = System.getenv(DISPATCHED_ENV) != null,
    private val startHelper: (command: List<String>, environment: Map<String, String>) -> Int = ::startInheritingIo,
) {
    /**
     * Runs [args] in the helper recorded for [repository] and returns its exit code, or returns null to run them
     * in this process: after a dispatch, without a record, when the record is this helper, or when the recorded JVM
     * or jar is gone.
     */
    fun dispatch(repository: IdeConfigRepository, args: Array<String>): Int? {
        if (alreadyDispatched) return null
        val target = SyncHelperInstallationStore.getInstance(repository).read() ?: return null
        if (ownInstallDir != null && samePath(Paths.get(target.installDir), ownInstallDir)) return null
        val ide = repository.installation.displayName
        if (!target.isUsable()) {
            System.err.println("[sync-helper] Helper of $ide at ${target.installDir} is missing, running here")
            return null
        }

        println("[sync-helper] Running the helper of $ide: ${target.installDir}")
        val command = target.command(IdeRegistry.getDefaultBaseDir().toString(), args.toList())
        return startHelper(command, mapOf(DISPATCHED_ENV to "1"))
    }
}

/** `<dir>` when this class was loaded from `<dir>/lib/<jar>`, as in an extracted or installed distribution. */
private fun detectOwnInstallDir(): Path? = try {
    val location = Paths.get(HelperDispatcher::class.java.protectionDomain.codeSource.location.toURI())
    if (location.isRegularFile() && location.parent?.name == "lib") location.parent.parent else null
} catch (e: Throwable) {
    null
}

private fun samePath(a: Path, b: Path): Boolean = canonical(a) == canonical(b)

private fun canonical(path: Path): Path =
    runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

private fun startInheritingIo(command: List<String>, environment: Map<String, String>): Int =
    ProcessBuilder(command).inheritIO().apply { environment().putAll(environment) }.start().waitFor()
