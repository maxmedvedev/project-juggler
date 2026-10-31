package com.projectjuggler.config

import com.projectjuggler.locators.IntelliJLocator
import com.projectjuggler.util.ProjectLockUtils
import org.koin.core.context.GlobalContext
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
class IdeRegistry internal constructor(baseDir: Path = getDefaultBaseDir()) {
    private val v2Dir = baseDir.resolve("v2")

    /**
     * Get all IDE installations that have been registered (have config directories).
     */
    fun getRegisteredInstallations(): List<IdeInstallation> =
        registeredRepositories().map { it.installation }

    /**
     * Repositories of all dirs under `v2/` with a readable `config.json`, each carrying its stored installation.
     * Dirs with a missing, corrupt or blank-executable config are skipped (see [IdeConfigRepository.loadFromDir]).
     */
    private fun registeredRepositories(): List<IdeConfigRepository> {
        if (!v2Dir.exists() || !v2Dir.isDirectory()) {
            return emptyList()
        }

        return Files.list(v2Dir).use { stream ->
            stream.asSequence().mapNotNull { IdeConfigRepository.loadFromDir(it) }.toList()
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
     *
     * Identity is the executable path; the display name is cosmetic. Resolution order:
     * 1. `v2/<installation.directoryName>` has a `config.json` -> that dir with [installation];
     * 2. a registered dir whose stored installation has the same executable path (registered under another
     *    display name) -> that dir with its stored installation, so scopes key on its real dir name;
     * 3. otherwise a new `v2/<installation.directoryName>`, created on first write.
     */
    fun getRepository(installation: IdeInstallation): IdeConfigRepository {
        val exact = IdeConfigRepository.create(installation, v2Dir)
        if (exact.getConfigFile().exists()) return exact

        return registeredRepositories().firstOrNull { it.installation.executablePath == installation.executablePath }
            ?: exact
    }

    /**
     * Moves a dir registered under a legacy executable path (e.g. the IDE home directory older plugin versions
     * keyed on) to `v2/<installation.directoryName>` and stores [installation] in its `config.json`, keeping
     * `projects/` and `recent.json`.
     *
     * Returns null, changing nothing, unless the target dir does not exist, exactly one registered dir has a
     * stored executable path in [legacyExecutablePaths], and no isolated IDE runs from it (no
     * `projects/<id>/config/.lock`). Isolated `idea.vmoptions` files keep the old absolute paths until the next
     * launch rewrites their marker block.
     *
     * @throws IllegalStateException when the move fails
     */
    fun adoptLegacyRepository(
        installation: IdeInstallation,
        legacyExecutablePaths: Collection<String>,
    ): IdeConfigRepository? {
        val newDir = v2Dir.resolve(installation.directoryName)
        if (newDir.exists()) return null

        val legacy = registeredRepositories()
            .filter { it.installation.executablePath in legacyExecutablePaths }
            .singleOrNull() ?: return null
        if (ProjectLockUtils.hasIsolatedInstanceLock(legacy)) return null

        try {
            Files.move(legacy.baseDir, newDir)
        } catch (e: Throwable) {
            throw IllegalStateException("Failed to move ${legacy.baseDir} to $newDir: ${e.message}", e)
        }
        return IdeConfigRepository(newDir, installation).also { repository ->
            repository.update { it.copy(installation = installation) }
        }
    }

    companion object {
        const val PROJECT_JUGGLER_BASE_DIR = "project.juggler.base.dir"

        fun getDefaultBaseDir(): Path {
            val userSpecifiedDir = System.getProperty(PROJECT_JUGGLER_BASE_DIR)
            userSpecifiedDir?.let { return Paths.get(it) }

            return Paths.get(System.getProperty("user.home"), ".project-juggler")
        }

        fun getInstance(): IdeRegistry =
            GlobalContext.get().get()
    }
}
