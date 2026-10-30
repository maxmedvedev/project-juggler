package com.projectjuggler.process

import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.platform.Platform
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contains details about a process launch failure.
 */
data class LaunchFailure(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * Process output to show for a failure: stderr, else stdout, limited to 500 characters; blank if there was none.
 */
fun LaunchFailure.describe(): String =
    stderr.ifBlank { stdout }.trim().take(500)

interface ProcessLauncher {
    /**
     * Starts [executable] with [args]. [environment] is merged over the inherited environment; a `null` value
     * removes the variable from the child environment.
     */
    fun launch(
        executable: Path,
        args: List<String>,
        environment: Map<String, String?> = emptyMap(),
        onFailure: ((LaunchFailure) -> Unit)? = null
    )
}

/**
 * Launches processes detached from the caller. On macOS/Linux the child's stdout/stderr go to
 * `launch-<millis>-<n>.out`/`.err` in [logDirectory]; the files are kept only when the child exits non-zero
 * within [WATCH_TIMEOUT_SECONDS].
 */
class ProcessLauncherImpl(
    private val logDirectory: Path = IdeRegistry.getDefaultBaseDir().resolve("launcher-logs")
) : ProcessLauncher {

    override fun launch(
        executable: Path,
        args: List<String>,
        environment: Map<String, String?>,
        onFailure: ((LaunchFailure) -> Unit)?
    ) {
        // Log launch diagnostics
        System.err.println("[ProcessLauncher] Starting process:")
        System.err.println("  Executable: $executable")
        System.err.println("  Arguments: $args")
        if (environment.isNotEmpty()) {
            System.err.println("  Environment: ${environment.mapValues { it.value ?: "<unset>" }}")
        }

        pruneOldLogs()

        val logs: LaunchLogs?
        val processBuilder = when (Platform.current()) {
            Platform.WINDOWS -> {
                logs = null
                createWindowsProcess(executable, args, environment)
            }
            Platform.MACOS, Platform.LINUX -> {
                logs = createLogFiles()
                createUnixProcess(executable, args, environment, logs)
            }
        }

        val process = try {
            processBuilder.start()
        } catch (t: Throwable) {
            logs?.delete()
            throw t
        }
        Thread({ watch(process, logs, onFailure) }, WATCHER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    private fun watch(process: Process, logs: LaunchLogs?, onFailure: ((LaunchFailure) -> Unit)?) {
        try {
            val exited = process.waitFor(WATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val exitCode = if (exited) process.exitValue() else 0
            if (exitCode == 0) {
                logs?.delete()
                return
            }

            val stdout = tail(logs?.stdout)
            val stderr = tail(logs?.stderr)
            System.err.println("[ProcessLauncher] Process failed:")
            System.err.println("  Exit code: $exitCode")
            if (stdout.isNotBlank()) {
                System.err.println("  Stdout: $stdout")
            }
            if (stderr.isNotBlank()) {
                System.err.println("  Stderr: $stderr")
            }
            if (logs != null) {
                System.err.println("  Logs: ${logs.stdout}, ${logs.stderr}")
            }
            onFailure?.invoke(LaunchFailure(exitCode, stdout, stderr))
        } catch (t: Throwable) {
            System.err.println("[ProcessLauncher] Launch watcher failed: ${t.message}")
            t.printStackTrace()
        }
    }

    /** Creates a fresh pair of log files, or returns `null` (output discarded) when that is not possible. */
    private fun createLogFiles(): LaunchLogs? = try {
        Files.createDirectories(logDirectory)
        val stem = "launch-${System.currentTimeMillis()}-${launchCounter.incrementAndGet()}"
        LaunchLogs(
            stdout = Files.createFile(logDirectory.resolve("$stem.out")),
            stderr = Files.createFile(logDirectory.resolve("$stem.err"))
        )
    } catch (t: Throwable) {
        System.err.println("[ProcessLauncher] Cannot create launch logs in $logDirectory, output is discarded: $t")
        null
    }

    private fun pruneOldLogs() {
        runCatching {
            if (!Files.isDirectory(logDirectory)) return
            val cutoff = Instant.now().minus(LOG_RETENTION)
            Files.list(logDirectory).use { entries ->
                entries
                    .filter { Files.isRegularFile(it) }
                    .filter { Files.getLastModifiedTime(it).toInstant() < cutoff }
                    .forEach { file -> runCatching { Files.deleteIfExists(file) } }
            }
        }
    }

    private fun createWindowsProcess(
        executable: Path,
        args: List<String>,
        environment: Map<String, String?>
    ): ProcessBuilder {
        // On Windows, use 'start' command to properly detach the process
        return ProcessBuilder()
            .command(listOf("cmd", "/c", "start", "", executable.toString()) + args)
            .apply {
                applyEnvironment(environment)
            }
    }

    private fun createUnixProcess(
        executable: Path,
        args: List<String>,
        environment: Map<String, String?>,
        logs: LaunchLogs?
    ): ProcessBuilder {
        // On Unix-like systems, directly launch the process
        // The process will be adopted by init when the parent (CLI) exits
        return ProcessBuilder()
            .command(listOf(executable.toString()) + args)
            .apply {
                applyEnvironment(environment)
                // Files instead of pipes: nothing has to drain them for the lifetime of the IDE
                redirectInput(ProcessBuilder.Redirect.INHERIT)
                if (logs != null) {
                    redirectOutput(logs.stdout.toFile())
                    redirectError(logs.stderr.toFile())
                } else {
                    redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    redirectError(ProcessBuilder.Redirect.DISCARD)
                }
            }
    }

    private class LaunchLogs(val stdout: Path, val stderr: Path) {
        fun delete() {
            runCatching { Files.deleteIfExists(stdout) }
            runCatching { Files.deleteIfExists(stderr) }
        }
    }

    companion object {
        const val WATCHER_THREAD_NAME = "project-juggler-launch-watcher"
        private const val WATCH_TIMEOUT_SECONDS = 10L
        private const val TAIL_BYTES = 64 * 1024L
        private val LOG_RETENTION = Duration.ofDays(7)
        private val launchCounter = AtomicInteger()

        /** Last [TAIL_BYTES] of [file] as UTF-8; empty when [file] is null or unreadable. */
        private fun tail(file: Path?): String {
            if (file == null) return ""
            return runCatching {
                Files.newByteChannel(file).use { channel ->
                    val size = channel.size()
                    val start = (size - TAIL_BYTES).coerceAtLeast(0)
                    channel.position(start)
                    val buffer = ByteBuffer.allocate((size - start).toInt())
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
                    String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
                }
            }.getOrDefault("")
        }
    }
}

/**
 * Merges [environment] into this builder's environment; a `null` value removes the variable.
 */
internal fun ProcessBuilder.applyEnvironment(environment: Map<String, String?>) {
    val target = environment()
    for ((key, value) in environment) {
        if (value == null) target.remove(key) else target[key] = value
    }
}
