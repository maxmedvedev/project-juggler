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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class LaunchProjectTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("test-project-juggler")
    val projectDir = createTempDir("test-project")
    val baseVmOptions = createTempFile("base", ".vmoptions")

    "should launch project with correct configuration" {
        baseVmOptions.writeText("-Xms256m\n-Xmx2048m")

        // Create a mock ProcessLauncher
        val mockProcessLauncher = mockk<ProcessLauncher>(relaxed = true)
        val executableSlot = slot<Path>()
        val argsSlot = slot<List<String>>()
        val envSlot = slot<Map<String, String>>()
        every {
            mockProcessLauncher.launch(capture(executableSlot), capture(argsSlot), capture(envSlot))
        } returns Unit

        // Override Koin binding with mock
        loadKoinModules(module {
            single<ProcessLauncher> { mockProcessLauncher }
        })

        // Setup IdeConfigRepository with test installation
        val testInstallation = IdeInstallation("/test/ide/bin/idea", "Test IDE")
        val ideConfigRepository = IdeConfigRepository(baseDir, testInstallation)

        // Configure base VM options
        ideConfigRepository.save(
            IdeConfig(
                installation = testInstallation,
                baseVmOptionsPath = baseVmOptions.toString()
            )
        )
        BaseVMOptionsTracker.getInstance(ideConfigRepository).updateHash()

        // Launch the project (emulating what OpenWithProjectJugglerAction does)
        val projectPath = ProjectPath(projectDir.toString())
        ProjectLauncher.getInstance(ideConfigRepository).launch(projectPath)

        // Verify ProcessLauncher was called with correct parameters
        verify(exactly = 1) {
            mockProcessLauncher.launch(any(), any(), any())
        }

        // Verify executable path is the IDE path
        executableSlot.captured.toString() shouldBe "/test/ide/bin/idea"

        // Verify project path is passed as argument
        argsSlot.captured shouldHaveSize 1
        argsSlot.captured[0] shouldBe projectDir.toString()

        // Verify environment contains IDEA_VM_OPTIONS
        envSlot.captured.containsKey("IDEA_VM_OPTIONS") shouldBe true
        envSlot.captured["IDEA_VM_OPTIONS"].shouldNotBeNull()

        // Verify project is in recent projects list
        val recentProjects = RecentProjectsIndex.getInstance(ideConfigRepository).getRecent(10)
        recentProjects shouldHaveSize 1
        recentProjects[0].path shouldBe projectPath

        // Verify project metadata is saved
        val projectMetadata = ideConfigRepository.loadProjectMetadata(projectPath)
        projectMetadata.shouldNotBeNull()
        projectMetadata.path shouldBe projectPath
        projectMetadata.name shouldBe projectDir.fileName.toString()

        // Verify project-juggler folder contains correct configuration
        baseDir.resolve("config.json").exists() shouldBe true
        baseDir.resolve("projects").exists() shouldBe true

        // Verify project directories are created
        val projectRoot = baseDir.resolve("projects").resolve(projectPath.id.id)
        projectRoot.exists() shouldBe true
        projectRoot.resolve("config").exists() shouldBe true
        projectRoot.resolve("system").exists() shouldBe true
        projectRoot.resolve("logs").exists() shouldBe true
        projectRoot.resolve("plugins").exists() shouldBe true

        // Verify VM options file is created with correct content
        val vmOptionsFile = projectRoot.resolve("idea.vmoptions")
        vmOptionsFile.exists() shouldBe true
        val vmOptionsContent = vmOptionsFile.toFile().readText()
        vmOptionsContent.contains("-Xms256m") shouldBe true
        vmOptionsContent.contains("-Xmx2048m") shouldBe true
        vmOptionsContent.contains("-Didea.config.path=") shouldBe true
        vmOptionsContent.contains("-Didea.system.path=") shouldBe true
        vmOptionsContent.contains("-Didea.log.path=") shouldBe true
        vmOptionsContent.contains("-Didea.plugins.path=") shouldBe true
    }
})
