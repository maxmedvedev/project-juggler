package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class OpenWithIdeTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val currentHome = createTempDir("current-home").toAbsolutePath().normalize()
    val current = IdeInstallation(currentHome.resolve("MacOS/idea").toString(), "Current IDE")

    // A fresh registry dir per test: config written by one test must not leak into the next
    fun newRepositoryFor(): (IdeInstallation) -> IdeConfigRepository {
        val registryDir = createTempDirectory(baseDir, "v2")
        return { IdeConfigRepository(registryDir.resolve(it.directoryName), it) }
    }

    fun IdeConfigRepository.withBaseConfig(): IdeConfigRepository = apply {
        update { it.copy(baseConfigPath = baseDir.resolve("${installation.directoryName}-config").toString()) }
    }

    fun other(name: String) = IdeInstallation("/apps/$name/bin/idea.sh", name)

    "the current installation is excluded by directory name" {
        val repositoryFor = newRepositoryFor()
        val sameKey = current.copy()
        repositoryFor(sameKey).withBaseConfig()
        // A home the executable is not under, so only the directory name can exclude it
        val unrelatedHome = createTempDir("unrelated-home").toAbsolutePath().normalize()

        openWithInstallations(listOf(sameKey), current, unrelatedHome, repositoryFor).shouldBeEmpty()
    }

    "an installation under the current home is excluded" {
        val repositoryFor = newRepositoryFor()
        val homeKeyed = IdeInstallation(currentHome.toString(), "Current IDE (home)")
        val underHome = IdeInstallation(currentHome.resolve("bin/idea.sh").toString(), "Current IDE (bin)")
        repositoryFor(homeKeyed).withBaseConfig()
        repositoryFor(underHome).withBaseConfig()

        openWithInstallations(listOf(homeKeyed, underHome), current, currentHome, repositoryFor).shouldBeEmpty()
    }

    "an installation without recorded base settings is excluded" {
        val repositoryFor = newRepositoryFor()

        openWithInstallations(listOf(other("Never Ran")), current, currentHome, repositoryFor).shouldBeEmpty()
    }

    "an installation with recorded base settings is included" {
        val repositoryFor = newRepositoryFor()
        val ran = other("Ran Once")
        repositoryFor(ran).withBaseConfig()

        openWithInstallations(listOf(current, ran, other("Never Ran")), current, currentHome, repositoryFor) shouldContainExactly
            listOf(ran)
    }

    "the result is sorted by display name" {
        val repositoryFor = newRepositoryFor()
        val b = other("IntelliJ IDEA 2025.2")
        val a = other("IntelliJ IDEA 2025.1")
        val c = other("PyCharm 2025.1")
        listOf(a, b, c).forEach { repositoryFor(it).withBaseConfig() }

        openWithInstallations(listOf(c, b, a), current, currentHome, repositoryFor) shouldContainExactly listOf(a, b, c)
    }

    "no installations offer nothing" {
        openWithInstallations(emptyList(), current, currentHome, newRepositoryFor()).shouldBeEmpty()
    }

    "the main-project prompt runs for the current installation's main instance" {
        val repositoryFor = newRepositoryFor()
        val currentConfigDir = createTempDirectory(baseDir, "main-config")
        val repository = repositoryFor(current)
        repository.update { it.copy(baseConfigPath = currentConfigDir.toString()) }

        shouldPromptForMainProject(repository, repository, currentConfigDir) shouldBe true
    }

    "the main-project prompt never runs for another installation" {
        val repositoryFor = newRepositoryFor()
        val currentConfigDir = createTempDirectory(baseDir, "main-config")
        val currentRepository = repositoryFor(current)
        currentRepository.update { it.copy(baseConfigPath = currentConfigDir.toString()) }
        // baseConfigPath unset: core alone would treat any config dir as this IDE's main instance and prompt
        val otherRepository = repositoryFor(other("Never Ran"))

        shouldPromptForMainProject(otherRepository, currentRepository, currentConfigDir) shouldBe false
    }
})
