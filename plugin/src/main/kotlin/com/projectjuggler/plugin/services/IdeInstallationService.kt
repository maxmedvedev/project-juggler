package com.projectjuggler.plugin.services

import com.intellij.diagnostic.VMOptions
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.config.MigrationManager
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.platform.IntelliJLocator
import com.projectjuggler.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Central service for managing IDE installations and their configurations.
 * Provides access to the current IDE's repository and all available IDE installations.
 */
@Service(Service.Level.APP)
class IdeInstallationService {
    private val ideRegistry = IdeRegistry.create()

    /**
     * The current IDE installation (the one running this plugin).
     */
    val currentInstallation: IdeInstallation by lazy { detectCurrentInstallation() }

    /**
     * Repository for current IDE, initializing config if needed.
     */
    val currentRepository: IdeConfigRepository by lazy { detectCurrentRepository() }

    init {
        // todo implement?
        // doMigrationFromV1toV2IfNeeded()
    }

    /** Get all available IDE installations (detected + registered). */
    fun getAllInstallations(): List<IdeInstallation> = ideRegistry.getAllInstallations()

    /** Get repository for a specific IDE installation. */
    fun getRepository(installation: IdeInstallation): IdeConfigRepository = ideRegistry.getRepository(installation)

    private fun detectCurrentRepository(): IdeConfigRepository {
        val installation = currentInstallation
        val repository = ideRegistry.getRepository(installation)

        // Auto-populate config if this is first run for this IDE
        autoPopulateIfNeeded(repository)
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

    /**
     * Auto-populate config with detected paths if this is first run.
     */
    fun autoPopulateIfNeeded(repository: IdeConfigRepository) {
        try {
            val config = repository.load()

            // Skip if already configured
            if (config.baseConfigPath != null) {
                return
            }

            val configPath = PathManager.getConfigDir()
            val vmOptionsPath = VMOptions.getUserOptionsFile()
                ?: VMOptions.getPlatformOptionsFile() // todo detect when a user-options file appears
            val pluginsPath = PathManager.getPluginsDir()

            repository.update { currentConfig ->
                var updated = currentConfig

                if (Files.exists(vmOptionsPath)) {
                    updated = updated.copy(baseVmOptionsPath = vmOptionsPath.toString())
                }

                updated = updated.copy(baseConfigPath = configPath.toString())
                updated = updated.copy(basePluginsPath = pluginsPath.toString())

                updated
            }

            // Update VM options hash if path exists
            if (Files.exists(vmOptionsPath)) {
                BaseVMOptionsTracker.getInstance(repository).updateHash()
            }

            logger<IdeInstallationService>().info("Auto-populated config for IDE: ${repository.installation.displayName}")
        } catch (e: Exception) {
            logger<IdeInstallationService>().error("Failed to auto-populate config", e)
        }
    }

    private fun doMigrationFromV1toV2IfNeeded() {
        // Run migration if needed
        val migrationManager = MigrationManager.create()
        if (migrationManager.needsMigration()) {
            try {
                val currentInstallation = currentInstallation
                migrationManager.migrateV1ToV2(currentInstallation)
                logger<IdeInstallationService>().info("Successfully migrated v1 config to v2")
            } catch (e: Exception) {
                logger<IdeInstallationService>().error("Failed to migrate v1 config to v2", e)
            }
        }
    }

    companion object {
        fun getInstance(): IdeInstallationService = service<IdeInstallationService>()
    }
}
