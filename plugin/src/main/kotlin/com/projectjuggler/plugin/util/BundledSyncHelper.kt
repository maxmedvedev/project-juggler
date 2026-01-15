package com.projectjuggler.plugin.util

import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Manages extraction and execution of the bundled sync-helper distribution.
 */
object BundledSyncHelper {

    private val log = logger<BundledSyncHelper>()
    private var cachedExecutablePath: Path? = null

    /**
     * Gets the path to the sync-helper executable.
     * Extracts the bundled distribution on first use and caches the location.
     *
     * @return Path to the sync-helper executable
     * @throws IllegalStateException if sync-helper cannot be extracted or found
     */
    fun getExecutable(): Path {
        // Return cached if available and still valid
        cachedExecutablePath?.let {
            if (it.exists()) {
                return it
            }
        }

        // Extract bundled sync-helper to temp directory
        val helperDir = extractBundledSyncHelper()
        val executable = when (Platform.current()) {
            Platform.WINDOWS -> helperDir.resolve("bin/sync-helper.bat")
            else -> helperDir.resolve("bin/sync-helper")
        }

        if (!executable.exists()) {
            throw IllegalStateException("sync-helper executable not found at: $executable")
        }

        // Make executable on Unix
        if (Platform.current() != Platform.WINDOWS) {
            try {
                executable.toFile().setExecutable(true)
            } catch (e: Exception) {
                log.warn("Failed to make sync-helper executable", e)
            }
        }

        cachedExecutablePath = executable
        log.info("sync-helper executable located at: $executable")
        return executable
    }

    /**
     * Extracts the bundled sync-helper distribution from plugin resources to a temp directory.
     *
     * @return Path to the extracted sync-helper directory
     */
    private fun extractBundledSyncHelper(): Path {
        // Create temp directory for extraction
        val tempDir = Files.createTempDirectory("sync-helper-")
        log.info("Extracting bundled sync-helper to: $tempDir")

        try {
            // Get classloader to access bundled resources
            val classLoader = this.javaClass.classLoader

            // The sync-helper distribution is bundled under sync-helper/
            // Structure: sync-helper/bin/, sync-helper/lib/
            val resourceBasePath = "sync-helper"

            // Extract bin directory
            extractDirectory(classLoader, "$resourceBasePath/bin", tempDir.resolve("bin"))

            // Extract lib directory
            extractDirectory(classLoader, "$resourceBasePath/lib", tempDir.resolve("lib"))

            return tempDir
        } catch (e: Exception) {
            log.error("Failed to extract bundled sync-helper", e)
            throw IllegalStateException("Failed to extract bundled sync-helper distribution", e)
        }
    }

    /**
     * Extracts a directory from plugin resources to a target directory.
     */
    private fun extractDirectory(classLoader: ClassLoader, resourcePath: String, targetDir: Path) {
        Files.createDirectories(targetDir)

        // Get the resource URL for the directory
        val resourceUrl = classLoader.getResource(resourcePath)
            ?: throw IllegalStateException("Resource not found: $resourcePath")

        log.debug("Extracting resource: $resourcePath from $resourceUrl")

        // Handle JAR file resources
        if (resourceUrl.protocol == "jar") {
            extractFromJar(resourceUrl, resourcePath, targetDir)
        } else {
            // Handle file system resources (development mode)
            extractFromFileSystem(resourceUrl.toURI().let { java.io.File(it) }.toPath(), targetDir)
        }
    }

    /**
     * Extracts resources from a JAR file.
     */
    private fun extractFromJar(resourceUrl: java.net.URL, resourcePath: String, targetDir: Path) {
        val jarPath = resourceUrl.toString().substringAfter("jar:file:").substringBefore("!")
        val jarFile = java.util.jar.JarFile(jarPath)

        try {
            val entries = jarFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name

                // Check if this entry is under our resource path
                if (entryName.startsWith(resourcePath) && entryName != resourcePath && entryName != "$resourcePath/") {
                    val relativePath = entryName.removePrefix(resourcePath).removePrefix("/")
                    if (relativePath.isEmpty()) continue

                    val targetFile = targetDir.resolve(relativePath)

                    if (entry.isDirectory) {
                        Files.createDirectories(targetFile)
                    } else {
                        Files.createDirectories(targetFile.parent)
                        jarFile.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
        } finally {
            jarFile.close()
        }
    }

    /**
     * Extracts resources from the file system (development mode).
     */
    private fun extractFromFileSystem(sourceDir: Path, targetDir: Path) {
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw IllegalStateException("Source directory does not exist: $sourceDir")
        }

        Files.walk(sourceDir).forEach { source ->
            val relative = sourceDir.relativize(source)
            val target = targetDir.resolve(relative)

            if (Files.isDirectory(source)) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
