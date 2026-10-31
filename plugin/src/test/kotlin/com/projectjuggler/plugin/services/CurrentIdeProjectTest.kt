package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class CurrentIdeProjectTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectDir = createTempDir("project")
    val otherProjectDir = createTempDir("other-project")
    val elsewhere = createTempDir("other-config")

    // A fresh repository per test: metadata and main-project settings must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    fun ownConfigDir(repository: IdeConfigRepository, projectPath: ProjectPath): Path =
        DirectoryManager.getInstance(repository).getProjectRoot(projectPath).resolve("config")

    fun setMainProject(repository: IdeConfigRepository, mainConfigDir: Path): ProjectPath {
        repository.update { it.copy(baseConfigPath = mainConfigDir.toString()) }
        val metadata = registerProject(repository, projectDir)
        MainProjectService.setMainProject(repository, metadata.path)
        return metadata.path
    }

    "an isolated project belongs to its own isolated IDE" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)

        belongsToCurrentIde(repository, metadata.path, ownConfigDir(repository, metadata.path)) shouldBe true
    }

    "an isolated project does not belong to another IDE" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val other = registerProject(repository, otherProjectDir)

        belongsToCurrentIde(repository, metadata.path, elsewhere) shouldBe false
        belongsToCurrentIde(repository, metadata.path, ownConfigDir(repository, other.path)) shouldBe false
    }

    "an unregistered path whose isolated config dir is the current config dir belongs to the current IDE" {
        val repository = newRepository()
        val projectPath = ProjectPath(projectDir.toString())

        belongsToCurrentIde(repository, projectPath, ownConfigDir(repository, projectPath)) shouldBe true
    }

    "the main project belongs to the main instance" {
        val repository = newRepository()
        val mainConfigDir = createTempDirectory(baseDir, "main-config")
        val mainProject = setMainProject(repository, mainConfigDir)

        belongsToCurrentIde(repository, mainProject, mainConfigDir) shouldBe true
    }

    "the main project does not belong to an isolated IDE" {
        val repository = newRepository()
        val mainConfigDir = createTempDirectory(baseDir, "main-config")
        val mainProject = setMainProject(repository, mainConfigDir)
        val other = registerProject(repository, otherProjectDir)

        belongsToCurrentIde(repository, mainProject, ownConfigDir(repository, other.path)) shouldBe false
    }

    "the main project does not belong to its former isolated IDE" {
        val repository = newRepository()
        val mainConfigDir = createTempDirectory(baseDir, "main-config")
        val mainProject = setMainProject(repository, mainConfigDir)

        belongsToCurrentIde(repository, mainProject, ownConfigDir(repository, mainProject)) shouldBe false
    }
})
