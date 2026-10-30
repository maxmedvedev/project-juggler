package com.projectjuggler.config

import com.projectjuggler.util.FileLockUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

/**
 * Per-IDE configuration repository.
 * Each IDE installation has its own repository with isolated config, recent projects, and project metadata.
 */
class IdeConfigRepository(
    val baseDir: Path,
    val installation: IdeInstallation
) {
    private val configFile = baseDir.resolve("config.json")
    private val projectsDir = baseDir.resolve("projects")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    fun load(): IdeConfig = readConfigOrNull() ?: IdeConfig.default(installation)

    fun save(config: IdeConfig) {
        Files.createDirectories(baseDir)
        FileLockUtils.withFileLock(configFile) {
            writeConfig(config)
        }
    }

    /**
     * Reads, transforms and writes the config under one lock, so concurrent updates from
     * other processes are not lost.
     */
    fun update(transform: (IdeConfig) -> IdeConfig) {
        Files.createDirectories(baseDir)
        FileLockUtils.withFileLock(configFile) {
            val current = decodeConfigOrNull() ?: IdeConfig.default(installation)
            writeConfig(transform(current))
        }
    }

    /** Returns the stored config, or null when the file is missing, empty or corrupt. */
    private fun readConfigOrNull(): IdeConfig? {
        if (!configFile.exists()) return null
        return try {
            FileLockUtils.withFileLock(configFile) { decodeConfigOrNull() }
        } catch (e: Throwable) {
            logIgnoredCorruptFile(configFile, e)
            null
        }
    }

    /** Decodes the config file; must be called under the config lock. */
    private fun decodeConfigOrNull(): IdeConfig? {
        val text = configFile.readText()
        if (text.isBlank()) return null
        return try {
            json.decodeFromString<IdeConfig>(text)
        } catch (e: Throwable) {
            logIgnoredCorruptFile(configFile, e)
            null
        }
    }

    private fun writeConfig(config: IdeConfig) {
        configFile.writeText(json.encodeToString(config))
    }

    fun getConfigFile(): Path = configFile

    fun saveProjectMetadata(metadata: ProjectMetadata) {
        val projectDir = projectsDir.resolve(metadata.id)
        val metadataFile = projectDir.resolve("metadata.json")
        Files.createDirectories(projectDir)
        FileLockUtils.withFileLock(metadataFile) {
            metadataFile.writeText(json.encodeToString(metadata))
        }
    }

    fun loadProjectMetadata(projectPath: ProjectPath): ProjectMetadata? =
        loadProjectMetadata(projectPath.id)

    fun loadProjectMetadata(projectId: ProjectId): ProjectMetadata? {
        val projectDir = projectsDir.resolve(projectId)
        return loadMetadata(projectDir)
    }

    private fun loadMetadata(projectDir: Path): ProjectMetadata? {
        val metadataFile = projectDir.resolve("metadata.json")
        if (!metadataFile.exists()) return null
        return try {
            FileLockUtils.withFileLock(metadataFile) {
                json.decodeFromString<ProjectMetadata>(metadataFile.readText())
            }
        } catch (e: Throwable) {
            logIgnoredCorruptFile(metadataFile, e)
            null
        }
    }

    fun loadAllProjects(): List<ProjectMetadata> {
        if (!projectsDir.exists()) return emptyList()
        return Files.list(projectsDir).use { stream ->
            stream.asSequence().mapNotNull { projectDir ->
                loadMetadata(projectDir)
            }.toList()
        }
    }

    fun deleteProjectMetadata(projectPath: ProjectPath) {
        val metadataFile = projectsDir.resolve(projectPath.id).resolve("metadata.json")
        if (!metadataFile.exists()) return
        FileLockUtils.withFileLock(metadataFile) {
            Files.delete(metadataFile)
        }
    }

    private fun Path.resolve(id: ProjectId) = resolve(id.id)

    companion object {
        /**
         * Creates a new IdeConfigRepository for the given installation and registry directory.
         */
        fun create(installation: IdeInstallation, ideRegistryDir: Path): IdeConfigRepository {
            val ideDir = ideRegistryDir.resolve(installation.directoryName)
            return IdeConfigRepository(ideDir, installation)
        }

        /**
         * Loads an IdeConfigRepository from the given directory, or returns null when it has no readable
         * `config.json` (missing, empty, corrupt, or with a blank executable path).
         */
        fun loadFromDir(ideDir: Path): IdeConfigRepository? {
            val config = IdeConfigRepository(ideDir, IdeInstallation("", "")).readConfigOrNull() ?: return null
            if (config.installation.executablePath.isBlank()) {
                System.err.println("[IdeConfigRepository] Ignoring $ideDir: config.json has a blank executable path")
                return null
            }
            return IdeConfigRepository(ideDir, config.installation)
        }

        private fun logIgnoredCorruptFile(file: Path, e: Throwable) {
            System.err.println("[IdeConfigRepository] Ignoring corrupt $file: ${e.message}")
        }
    }
}
