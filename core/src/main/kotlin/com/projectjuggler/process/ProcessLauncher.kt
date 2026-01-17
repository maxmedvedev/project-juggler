package com.projectjuggler.process

import com.projectjuggler.platform.Platform
import java.io.ByteArrayOutputStream
import java.nio.file.Path

interface ProcessLauncher {
    fun launch(executable: Path, args: List<String>, environment: Map<String, String> = emptyMap())
}

class ProcessLauncherImpl : ProcessLauncher {

    override fun launch(executable: Path, args: List<String>, environment: Map<String, String>) {
        // Log launch diagnostics
        System.err.println("[ProcessLauncher] Starting process:")
        System.err.println("  Executable: $executable")
        System.err.println("  Arguments: $args")
        if (environment.isNotEmpty()) {
            System.err.println("  Environment: $environment")
        }

        val processBuilder = when (Platform.current()) {
            Platform.WINDOWS -> createWindowsProcess(executable, args, environment)
            Platform.MACOS, Platform.LINUX -> createUnixProcess(executable, args, environment)
        }

        val process = processBuilder.start()
        object : Thread() {
            override fun run() {
                val started = System.currentTimeMillis()
                val stdoutBuffer = ByteArrayOutputStream()
                val stderrBuffer = ByteArrayOutputStream()

                while (System.currentTimeMillis() - started < 10000) {
                    sleep(100)
                    // Read available bytes without blocking
                    val output = process.inputStream.readNBytes(process.inputStream.available())
                    val errorOutput = process.errorStream.readNBytes(process.errorStream.available())
                    stdoutBuffer.write(output)
                    stderrBuffer.write(errorOutput)

                    if (!process.isAlive) {
                        val exitCode = process.exitValue()
                        if (exitCode != 0) {
                            System.err.println("[ProcessLauncher] Process failed:")
                            System.err.println("  Exit code: $exitCode")
                            if (stdoutBuffer.size() > 0) {
                                System.err.println("  Stdout: ${stdoutBuffer.toString(Charsets.UTF_8)}")
                            }
                            if (stderrBuffer.size() > 0) {
                                System.err.println("  Stderr: ${stderrBuffer.toString(Charsets.UTF_8)}")
                            }
                        }
                        break
                    }
                }
            }
        }.start() // todo use coroutines
    }

    private fun createWindowsProcess(
        executable: Path,
        args: List<String>,
        environment: Map<String, String>
    ): ProcessBuilder {
        // On Windows, use 'start' command to properly detach the process
        return ProcessBuilder()
            .command(listOf("cmd", "/c", "start", "", executable.toString()) + args)
            .apply {
                environment().putAll(environment)
            }
    }

    private fun createUnixProcess(
        executable: Path,
        args: List<String>,
        environment: Map<String, String>
    ): ProcessBuilder {
        // On Unix-like systems, directly launch the process
        // The process will be adopted by init when the parent (CLI) exits
        return ProcessBuilder()
            .command(listOf(executable.toString()) + args)
            .apply {
                environment().putAll(environment)
                // Inherit IO to avoid blocking on buffers
                inheritIO()
            }
    }
}
