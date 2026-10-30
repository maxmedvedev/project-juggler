package com.projectjuggler.config

import com.projectjuggler.test.createTempDir
import com.projectjuggler.util.FileLockUtils
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class IdeConfigRepositoryTest : StringSpec({
    val installation = IdeInstallation("/test/ide", "Test IDE")

    fun newRepository() = IdeConfigRepository(createTempDir("test-repo"), installation)

    fun metadata(path: String, openCount: Int = 0) = ProjectMetadata(ProjectPath(path), "2026-10-30T10:00:00Z", openCount)

    "load without a config file returns the default config" {
        newRepository().load() shouldBe IdeConfig.default(installation)
    }

    "save creates baseDir and load reads the saved config back" {
        val baseDir = createTempDir("test-repo").resolve("nested/ide")
        val repository = IdeConfigRepository(baseDir, installation)
        val config = IdeConfig(installation, baseConfigPath = "/base/config", maxRecentProjects = 5)

        repository.save(config)

        baseDir.resolve("config.json").exists() shouldBe true
        repository.load() shouldBe config
    }

    "update applies the transform" {
        val repository = newRepository()

        repository.update { it.copy(mainProjectPath = "/work/main") }

        repository.load().mainProjectPath shouldBe "/work/main"
    }

    "saveProjectMetadata writes projects/<id>/metadata.json and loads by path and id" {
        val repository = newRepository()
        val saved = metadata("/work/repo", openCount = 2)

        repository.saveProjectMetadata(saved)

        repository.baseDir.resolve("projects").resolve(saved.id.id).resolve("metadata.json").exists() shouldBe true
        repository.loadProjectMetadata(saved.path) shouldBe saved
        repository.loadProjectMetadata(saved.id) shouldBe saved
    }

    "loadProjectMetadata returns null when missing" {
        val repository = newRepository()

        repository.loadProjectMetadata(ProjectPath("/work/missing")) shouldBe null
        repository.loadProjectMetadata(ProjectId("missing")) shouldBe null
    }

    "loadAllProjects returns all saved and skips dirs without metadata" {
        val repository = newRepository()
        val first = metadata("/work/first")
        val second = metadata("/work/second")
        repository.saveProjectMetadata(first)
        repository.saveProjectMetadata(second)
        Files.createDirectories(repository.baseDir.resolve("projects/stray"))

        repository.loadAllProjects() shouldContainExactlyInAnyOrder listOf(first, second)
    }

    "loadAllProjects is empty without a projects dir" {
        newRepository().loadAllProjects().shouldBeEmpty()
    }

    "deleteProjectMetadata removes only metadata.json" {
        val repository = newRepository()
        val saved = metadata("/work/repo")
        repository.saveProjectMetadata(saved)
        val projectDir = repository.baseDir.resolve("projects").resolve(saved.id.id)
        Files.createDirectories(projectDir.resolve("config"))

        repository.deleteProjectMetadata(saved.path)

        projectDir.resolve("metadata.json").exists() shouldBe false
        projectDir.resolve("config").exists() shouldBe true
        repository.loadProjectMetadata(saved.path) shouldBe null
    }

    "deleteProjectMetadata is a no-op when missing" {
        val repository = newRepository()

        repository.deleteProjectMetadata(ProjectPath("/work/missing"))

        repository.baseDir.resolve("projects").exists() shouldBe false
    }

    "create resolves the installation directory name under the registry dir" {
        val registryDir = createTempDir("test-registry")

        val repository = IdeConfigRepository.create(installation, registryDir)

        repository.baseDir shouldBe registryDir.resolve(installation.directoryName)
        repository.installation shouldBe installation
    }

    "loadFromDir returns null without config.json" {
        IdeConfigRepository.loadFromDir(createTempDir("test-ide-dir")) shouldBe null
    }

    "loadFromDir returns the saved installation" {
        val repository = newRepository()
        repository.save(IdeConfig.default(installation))

        val loaded = IdeConfigRepository.loadFromDir(repository.baseDir)

        loaded?.installation shouldBe installation
        loaded?.baseDir shouldBe repository.baseDir
    }

    fun ideDirWithConfig(text: String) = createTempDir("test-ide-dir").also { it.resolve("config.json").writeText(text) }

    "loadFromDir returns null for corrupt config" {
        IdeConfigRepository.loadFromDir(ideDirWithConfig("{not json")) shouldBe null
    }

    "loadFromDir returns null for empty config file" {
        IdeConfigRepository.loadFromDir(ideDirWithConfig("")) shouldBe null
    }

    "loadFromDir returns null for blank executable path" {
        val dir = ideDirWithConfig("""{"installation": {"executablePath": "", "displayName": "x"}}""")

        IdeConfigRepository.loadFromDir(dir) shouldBe null
    }

    "load returns default for corrupt config" {
        val repository = newRepository()
        repository.getConfigFile().writeText("{not json")

        repository.load() shouldBe IdeConfig.default(installation)
    }

    "update replaces a corrupt config with the transformed default" {
        val repository = newRepository()
        repository.getConfigFile().writeText("{not json")

        repository.update { it.copy(maxRecentProjects = 3) }

        repository.load() shouldBe IdeConfig.default(installation).copy(maxRecentProjects = 3)
    }

    "update holds one lock for read-transform-write" {
        val repository = newRepository()
        repository.save(IdeConfig.default(installation))
        mockkObject(FileLockUtils)
        try {
            repository.update { it.copy(mainProjectPath = "/work/main") }

            verify(exactly = 1) { FileLockUtils.withFileLock<Any?>(any(), any()) }
        } finally {
            unmockkObject(FileLockUtils)
        }
        repository.load().mainProjectPath shouldBe "/work/main"
    }

    "loadAllProjects skips a corrupt metadata file" {
        val repository = newRepository()
        val valid = metadata("/work/valid")
        repository.saveProjectMetadata(valid)
        val corruptId = ProjectPath("/work/corrupt").id
        val corruptDir = repository.baseDir.resolve("projects").resolve(corruptId.id)
        Files.createDirectories(corruptDir)
        corruptDir.resolve("metadata.json").writeText("garbage")

        repository.loadAllProjects() shouldContainExactlyInAnyOrder listOf(valid)
        repository.loadProjectMetadata(corruptId) shouldBe null
    }
})
