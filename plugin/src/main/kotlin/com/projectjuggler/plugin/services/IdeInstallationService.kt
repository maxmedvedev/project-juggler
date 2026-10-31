package com.projectjuggler.plugin.services

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.locators.IntelliJLocator
import com.projectjuggler.platform.Platform
import com.projectjuggler.plugin.rethrowIfControlFlow
import org.koin.core.context.GlobalContext
import java.nio.file.Path
import kotlin.io.path.exists

private val LOG = logger<IdeInstallationService>()

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
        adoptLegacyHomeKeyedRepository(installation)
        val repository = ideRegistry.getRepository(installation)

        // Auto-populate config if this is first run for this IDE
        IdePathSyncDetector.autoPopulateIfNeeded(repository)
        return repository
    }

    /**
     * Older plugin versions keyed the current IDE on its home directory when the `idea` launcher was missing
     * (other products). Moves such a dir to the executable-keyed one once, so recents and settings survive.
     */
    private fun adoptLegacyHomeKeyedRepository(installation: IdeInstallation) {
        val homePath = PathManager.getHomePath()
        try {
            val adopted = ideRegistry.adoptLegacyRepository(installation, listOf(homePath)) ?: return
            LOG.info("Adopted the ide dir keyed on $homePath as ${adopted.baseDir}")
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            LOG.warn("Failed to adopt the ide dir keyed on $homePath; continuing without it", e)
        }
    }

    /**
     * Detect the currently running IDE installation, keyed on its launcher (never on the home directory).
     */
    private fun detectCurrentInstallation(): IdeInstallation {
        val executable = currentIdeExecutable(
            Path.of(PathManager.getHomePath()),
            ApplicationNamesInfo.getInstance().scriptName,
            Platform.current(),
        )
        if (!executable.exists()) {
            LOG.warn("IDE launcher $executable does not exist; launches from this IDE will fail")
        }
        return IntelliJLocator.findInstallation(executable.toString())
    }

    companion object {
        fun getInstance(): IdeInstallationService =
            GlobalContext.get().get()

        val currentIdeConfigRepository: IdeConfigRepository
            get() = getInstance().currentRepository
    }
}
