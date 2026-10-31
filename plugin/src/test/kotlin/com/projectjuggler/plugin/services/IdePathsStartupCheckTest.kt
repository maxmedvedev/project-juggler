package com.projectjuggler.plugin.services

import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting

class IdePathsStartupCheckTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val configDir = createTempDir("ide-config")
    val pluginsDir = createTempDir("ide-plugins")
    val current = CurrentIdePaths(configDir, pluginsDir, createTempDir("ide-vmoptions").resolve("idea.vmoptions"))

    fun newRepository() = testRepository(createTempDirectory(baseDir, "ide")).apply {
        update { it.copy(baseConfigPath = configDir.toString(), basePluginsPath = pluginsDir.toString()) }
    }

    beforeTest { IdePathsStartupCheck.resetForTests() }

    "an out-of-sync path is reported on the first call only" {
        val repository = newRepository()
        repository.update { it.copy(basePluginsPath = createTempDir("other-plugins").toString()) }

        IdePathsStartupCheck.problemsToReport(repository, current) shouldBe
            mapOf(IdePathKind.PLUGINS to IdePathProblem.OUT_OF_SYNC)
        IdePathsStartupCheck.problemsToReport(repository, current).shouldBeEmpty()
    }

    "a stored path that no longer exists is missing" {
        val repository = newRepository()
        val deleted = createTempDir("deleted-config").also { it.deleteExisting() }
        repository.update { it.copy(baseConfigPath = deleted.toString()) }

        IdePathsStartupCheck.problemsToReport(repository, current) shouldBe
            mapOf(IdePathKind.CONFIG to IdePathProblem.MISSING)
    }

    "missing and out-of-sync paths are both reported" {
        val repository = newRepository()
        repository.update {
            it.copy(baseConfigPath = "/missing/config", basePluginsPath = createTempDir("other-plugins").toString())
        }

        IdePathsStartupCheck.problemsToReport(repository, current) shouldBe mapOf(
            IdePathKind.CONFIG to IdePathProblem.MISSING,
            IdePathKind.PLUGINS to IdePathProblem.OUT_OF_SYNC,
        )
    }

    "nothing is reported when the paths match" {
        IdePathsStartupCheck.problemsToReport(newRepository(), current).shouldBeEmpty()
    }

    "nothing is reported in an isolated instance" {
        val repository = newRepository()
        repository.update { it.copy(baseConfigPath = "/missing/config", basePluginsPath = "/stale/plugins") }
        val isolated = current.copy(configDir = repository.baseDir.resolve("projects/x-1/config"))

        IdePathsStartupCheck.problemsToReport(repository, isolated).shouldBeEmpty()
    }
})
