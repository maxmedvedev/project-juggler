package com.projectjuggler.platform

import java.nio.file.Files

/**
 * Utility for focusing IDE windows across different platforms.
 * Uses platform-specific APIs to bring already-open IntelliJ windows to the front.
 */
object WindowFocuser {

    /**
     * Result of a window focus attempt.
     */
    sealed class FocusResult {
        /**
         * Window was successfully focused.
         */
        object Success : FocusResult()

        /**
         * Process with the given PID was not found.
         */
        data class ProcessNotFound(val pid: Int) : FocusResult()

        /**
         * Process exists but has no windows to focus.
         */
        data class WindowNotFound(val pid: Int) : FocusResult()

        /**
         * Platform command failed with an error.
         */
        data class CommandFailed(val error: String) : FocusResult()

        /**
         * Required tool is not installed (Linux only).
         */
        data class ToolNotInstalled(val toolName: String) : FocusResult()
    }

    /**
     * Attempts to focus the window associated with the given process ID.
     *
     * @param pid The process ID of the IntelliJ instance
     * @return FocusResult indicating success or specific failure reason
     */
    fun focus(pid: Int): FocusResult {
        return when (Platform.current()) {
            Platform.MACOS -> focusMacOS(pid)
            Platform.LINUX -> focusLinux(pid)
            Platform.WINDOWS -> focusWindows(pid)
        }
    }

    /**
     * macOS implementation using AppleScript.
     */
    private fun focusMacOS(pid: Int): FocusResult {
        return try {
            val script = "tell application \"System Events\" to set frontmost of first process whose unix id is $pid to true"

            val process = ProcessBuilder(
                "osascript",
                "-e",
                script
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            when {
                exitCode == 0 -> FocusResult.Success
                output.contains("Can't get process") || output.contains("doesn't understand") ->
                    FocusResult.ProcessNotFound(pid)
                else -> FocusResult.CommandFailed(output.trim().ifEmpty { "osascript returned exit code $exitCode" })
            }
        } catch (e: Exception) {
            FocusResult.CommandFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Linux implementation using wmctrl (primary) or xdotool (fallback).
     */
    private fun focusLinux(pid: Int): FocusResult {
        // Try wmctrl first (more reliable)
        if (isToolAvailable("wmctrl")) {
            return focusLinuxWithWmctrl(pid)
        }

        // Fall back to xdotool
        if (isToolAvailable("xdotool")) {
            return focusLinuxWithXdotool(pid)
        }

        // Neither tool is available
        return FocusResult.ToolNotInstalled("wmctrl or xdotool")
    }

    /**
     * Focus using wmctrl on Linux.
     */
    private fun focusLinuxWithWmctrl(pid: Int): FocusResult {
        return try {
            // First, find window ID for the given PID
            val findProcess = ProcessBuilder(
                "sh", "-c",
                "wmctrl -l -p | grep '\\s$pid\\s' | head -n 1 | awk '{print \$1}'"
            )
                .redirectErrorStream(true)
                .start()

            val windowId = findProcess.inputStream.bufferedReader().readText().trim()
            val findExitCode = findProcess.waitFor()

            if (findExitCode != 0 || windowId.isEmpty()) {
                return FocusResult.WindowNotFound(pid)
            }

            // Now activate the window
            val activateProcess = ProcessBuilder("wmctrl", "-i", "-a", windowId)
                .redirectErrorStream(true)
                .start()

            val activateExitCode = activateProcess.waitFor()

            if (activateExitCode == 0) {
                FocusResult.Success
            } else {
                val error = activateProcess.inputStream.bufferedReader().readText().trim()
                FocusResult.CommandFailed(error.ifEmpty { "wmctrl failed with exit code $activateExitCode" })
            }
        } catch (e: Exception) {
            FocusResult.CommandFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Focus using xdotool on Linux.
     */
    private fun focusLinuxWithXdotool(pid: Int): FocusResult {
        return try {
            val process = ProcessBuilder("xdotool", "search", "--pid", pid.toString(), "windowactivate")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            when {
                exitCode == 0 -> FocusResult.Success
                exitCode == 1 && output.isEmpty() -> FocusResult.WindowNotFound(pid)
                else -> FocusResult.CommandFailed(output.trim().ifEmpty { "xdotool failed with exit code $exitCode" })
            }
        } catch (e: Exception) {
            FocusResult.CommandFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Windows implementation using PowerShell with Win32 APIs.
     */
    private fun focusWindows(pid: Int): FocusResult {
        return try {
            val powershellScript = """
                Add-Type @"
                using System;
                using System.Runtime.InteropServices;

                public class WinAPI {
                    [DllImport("user32.dll")]
                    [return: MarshalAs(UnmanagedType.Bool)]
                    public static extern bool SetForegroundWindow(IntPtr hWnd);

                    [DllImport("user32.dll")]
                    [return: MarshalAs(UnmanagedType.Bool)]
                    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

                    [DllImport("user32.dll")]
                    [return: MarshalAs(UnmanagedType.Bool)]
                    public static extern bool IsIconic(IntPtr hWnd);

                    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

                    [DllImport("user32.dll")]
                    [return: MarshalAs(UnmanagedType.Bool)]
                    public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);

                    [DllImport("user32.dll")]
                    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
                }
"@

                ${"$"}pid = $pid
                ${"$"}hwnd = [IntPtr]::Zero

                [WinAPI+EnumWindowsProc] ${"$"}callback = {
                    param(${"$"}h, ${"$"}l)
                    ${"$"}procId = 0
                    [WinAPI]::GetWindowThreadProcessId(${"$"}h, [ref] ${"$"}procId)
                    if (${"$"}procId -eq ${"$"}pid) {
                        ${"$"}script:hwnd = ${"$"}h
                        return ${"$"}false
                    }
                    return ${"$"}true
                }

                [WinAPI]::EnumWindows(${"$"}callback, [IntPtr]::Zero) | Out-Null

                if (${"$"}hwnd -ne [IntPtr]::Zero) {
                    if ([WinAPI]::IsIconic(${"$"}hwnd)) {
                        [WinAPI]::ShowWindow(${"$"}hwnd, 9) | Out-Null
                    }
                    [WinAPI]::SetForegroundWindow(${"$"}hwnd) | Out-Null
                    exit 0
                } else {
                    exit 1
                }
            """.trimIndent()

            // Write script to temp file
            val scriptFile = Files.createTempFile("focus_window_", ".ps1")
            try {
                Files.writeString(scriptFile, powershellScript)

                val process = ProcessBuilder(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-NoProfile",
                    "-File", scriptFile.toString()
                )
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                when (exitCode) {
                    0 -> FocusResult.Success
                    1 -> FocusResult.WindowNotFound(pid)
                    else -> FocusResult.CommandFailed(output.trim().ifEmpty { "PowerShell failed with exit code $exitCode" })
                }
            } finally {
                Files.deleteIfExists(scriptFile)
            }
        } catch (e: Exception) {
            FocusResult.CommandFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Checks if a command-line tool is available on the system.
     */
    private fun isToolAvailable(toolName: String): Boolean {
        return try {
            val process = ProcessBuilder("which", toolName)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
