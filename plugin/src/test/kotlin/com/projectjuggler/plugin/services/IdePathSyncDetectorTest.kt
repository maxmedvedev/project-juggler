package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.expectLoggedError
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.FileAlreadyExistsException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class IdePathSyncDetectorTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val configDir = createTempDir("ide-config")
    val pluginsDir = createTempDir("ide-plugins")
    val vmOptionsDir = createTempDir("ide-vmoptions")
    val config = ProjectJugglerBundle.message("ide.path.kind.config")
    val plugins = ProjectJugglerBundle.message("ide.path.kind.plugins")
    val vmOptions = ProjectJugglerBundle.message("ide.path.kind.vmoptions")

    // A fresh repository per test: the stored paths of one test must not leak into the next
    fun newRepository(): IdeConfigRepository = testRepository(createTempDirectory(baseDir, "ide"))

    // A vmoptions file that exists; a sibling that does not
    val existingVmOptions = vmOptionsDir.resolve("idea.vmoptions").apply { writeText("-Xmx2g\n") }
    val missingVmOptions = vmOptionsDir.resolve("missing.vmoptions")

    val current = CurrentIdePaths(configDir, pluginsDir, missingVmOptions)

    fun IdeConfigRepository.storeCurrent() = update {
        it.copy(
            baseConfigPath = configDir.toString(),
            basePluginsPath = pluginsDir.toString(),
            baseVmOptionsPath = missingVmOptions.toString(),
        )
    }

    "detectMismatches is silent in an isolated instance even when the stored paths differ" {
        val repository = newRepository()
        repository.update { it.copy(baseConfigPath = "/stale/config", basePluginsPath = "/stale/plugins") }
        val isolated = current.copy(configDir = repository.baseDir.resolve("projects/x/config"))

        IdePathSyncDetector.detectMismatches(repository, isolated).shouldBeEmpty()
    }

    "detectMismatches ignores paths that were never stored" {
        IdePathSyncDetector.detectMismatches(newRepository(), current).shouldBeEmpty()
    }

    "detectMismatches is silent when everything matches" {
        val repository = newRepository()
        repository.storeCurrent()

        IdePathSyncDetector.detectMismatches(repository, current).shouldBeEmpty()
    }

    "detectMismatches reports a differing config path" {
        val repository = newRepository()
        repository.storeCurrent()
        repository.update { it.copy(baseConfigPath = "/stale/config") }

        IdePathSyncDetector.detectMismatches(repository, current) shouldContainExactly listOf(config)
    }

    "detectMismatches reports a differing plugins path" {
        val repository = newRepository()
        repository.storeCurrent()
        repository.update { it.copy(basePluginsPath = "/stale/plugins") }

        IdePathSyncDetector.detectMismatches(repository, current) shouldContainExactly listOf(plugins)
    }

    "detectMismatches reports a differing vmoptions path" {
        val repository = newRepository()
        repository.storeCurrent()
        repository.update { it.copy(baseVmOptionsPath = "/stale/idea.vmoptions") }

        IdePathSyncDetector.detectMismatches(repository, current) shouldContainExactly listOf(vmOptions)
    }

    "detectMismatches reports all three in a fixed order" {
        val repository = newRepository()
        repository.update {
            it.copy(
                baseConfigPath = "/stale/config",
                basePluginsPath = "/stale/plugins",
                baseVmOptionsPath = "/stale/idea.vmoptions",
            )
        }

        IdePathSyncDetector.detectMismatches(repository, current) shouldContainExactly
            listOf(config, plugins, vmOptions)
    }

    "detectMismatches treats a trailing slash as a different path (gap 9)" {
        // plugin-spec gap 9: paths are compared as raw strings, not normalized
        val repository = newRepository()
        repository.storeCurrent()
        repository.update { it.copy(baseConfigPath = "$configDir/") }

        IdePathSyncDetector.detectMismatches(repository, current) shouldContainExactly listOf(config)
    }

    "updatePaths overwrites config and plugins but keeps a vmoptions path whose file is missing" {
        val repository = newRepository()
        repository.update {
            it.copy(
                baseConfigPath = "/stale/config",
                basePluginsPath = "/stale/plugins",
                baseVmOptionsPath = "/stale/idea.vmoptions",
            )
        }
        val handler = RecordingNotificationHandler()

        IdePathSyncDetector.updatePaths(repository, handler, current)

        val config = repository.load()
        config.baseConfigPath shouldBe configDir.toString()
        config.basePluginsPath shouldBe pluginsDir.toString()
        config.baseVmOptionsPath shouldBe "/stale/idea.vmoptions"
        config.baseVmOptionsHash.shouldBeNull()
        handler.infos shouldContainExactly listOf(ProjectJugglerBundle.message("notification.success.paths.updated"))
        handler.errors.shouldBeEmpty()
    }

    "updatePaths overwrites the vmoptions path and refreshes its hash when the file exists" {
        val repository = newRepository()
        repository.update { it.copy(baseVmOptionsPath = "/stale/idea.vmoptions") }

        IdePathSyncDetector.updatePaths(repository, RecordingNotificationHandler(), current.copy(vmOptionsFile = existingVmOptions))

        val config = repository.load()
        config.baseVmOptionsPath shouldBe existingVmOptions.toString()
        config.baseVmOptionsHash.shouldNotBeNull()
    }

    "updatePaths reports a failure when the repository cannot be written" {
        val repository = testRepository(createTempFile(baseDir, "not-a-dir"))
        val handler = RecordingNotificationHandler()

        val logged = expectLoggedError { IdePathSyncDetector.updatePaths(repository, handler, current) }

        logged.shouldBeInstanceOf<FileAlreadyExistsException>()
        handler.infos.shouldBeEmpty()
        handler.errors.single() shouldStartWith ProjectJugglerBundle.message("notification.error.paths.update.failed", "")
    }

    "autoPopulateIfNeeded stores the three paths and the vmoptions hash on a fresh repository" {
        val repository = newRepository()

        IdePathSyncDetector.autoPopulateIfNeeded(repository, current.copy(vmOptionsFile = existingVmOptions))

        val config = repository.load()
        config.baseConfigPath shouldBe configDir.toString()
        config.basePluginsPath shouldBe pluginsDir.toString()
        config.baseVmOptionsPath shouldBe existingVmOptions.toString()
        config.baseVmOptionsHash.shouldNotBeNull()
    }

    "autoPopulateIfNeeded leaves the vmoptions path unset when the file is missing" {
        val repository = newRepository()

        IdePathSyncDetector.autoPopulateIfNeeded(repository, current)

        val config = repository.load()
        config.baseConfigPath shouldBe configDir.toString()
        config.basePluginsPath shouldBe pluginsDir.toString()
        config.baseVmOptionsPath.shouldBeNull()
        config.baseVmOptionsHash.shouldBeNull()
    }

    "autoPopulateIfNeeded does not touch a configured repository" {
        val repository = newRepository()
        repository.update { it.copy(baseConfigPath = "/configured/config") }
        val before = repository.load()

        IdePathSyncDetector.autoPopulateIfNeeded(repository, current.copy(vmOptionsFile = existingVmOptions))

        repository.load() shouldBe before
    }
})
