package com.projectjuggler.locators

import com.projectjuggler.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ConfigLocator {

    /**
     * Find the default IntelliJ config directory.
     * Returns null if not found.
     */
    fun findDefaultConfigDirectory(): Path? {
        val candidates = when (Platform.current()) {
            Platform.MACOS -> getMacOSCandidates()
            Platform.LINUX -> getLinuxCandidates()
            Platform.WINDOWS -> getWindowsCandidates()
        }

        return candidates.firstOrNull { Files.exists(it) && Files.isDirectory(it) }
    }

    private fun getMacOSCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val baseDir = Paths.get(userHome, "Library", "Application Support", "JetBrains")

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return getCandidates(baseDir)
    }

    private fun getLinuxCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val baseDir = Paths.get(userHome, ".local", "share", "JetBrains")

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return getCandidates(baseDir)
    }

    private fun getWindowsCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
        val baseDir = Paths.get(appData, "JetBrains")

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return getCandidates(baseDir)
    }

    private fun getCandidates(baseDir: Path): List<Path> {
        return try {
            Files.list(baseDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("IntelliJIdea") }
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.fileName.toString() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}