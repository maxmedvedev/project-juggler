package com.projectjuggler.plugin.services

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.locators.IntelliJLocator
import com.projectjuggler.platform.Platform
import org.koin.core.context.GlobalContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Central service for managing IDE installations and their configurations.
 * Provides access to the current IDE's repository and all available IDE installations.
 */
class IdeInstallationService internal constructor(
    private val ideRegistry: IdeRegistry
) {
    /**
     * The current IDE installation (the one running this plugin).
     */
    val currentInstallation: IdeInstallation by lazy { detectCurrentInstallation() }

    /**
     * Repository for current IDE, initializing config if needed.
     */
    val currentRepository: IdeConfigRepository by lazy { detectCurrentRepository() }

    /** Get all available IDE installations (detected + registered). */
    fun getAllInstallations(): List<IdeInstallation> = ideRegistry.getAllInstallations()

    /** Get repository for a specific IDE installation. */
    fun getRepository(installation: IdeInstallation): IdeConfigRepository = ideRegistry.getRepository(installation)

    private fun detectCurrentRepository(): IdeConfigRepository {
        val installation = currentInstallation
        val repository = ideRegistry.getRepository(installation)

        // Auto-populate config if this is first run for this IDE
        IdePathSyncDetector.autoPopulateIfNeeded(repository)
        return repository
    }

    /**
     * Detect the currently running IDE installation.
     */
    private fun detectCurrentInstallation(): IdeInstallation {
        val executablePath = getIntelliJExecutablePath()

        // Try to find matching installation from detected list
        if (executablePath != null) {
            return IntelliJLocator.findInstallation(executablePath.toString())
        }

        // Create installation from detected path
        val path = PathManager.getHomePath()
        val displayName = IntelliJLocator.extractDisplayName(path)
        return IdeInstallation(path, displayName)
    }

    private fun getIntelliJExecutablePath(): Path? {
        try {
            val homeDir = Paths.get(PathManager.getHomePath())
            val platform = Platform.current()

            val executablePath = when (platform) {
                Platform.MACOS -> homeDir.resolve("MacOS").resolve("idea")
                Platform.LINUX -> homeDir.resolve("bin").resolve("idea.sh")
                Platform.WINDOWS -> homeDir.resolve("bin").resolve("idea64.exe")
            }

            return if (Files.exists(executablePath)) {
                executablePath
            } else null
        } catch (e: Exception) {
            logger<IdeInstallationService>().error("Failed to get IntelliJ executable path", e)
            return null
        }
    }

    companion object {
        fun getInstance(): IdeInstallationService =
            GlobalContext.get().get()

        val currentIdeConfigRepository: IdeConfigRepository
            get() = getInstance().currentRepository
    }
}
