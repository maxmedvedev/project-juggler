package com.projectjuggler.process

import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.locators.ConfigLocator
import com.projectjuggler.locators.PluginLocator
import com.projectjuggler.test.createTempDir
import com.projectjuggler.test.createTempFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.slot
import io.mockk.unmockkObject
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

class IntelliJLauncherTest : StringSpec({
    extensions(KoinTestExtension())

    beforeTest {
        mockkObject(ConfigLocator, PluginLocator)
        every { ConfigLocator.findDefaultConfigDirectory() } returns null
        every { PluginLocator.findDefaultPluginsDirectory() } returns null
    }

    afterTest {
        unmockkObject(ConfigLocator, PluginLocator)
    }

    "launch starts the IDE with the project and its isolated vmoptions" {
        val installation = IdeInstallation("/test/ide/bin/idea", "Test IDE")
        val repository = IdeConfigRepository(createTempDir("test-intellij-launcher"), installation)
        val processLauncher = mockk<ProcessLauncher>()
        val executable = slot<Path>()
        val args = slot<List<String>>()
        val environment = slot<Map<String, String?>>()
        val onFailureSlot = slot<((LaunchFailure) -> Unit)?>()
        every {
            processLauncher.launch(capture(executable), capture(args), capture(environment), captureNullable(onFailureSlot))
        } just Runs
        val project = ProjectManager.getInstance(repository).registerOrUpdate(ProjectPath("/work/repo"))
        val onFailure: (LaunchFailure) -> Unit = {}

        IntelliJLauncher(repository, processLauncher).launch(project, onFailure)

        val projectRoot = DirectoryManager.getInstance(repository).getProjectRoot(project)
        executable.captured shouldBe Paths.get(installation.executablePath)
        args.captured shouldBe listOf(project.path.pathString)
        environment.captured shouldBe mapOf("IDEA_VM_OPTIONS" to projectRoot.resolve("idea.vmoptions").toString())
        onFailureSlot.captured shouldBe onFailure
        projectRoot.resolve("idea.vmoptions").exists() shouldBe true
        for (dir in listOf("config", "system", "logs", "plugins")) {
            projectRoot.resolve(dir).exists() shouldBe true
        }
    }

    fun mainLaunchSetup(): Triple<IdeConfigRepository, ProcessLauncher, CapturingSlot<Map<String, String?>>> {
        val installation = IdeInstallation("/test/ide/bin/idea", "Test IDE")
        val repository = IdeConfigRepository(createTempDir("test-intellij-launcher-main"), installation)
        val processLauncher = mockk<ProcessLauncher>()
        val environment = slot<Map<String, String?>>()
        every { processLauncher.launch(any(), any(), capture(environment), any()) } just Runs
        return Triple(repository, processLauncher, environment)
    }

    "launchMain removes IDEA_VM_OPTIONS when no base vmoptions is configured" {
        val (repository, processLauncher, environment) = mainLaunchSetup()

        IntelliJLauncher(repository, processLauncher).launchMain(createTempDir("test-main-project"))

        environment.captured shouldContainExactly mapOf("IDEA_VM_OPTIONS" to null)
    }

    "launchMain passes the base vmoptions file when configured" {
        val (repository, processLauncher, environment) = mainLaunchSetup()
        val baseVmOptions = createTempFile("base", ".vmoptions")
        repository.save(IdeConfig(installation = repository.installation, baseVmOptionsPath = baseVmOptions.toString()))

        IntelliJLauncher(repository, processLauncher).launchMain(createTempDir("test-main-project"))

        environment.captured shouldContainExactly mapOf("IDEA_VM_OPTIONS" to baseVmOptions.toString())
    }

    "launchMain does not create isolated directories or metadata" {
        val (repository, processLauncher, _) = mainLaunchSetup()

        IntelliJLauncher(repository, processLauncher).launchMain(createTempDir("test-main-project"))

        repository.baseDir.resolve("projects").exists() shouldBe false
    }
})
