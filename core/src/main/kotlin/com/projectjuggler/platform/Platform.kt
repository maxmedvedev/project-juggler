package com.projectjuggler.platform

enum class Platform {
    MACOS,
    LINUX,
    WINDOWS;

    companion object {
        fun current(): Platform = fromOsName(System.getProperty("os.name"))

        internal fun fromOsName(name: String): Platform {
            val osName = name.lowercase()
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
