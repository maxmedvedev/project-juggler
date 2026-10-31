package com.projectjuggler.plugin.services

import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.io.path.createTempDirectory

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

    "a mismatch is reported on the first call only" {
        val repository = newRepository()
        repository.update { it.copy(basePluginsPath = "/stale/plugins") }

        IdePathsStartupCheck.mismatchesToReport(repository, current) shouldContainExactly listOf(IdePathKind.PLUGINS)
        IdePathsStartupCheck.mismatchesToReport(repository, current).shouldBeEmpty()
    }

    "nothing is reported when the paths match" {
        IdePathsStartupCheck.mismatchesToReport(newRepository(), current).shouldBeEmpty()
    }

    "nothing is reported in an isolated instance" {
        val repository = newRepository()
        repository.update { it.copy(basePluginsPath = "/stale/plugins") }
        val isolated = current.copy(configDir = repository.baseDir.resolve("projects/x-1/config"))

        IdePathsStartupCheck.mismatchesToReport(repository, isolated).shouldBeEmpty()
    }
})
