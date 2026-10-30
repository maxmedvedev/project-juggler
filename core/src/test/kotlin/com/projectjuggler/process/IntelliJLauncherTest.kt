package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.DirectoryManager
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.locators.ConfigLocator
import com.projectjuggler.locators.PluginLocator
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
        val environment = slot<Map<String, String>>()
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
})
