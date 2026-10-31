package com.projectjuggler.plugin.services

import com.intellij.openapi.application.PathManager
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.test.TestApplicationExtension
import com.projectjuggler.platform.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.paths.shouldStartWithPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.koin.core.context.loadKoinModules
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

@OptIn(ExperimentalPathApi::class)
class IdeInstallationServiceTest : StringSpec({
    extensions(KoinTestExtension(), TestApplicationExtension())

    // Set by the plugin's test task, so the real IdeRegistry stays inside the build directory
    val baseDir = Path.of(System.getProperty(IdeRegistry.PROJECT_JUGGLER_BASE_DIR))

    beforeSpec {
        loadKoinModules(pluginModule)
        // Start from an unconfigured repository so the first access auto-populates it
        baseDir.resolve("v2").deleteRecursively()
    }

    "the installation is the IDE's launcher, or the home directory when it has none (gap 10)" {
        // plugin-spec gap 10: without a launcher the installation is keyed on the home directory. The sandbox
        // IDE ships its launcher, so this run takes the launcher branch; the expectation covers both.
        val home = Path.of(PathManager.getHomePath())
        val launcher = when (Platform.current()) {
            Platform.MACOS -> home.resolve("MacOS").resolve("idea")
            Platform.LINUX -> home.resolve("bin").resolve("idea.sh")
            Platform.WINDOWS -> home.resolve("bin").resolve("idea64.exe")
        }
        val expected = if (launcher.exists()) launcher.toString() else home.toString()

        IdeInstallationService.getInstance().currentInstallation.executablePath shouldBe expected
    }

    "the current repository lives under the configured base dir" {
        IdeInstallationService.getInstance().currentRepository.baseDir shouldStartWithPath baseDir.resolve("v2")
    }

    "the first access stores the running IDE's config and plugins paths" {
        val config = IdeInstallationService.currentIdeConfigRepository.load()

        config.baseConfigPath shouldBe PathManager.getConfigDir().toString()
        config.basePluginsPath shouldBe PathManager.getPluginsDir().toString()
    }

    "the platform vmoptions file sits in the IDE's bin directory" {
        val file = VMOptionsCopy.getPlatformOptionsFile()

        file shouldStartWithPath Path.of(PathManager.getBinPath())
        file.fileName.toString() shouldEndWith ".vmoptions"
    }

    "there is no user vmoptions file without jb.vmOptionsFile" {
        val previous = System.clearProperty("jb.vmOptionsFile")
        try {
            VMOptionsCopy.getUserOptionsFile().shouldBeNull()
        } finally {
            previous?.let { System.setProperty("jb.vmOptionsFile", it) }
        }
    }
})
