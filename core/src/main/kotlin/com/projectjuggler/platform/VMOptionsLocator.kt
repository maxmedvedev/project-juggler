package com.projectjuggler.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object VMOptionsLocator {

    /**
     * Find the default IntelliJ VM options file.
     * Returns null if not found.
     */
    fun findDefaultVMOptions(): Path? {
        val candidates = when (Platform.current()) {
            Platform.MACOS -> getMacOSCandidates()
            Platform.LINUX -> getLinuxCandidates()
            Platform.WINDOWS -> getWindowsCandidates()
        }

        return candidates.firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
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
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.fileName.toString() } // Latest version first
                    .map { it.resolve("idea.vmoptions") }
                    .filter { Files.exists(it) && Files.isRegularFile(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getLinuxCandidates(): List<Path> {
        val userHome = System.getProperty("user.home")
        val baseDir = Paths.get(userHome, ".config", "JetBrains")

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return try {
            Files.list(baseDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("IntelliJIdea") }
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.fileName.toString() }
                    .map { it.resolve("idea.vmoptions") }
                    .filter { Files.exists(it) && Files.isRegularFile(it) }
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
                    .filter { Files.exists(it) && Files.isDirectory(it) }
                    .toList()
                    .sortedByDescending { it.fileName.toString() }
                    .map { it.resolve("idea.vmoptions") }
                    .filter { Files.exists(it) && Files.isRegularFile(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
