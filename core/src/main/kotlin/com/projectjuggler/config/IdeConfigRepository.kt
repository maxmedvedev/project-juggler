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

    fun load(): IdeConfig {
        if (!configFile.exists()) {
            return IdeConfig.default(installation)
        }
        try {
            return FileLockUtils.withFileLock(configFile) {
                json.decodeFromString<IdeConfig>(configFile.readText())
            }
        }
        catch (e: Exception) {
            return IdeConfig.default(installation)
        }
    }

    fun save(config: IdeConfig) {
        Files.createDirectories(baseDir)
        FileLockUtils.withFileLock(configFile) {
            configFile.writeText(json.encodeToString(config))
        }
    }

    fun update(transform: (IdeConfig) -> IdeConfig) {
        val config = load()
        val updated = transform(config)
        save(updated)
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
        return FileLockUtils.withFileLock(metadataFile) {
            json.decodeFromString<ProjectMetadata>(metadataFile.readText())
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
         * Loads an IdeConfigRepository from the given directory if it exists, otherwise returns null.
         */
        fun loadFromDir(ideDir: Path): IdeConfigRepository? {
            val fakeInstallation = IdeInstallation("", "")
            val fakeRegistry = IdeConfigRepository(ideDir, fakeInstallation)

            if (!fakeRegistry.getConfigFile().exists()) return null
            val ideConfig = fakeRegistry.load()
            val installation = ideConfig.installation
            return IdeConfigRepository(ideDir, installation)
        }
    }
}
