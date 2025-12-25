package com.ideajuggler.platform

enum class Platform {
    MACOS,
    LINUX,
    WINDOWS;

    companion object {
        fun current(): Platform {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("mac") || osName.contains("darwin") -> MACOS
                osName.contains("win") -> WINDOWS
                osName.contains("nux") || osName.contains("nix") || osName.contains("aix") -> LINUX
                else -> throw UnsupportedOperationException("Unsupported operating system: $osName")
            }
        }

        fun isUnix(): Boolean = current() in setOf(MACOS, LINUX)
        fun isWindows(): Boolean = current() == WINDOWS
    }
}
