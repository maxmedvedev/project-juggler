package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.markIsolatedInstanceOpen
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class LaunchPlanTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectDir = createTempDir("project")
    val otherProjectDir = createTempDir("other-project")
    val elsewhere = createTempDir("other-config")

    // A fresh repository per test: metadata and locks written by one test must not leak into the next
    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide"))

    fun ownConfigDir(repository: IdeConfigRepository, projectPath: ProjectPath): Path =
        DirectoryManager.getInstance(repository).getProjectRoot(projectPath).resolve("config")

    fun setBaseConfigPath(repository: IdeConfigRepository, path: Path) =
        repository.update { it.copy(baseConfigPath = path.toString()) }

    "an unregistered project is launched" {
        val repository = newRepository()

        planLaunch(repository, ProjectPath(projectDir.toString()), elsewhere) shouldBe LaunchStep.LAUNCH
    }

    "a registered project without a lock is launched" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)

        planLaunch(repository, metadata.path, elsewhere) shouldBe LaunchStep.LAUNCH
    }

    "a running project is focused" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        markIsolatedInstanceOpen(repository, metadata.path)

        planLaunch(repository, metadata.path, elsewhere) shouldBe LaunchStep.FOCUS
    }

    "project of the current isolated IDE opens in place even though its lock exists" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        markIsolatedInstanceOpen(repository, metadata.path)

        planLaunch(repository, metadata.path, ownConfigDir(repository, metadata.path)) shouldBe
            LaunchStep.OPEN_IN_CURRENT_IDE
    }

    "project of the current isolated IDE without a lock opens in place" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)

        planLaunch(repository, metadata.path, ownConfigDir(repository, metadata.path)) shouldBe
            LaunchStep.OPEN_IN_CURRENT_IDE
    }

    "the current config dir is compared after normalization" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val unnormalized = ownConfigDir(repository, metadata.path).resolve("..").resolve("config")

        planLaunch(repository, metadata.path, unnormalized) shouldBe LaunchStep.OPEN_IN_CURRENT_IDE
    }

    "the main project opens in place in the main instance" {
        val repository = newRepository()
        val mainConfigDir = createTempDirectory(baseDir, "main-config")
        setBaseConfigPath(repository, mainConfigDir)
        mainConfigDir.resolve(".lock").writeText(ProcessHandle.current().pid().toString())
        val metadata = registerProject(repository, projectDir)
        MainProjectService.setMainProject(repository, metadata.path)

        planLaunch(repository, metadata.path, mainConfigDir) shouldBe LaunchStep.OPEN_IN_CURRENT_IDE
    }

    "the main project is launched from an isolated IDE even though the main IDE runs" {
        val repository = newRepository()
        val mainConfigDir = createTempDirectory(baseDir, "main-config")
        setBaseConfigPath(repository, mainConfigDir)
        mainConfigDir.resolve(".lock").writeText(ProcessHandle.current().pid().toString())
        val metadata = registerProject(repository, projectDir)
        MainProjectService.setMainProject(repository, metadata.path)
        val other = registerProject(repository, otherProjectDir)

        planLaunch(repository, metadata.path, ownConfigDir(repository, other.path)) shouldBe LaunchStep.LAUNCH
    }

    "planning creates nothing" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(metadata.path)

        planLaunch(repository, metadata.path, elsewhere)
        planLaunch(repository, metadata.path, projectRoot.resolve("config"))

        projectRoot.resolve("config").shouldNotExist()
    }
})
