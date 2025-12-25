package com.ideajuggler.platform

import java.nio.file.Path

class ProcessLauncher {

    fun launch(executable: Path, args: List<String>, environment: Map<String, String> = emptyMap()) {
        val processBuilder = when (Platform.current()) {
            Platform.WINDOWS -> createWindowsProcess(executable, args, environment)
            Platform.MACOS, Platform.LINUX -> createUnixProcess(executable, args, environment)
        }

        processBuilder.start()
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
