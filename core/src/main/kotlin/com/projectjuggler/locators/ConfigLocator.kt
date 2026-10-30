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
    fun findDefaultConfigDirectory(): Path? = findDefaultConfigDirectory(LocatorEnvironment.current())

    internal fun findDefaultConfigDirectory(env: LocatorEnvironment): Path? {
        val candidates = when (env.platform) {
            Platform.MACOS -> getMacOSCandidates(env)
            Platform.LINUX -> getLinuxCandidates(env)
            Platform.WINDOWS -> getWindowsCandidates(env)
        }

        return candidates.firstOrNull { Files.exists(it) && Files.isDirectory(it) }
    }

    private fun getMacOSCandidates(env: LocatorEnvironment): List<Path> {
        val baseDir = env.userHome.resolve(Paths.get("Library", "Application Support", "JetBrains"))

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return getCandidates(baseDir)
    }

    private fun getLinuxCandidates(env: LocatorEnvironment): List<Path> {
        val baseDir = env.userHome.resolve(Paths.get(".local", "share", "JetBrains"))

        if (!Files.exists(baseDir)) {
            return emptyList()
        }

        return getCandidates(baseDir)
    }

    private fun getWindowsCandidates(env: LocatorEnvironment): List<Path> {
        val appData = env.getenv("APPDATA") ?: "${env.userHome}\\AppData\\Roaming"
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