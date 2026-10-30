package com.projectjuggler.util

import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

internal data class GitCommandResult(val exitCode: Int, val output: String)

internal object GitCommandRunner {
    /**
     * Runs `git <args>` in [workingDir]; stdout and stderr are merged into [GitCommandResult.output].
     *
     * The output is drained on a daemon thread so that a `git` that never exits (credential prompt, hook, lock)
     * cannot block the caller past [timeout]. On timeout the process and its descendants are destroyed and an [IOException] is thrown.
     * An [IOException] from starting the process (e.g. `git` not on PATH) propagates unwrapped.
     */
    fun run(workingDir: Path, args: List<String>, timeout: Duration): GitCommandResult {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .start()

        val deadline = System.nanoTime() + timeout.toNanos()
        var output = ""
        var readError: Throwable? = null
        val reader = Thread {
            try {
                output = process.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Throwable) {
                readError = e
            }
        }.apply {
            isDaemon = true
            name = "git-output-reader"
            start()
        }

        reader.join(timeout.toMillis().coerceAtLeast(1))
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        if (reader.isAlive || !process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw IOException("git ${args.firstOrNull()} timed out after ${timeout.toSeconds()} s")
        }

        readError?.let { throw it }
        return GitCommandResult(process.exitValue(), output)
    }
}
