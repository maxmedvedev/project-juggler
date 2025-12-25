package com.ideajuggler.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object PluginLocator {

    /**
     * Find the default IntelliJ plugins directory.
     * Returns null if not found.
     */
    fun findDefaultPluginsDirectory(): Path? {
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

        return try {
            Files.list(baseDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("IntelliJIdea") }
                    .map { it.resolve("plugins") }
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.parent.fileName.toString() } // Latest version first
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getLinuxCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val baseDir = Paths.get(userHome, ".local", "share", "JetBrains")

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return try {
            Files.list(baseDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("IntelliJIdea") }
                    .map { it.resolve("plugins") }
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.parent.fileName.toString() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getWindowsCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val appData = System.getenv("APPDATA") ?: "$userHome\\AppData\\Roaming"
        val baseDir = Paths.get(appData, "JetBrains")

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return try {
            Files.list(baseDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("IntelliJIdea") }
                    .map { it.resolve("plugins") }
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.parent.fileName.toString() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
