package com.projectjuggler.config

import com.projectjuggler.locators.IntelliJLocator
import com.projectjuggler.util.FileLockUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

/**
 * Handles migration from v1 to v2 directory structure.
 * Creates IDE-specific directory and migrates existing config, recent projects, and project metadata.
 */
class MigrationManager private constructor(
    private val baseDir: Path = IdeRegistry.getDefaultBaseDir())
{
    private val v2Dir = baseDir.resolve("v2")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /**
     * Check if migration from v1 is needed.
     */
    fun needsMigration(): Boolean {
        val v1ConfigFile = baseDir.resolve("config.json")
        return v1ConfigFile.exists() && !v2Dir.exists()
    }

    /**
     * Migrate v1 config to v2 format.
     * Creates IDE-specific directory based on configured intellijPath.
     */
    fun migrateV1ToV2(currentInstallation: IdeInstallation?) {
        val v1ConfigFile = baseDir.resolve("config.json")
        if (!v1ConfigFile.exists()) return

        val legacyConfig = loadLegacyConfig()
        val installation = determineInstallation(legacyConfig)
            ?: currentInstallation
            ?: IntelliJLocator.findAllInstallations().firstOrNull()
            ?: return // todo report something here???

        // Create v2 directory for this IDE
        val ideDir = v2Dir.resolve(installation.directoryName)
        Files.createDirectories(ideDir)

        // Create new config format
        val newConfig = IdeConfig(
            installation = installation,
            baseVmOptionsPath = legacyConfig.baseVmOptionsPath,
            baseVmOptionsHash = legacyConfig.baseVmOptionsHash,
            maxRecentProjects = legacyConfig.maxRecentProjects,
            basePluginsPath = legacyConfig.basePluginsPath,
            baseConfigPath = legacyConfig.baseConfigPath,
            mainProjectPath = legacyConfig.mainProjectPath
        )
        saveIdeConfig(ideDir.resolve("config.json"), newConfig)

        // Copy recent.json
        val v1Recent = baseDir.resolve("recent.json")
        if (v1Recent.exists()) {
            v1Recent.copyTo(ideDir.resolve("recent.json"), overwrite = true)
        }

        // Move projects directory
        val v1Projects = baseDir.resolve("projects")
        if (v1Projects.exists() && v1Projects.isDirectory()) {
            moveDirectory(v1Projects, ideDir.resolve("projects"))
        }

        // Archive v1 files (don't delete, keep for rollback)
        archiveV1Files()
    }

    private fun loadLegacyConfig(): LegacyGlobalConfig {
        val v1ConfigFile = baseDir.resolve("config.json")
        return FileLockUtils.withFileLock(v1ConfigFile) {
            json.decodeFromString<LegacyGlobalConfig>(v1ConfigFile.readText())
        }
    }

    private fun saveIdeConfig(configFile: Path, config: IdeConfig) {
        FileLockUtils.withFileLock(configFile) {
            configFile.writeText(json.encodeToString(config))
        }
    }

    private fun determineInstallation(config: LegacyGlobalConfig): IdeInstallation? {
        val configuredPath = config.intellijPath ?: return null
        return IntelliJLocator.findInstallation(configuredPath)
    }

    private fun moveDirectory(source: Path, target: Path) {
        if (!source.exists()) return

        Files.createDirectories(target.parent)

        // Try atomic move first
        try {
            source.moveTo(target, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (e: Exception) {
            // Atomic move failed (e.g., cross-filesystem), fall back to copy
        }

        // Copy recursively then delete
        Files.walk(source).use { stream ->
            stream.forEach { sourcePath ->
                val targetPath = target.resolve(source.relativize(sourcePath))
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    sourcePath.copyTo(targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        // Delete source after successful copy
        Files.walk(source)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    private fun archiveV1Files() {
        val v1ConfigFile = baseDir.resolve("config.json")
        val v1RecentFile = baseDir.resolve("recent.json")

        if (v1ConfigFile.exists()) {
            v1ConfigFile.moveTo(baseDir.resolve("config.json.v1.bak"), StandardCopyOption.REPLACE_EXISTING)
        }
        if (v1RecentFile.exists()) {
            v1RecentFile.moveTo(baseDir.resolve("recent.json.v1.bak"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun create() = MigrationManager(IdeRegistry.getDefaultBaseDir())
    }
}
