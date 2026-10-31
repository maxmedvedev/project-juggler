package com.projectjuggler.plugin.services

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.projectjuggler.config.IdeConfig
import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.IdeRegistry
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.di.pluginModule
import com.projectjuggler.plugin.test.TestApplicationExtension
import com.projectjuggler.platform.Platform
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.paths.shouldStartWithPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    val home = PathManager.getHomePath()
    val legacy = IdeInstallation(home, "legacy")
    val legacyDir = baseDir.resolve("v2").resolve(legacy.directoryName)

    beforeSpec {
        loadKoinModules(pluginModule)
        // Start from an unconfigured repository so the first access auto-populates it
        baseDir.resolve("v2").deleteRecursively()
        // A dir keyed on the home directory, as older plugin versions wrote it; adopted on first access
        IdeConfigRepository.create(legacy, baseDir.resolve("v2"))
            .save(IdeConfig.default(legacy).copy(mainProjectPath = "/x"))
    }

    "the installation is the launcher derived from the product's script name" {
        // The sandbox IDE ships MacOS/idea; without it the same path is used and only a warning is logged
        val expected = currentIdeExecutable(
            Path.of(home),
            ApplicationNamesInfo.getInstance().scriptName,
            Platform.current(),
        ).toString()

        val executablePath = IdeInstallationService.getInstance().currentInstallation.executablePath

        executablePath shouldBe expected
        executablePath shouldNotBe home
    }

    "the first access adopts a legacy home-keyed dir" {
        val repository = IdeInstallationService.getInstance().currentRepository

        repository.load().mainProjectPath shouldBe "/x"
        repository.load().installation shouldBe IdeInstallationService.getInstance().currentInstallation
        legacyDir.exists() shouldBe false
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
