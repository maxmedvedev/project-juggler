package com.projectjuggler.config

import com.projectjuggler.platform.IntelliJLocator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

/**
 * Central registry for managing multiple IDE installations.
 * Provides discovery of available IDEs and access to their repositories.
 */
class IdeRegistry(baseDir: Path = getDefaultBaseDir()) {
    private val v2Dir = baseDir.resolve("v2")

    /**
     * Get all IDE installations that have been registered (have config directories).
     */
    fun getRegisteredInstallations(): List<IdeInstallation> {
        if (!v2Dir.exists() || !v2Dir.isDirectory()) {
            return emptyList()
        }

        return Files.list(v2Dir).use { stream ->
            stream.asSequence().mapNotNull { ideDir ->
                val ideConfigRepository = IdeConfigRepository.loadFromDir(ideDir)
                ideConfigRepository?.installation
            }.toList()
        }
    }

    /**
     * Get all available IDE installations (detected + registered, deduplicated).
     */
    fun getAllInstallations(): List<IdeInstallation> {
        val detected = IntelliJLocator.findAllInstallations()
        val registered = getRegisteredInstallations()
        return (detected + registered).distinctBy { it.executablePath }
    }

    /**
     * Get or create repository for an IDE installation.
     */
    fun getRepository(installation: IdeInstallation): IdeConfigRepository {
        return IdeConfigRepository.create(installation, v2Dir)
    }

    companion object {
        fun getDefaultBaseDir(): Path {
            val userSpecifiedDir = System.getProperty("project.juggler.base.dir")
            userSpecifiedDir?.let { return Paths.get(it) }

            return Paths.get(System.getProperty("user.home"), ".project-juggler")
        }

        fun create() = IdeRegistry(getDefaultBaseDir())
    }
}
