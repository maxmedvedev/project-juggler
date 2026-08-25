package com.projectjuggler.core

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.process.ProcessLauncher
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.test.createTempDir
import com.projectjuggler.test.createTempFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Projects can be opened by selecting a file (e.g. `module.bazel`) instead of a directory.
 * These tests cover the path handling and launch flow for such file-based project paths.
 */
class FileBasedProjectTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("test-file-based")
    val repoDir = createTempDir("test-bazel-repo")
    val moduleFile = repoDir.resolve("module.bazel").apply { writeText("module(name = \"test\")") }
    val registerBaseDir = createTempDir("test-file-based-register")
    val launchBaseDir = createTempDir("test-file-based-launch")
    val baseVmOptions = createTempFile("base", ".vmoptions")

    fun repository(dir: Path = baseDir) =
        IdeConfigRepository(dir, IdeInstallation("/test/ide/bin/idea", "Test IDE"))

    "should resolve a file path to a project path" {
        val projectManager = ProjectManager.getInstance(repository())

        val projectPath = projectManager.resolvePath(moduleFile.toString())

        projectPath.pathString shouldBe moduleFile.toAbsolutePath().normalize().toString()
        // The name is inferred from the enclosing directory, not from the selected file
        projectPath.name shouldBe repoDir.fileName.toString()
        projectPath.fileName shouldBe "module.bazel"
    }

    "should infer the name from the enclosing directory for a nested file" {
        val projectManager = ProjectManager.getInstance(repository())

        val nestedDir = repoDir.resolve("nested-module").createDirectories()
        val nestedFile = nestedDir.resolve("MODULE.bazel").apply { writeText("module(name = \"nested\")") }

        projectManager.resolvePath(nestedFile.toString()).name shouldBe "nested-module"
    }

    "should infer the name from the enclosing directory for a path that no longer exists" {
        val projectManager = ProjectManager.getInstance(repository())

        // Stale recent-projects entries point at files that may have been deleted
        val missing = repoDir.resolve("gone").resolve("MODULE.bazel")

        projectManager.resolvePath(missing.toString()).name shouldBe "gone"
    }

    "should use the directory itself as the name for a directory-based project" {
        val projectManager = ProjectManager.getInstance(repository())

        projectManager.resolvePath(repoDir.toString()).name shouldBe repoDir.fileName.toString()
    }

    "should expand tilde in a file path" {
        val projectManager = ProjectManager.getInstance(repository())

        val homeDir = System.getProperty("user.home")
        val tildePath = "~/${Path.of(homeDir).relativize(moduleFile)}"

        val resolved = projectManager.resolvePath(tildePath)

        resolved.path.toAbsolutePath().normalize().toString() shouldBe
            moduleFile.toAbsolutePath().normalize().toString()
        resolved.id shouldBe projectManager.resolvePath(moduleFile.toString()).id
    }

    "should validate existence of a file path" {
        val projectManager = ProjectManager.getInstance(repository())

        projectManager.validatePathExists(moduleFile.toString()) shouldBe true
        projectManager.validatePathExists(repoDir.resolve("no-such.bazel").toString()) shouldBe false
    }

    "should generate an id distinct from the containing directory" {
        val projectManager = ProjectManager.getInstance(repository())

        val fileProjectPath = projectManager.resolvePath(moduleFile.toString())
        val dirProjectPath = projectManager.resolvePath(repoDir.toString())

        fileProjectPath.id shouldNotBe dirProjectPath.id
        // ID format is name-hash16chars. The raw file name (with extension) is used deliberately,
        // not the inferred display name: the ID names the isolated config/system/plugins directory
        // and must stay stable for projects registered before name inference was added.
        fileProjectPath.id.id.substringBeforeLast("-") shouldBe "module.bazel"
        fileProjectPath.id.id.substringAfterLast("-").length shouldBe 16
    }

    "should generate distinct ids for same-named files in different directories" {
        val projectManager = ProjectManager.getInstance(repository())

        val otherRepo = repoDir.resolve("nested").createDirectory()
        val otherModuleFile = otherRepo.resolve("module.bazel").createFile()

        projectManager.resolvePath(moduleFile.toString()).id shouldNotBe
            projectManager.resolvePath(otherModuleFile.toString()).id
    }

    "should register metadata for a file-based project" {
        val ideConfigRepository = repository(registerBaseDir)
        val projectManager = ProjectManager.getInstance(ideConfigRepository)
        val projectPath = projectManager.resolvePath(moduleFile.toString())

        val metadata = projectManager.registerOrUpdate(projectPath)

        metadata.path shouldBe projectPath
        metadata.name shouldBe repoDir.fileName.toString()
        metadata.openCount shouldBe 1

        val reloaded = ideConfigRepository.loadProjectMetadata(projectPath)
        reloaded.shouldNotBeNull()
        reloaded.path shouldBe projectPath

        // Reopening the same file bumps the counter instead of creating a second entry
        projectManager.registerOrUpdate(projectPath).openCount shouldBe 2
        projectManager.listAll() shouldHaveSize 1
    }

    "should launch a project selected by file with isolated configuration" {
        baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

        val mockProcessLauncher = mockk<ProcessLauncher>(relaxed = true)
        val argsSlot = slot<List<String>>()
        every {
            mockProcessLauncher.launch(any(), capture(argsSlot), any())
        } returns Unit

        loadKoinModules(module {
            single<ProcessLauncher> { mockProcessLauncher }
        })

        val testInstallation = IdeInstallation("/test/ide/bin/idea", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(launchBaseDir, testInstallation)
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = baseVmOptions.toString()
            )
        )
        BaseVMOptionsTracker.getInstance(ideConfigRepository).updateHash()

        val projectPath = ProjectPath(moduleFile.toString())
        ProjectLauncher.getInstance(ideConfigRepository).launch(projectPath)

        verify(exactly = 1) { mockProcessLauncher.launch(any(), any(), any()) }

        // The selected file, not its parent directory, is passed to the IDE
        argsSlot.captured shouldHaveSize 1
        argsSlot.captured[0] shouldBe moduleFile.toString()

        // Isolated directories are created under an ID derived from the raw file name
        val projectRoot = launchBaseDir.resolve("projects").resolve(projectPath.id.id)
        projectRoot.exists() shouldBe true
        projectRoot.resolve("config").exists() shouldBe true
        projectRoot.resolve("system").exists() shouldBe true
        projectRoot.resolve("logs").exists() shouldBe true
        projectRoot.resolve("plugins").exists() shouldBe true
        projectRoot.resolve("idea.vmoptions").exists() shouldBe true

        val recentProjects = RecentProjectsIndex.getInstance(ideConfigRepository).getRecent(10)
        recentProjects shouldHaveSize 1
        recentProjects[0].path shouldBe projectPath
        recentProjects[0].name shouldBe repoDir.fileName.toString()
    }
})
