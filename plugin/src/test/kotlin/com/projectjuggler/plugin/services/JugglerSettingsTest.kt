package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import com.projectjuggler.util.HashUtils
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class JugglerSettingsTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val configDir = createTempDir("ide-config")
    val pluginsDir = createTempDir("ide-plugins")
    val vmOptionsDir = createTempDir("ide-vmoptions")
    val vmOptionsFile = vmOptionsDir.resolve("idea.vmoptions").apply { writeText("-Xmx2g\n") }
    val projectDir = createTempDir("project")

    // A fresh repository per test: config and metadata written by one test must not leak into the next
    fun newRepository(): IdeConfigRepository = testRepository(createTempDirectory(baseDir, "ide"))

    fun message(key: String, path: Path) = ProjectJugglerBundle.message(key, path)

    "load maps unset paths to blank and dontAskAboutMainProject to askForMainProject" {
        val repository = newRepository()
        loadJugglerSettings(repository) shouldBe JugglerSettings()

        repository.update {
            it.copy(
                baseConfigPath = "/c",
                dontAskAboutMainProject = true,
                syncPluginsOnIdeUpdate = true,
                mainProjectPath = "/p",
                maxRecentProjects = 7,
            )
        }

        loadJugglerSettings(repository) shouldBe JugglerSettings(
            baseConfigPath = "/c",
            syncPluginsOnIdeUpdate = true,
            askForMainProject = false,
            mainProjectPath = "/p",
            maxRecentProjects = 7,
        )
    }

    "save stores blank paths as null, expands tilde and normalizes" {
        val repository = newRepository()
        repository.update { it.copy(baseConfigPath = "/old/config") }
        val home = System.getProperty("user.home")

        saveJugglerSettings(
            repository,
            JugglerSettings(baseConfigPath = "", basePluginsPath = "~/plugins", baseVmOptionsPath = "/a/../b/idea.vmoptions"),
        )

        val config = repository.load()
        config.baseConfigPath.shouldBeNull()
        config.basePluginsPath shouldBe Path.of(home, "plugins").toString()
        config.baseVmOptionsPath shouldBe "/b/idea.vmoptions"
    }

    "save writes the checkboxes and coerces the recent limit" {
        val repository = newRepository()

        saveJugglerSettings(
            repository,
            JugglerSettings(syncPluginsOnIdeUpdate = true, askForMainProject = false, maxRecentProjects = 0),
        )
        repository.load().run {
            syncPluginsOnIdeUpdate shouldBe true
            dontAskAboutMainProject shouldBe true
            maxRecentProjects shouldBe 1
        }

        saveJugglerSettings(repository, JugglerSettings(maxRecentProjects = 99))
        repository.load().maxRecentProjects shouldBe 50
    }

    "validateBasePath accepts blank and valid paths and names each problem" {
        val repository = newRepository()
        val missing = configDir.resolve("missing")
        val isolated = repository.baseDir.resolve("projects/x/config").createDirectories()

        validateBasePath(repository, IdePathKind.CONFIG, "  ").shouldBeNull()
        validateBasePath(repository, IdePathKind.CONFIG, configDir.toString()).shouldBeNull()
        validateBasePath(repository, IdePathKind.VM_OPTIONS, vmOptionsFile.toString()).shouldBeNull()
        validateBasePath(repository, IdePathKind.PLUGINS, missing.toString()) shouldBe
            message("settings.error.path.missing", missing)
        validateBasePath(repository, IdePathKind.CONFIG, vmOptionsFile.toString()) shouldBe
            message("settings.error.path.not.directory", vmOptionsFile)
        validateBasePath(repository, IdePathKind.VM_OPTIONS, vmOptionsDir.toString()) shouldBe
            message("settings.error.path.not.file", vmOptionsDir)
        validateBasePath(repository, IdePathKind.CONFIG, isolated.toString()) shouldBe
            message("settings.error.path.isolated", isolated)
    }

    "changing the vmoptions path refreshes its hash, keeping it does not" {
        val repository = newRepository()
        repository.update { it.copy(baseVmOptionsHash = "old") }

        saveJugglerSettings(repository, JugglerSettings(baseVmOptionsPath = vmOptionsFile.toString()))
        repository.load().baseVmOptionsHash shouldBe HashUtils.calculateFileHash(vmOptionsFile)

        repository.update { it.copy(baseVmOptionsHash = "kept") }
        saveJugglerSettings(repository, loadJugglerSettings(repository))
        repository.load().baseVmOptionsHash shouldBe "kept"
    }

    "withCurrentIdePaths fills the current paths and skips a missing vmoptions file" {
        val start = JugglerSettings(baseVmOptionsPath = "/stored/idea.vmoptions", maxRecentProjects = 5)

        start.withCurrentIdePaths(CurrentIdePaths(configDir, pluginsDir, vmOptionsFile)) shouldBe start.copy(
            baseConfigPath = configDir.toString(),
            basePluginsPath = pluginsDir.toString(),
            baseVmOptionsPath = vmOptionsFile.toString(),
        )
        start.withCurrentIdePaths(CurrentIdePaths(configDir, pluginsDir, vmOptionsDir.resolve("missing"))) shouldBe
            start.copy(baseConfigPath = configDir.toString(), basePluginsPath = pluginsDir.toString())
    }

    "validateMainProject accepts blank and existing paths" {
        val file = projectDir.resolve("MODULE.bazel").apply { writeText("") }
        val missing = projectDir.resolve("missing")

        validateMainProject("").shouldBeNull()
        validateMainProject(projectDir.toString()).shouldBeNull()
        validateMainProject(file.toString()).shouldBeNull()
        validateMainProject(missing.toString()) shouldBe message("settings.error.main.project.missing", missing)
    }

    "save returns the main project change without writing the main project" {
        val repository = newRepository()
        val projectPath = ProjectPath(projectDir.toString())

        saveJugglerSettings(repository, JugglerSettings()).shouldBeNull()
        saveJugglerSettings(repository, JugglerSettings(mainProjectPath = "$projectDir/./")) shouldBe
            MainProjectChange.Set(projectPath)
        repository.load().mainProjectPath.shouldBeNull()

        MainProjectService.setMainProject(repository, projectPath)
        saveJugglerSettings(repository, JugglerSettings(mainProjectPath = projectDir.toString())).shouldBeNull()
        saveJugglerSettings(repository, JugglerSettings()) shouldBe MainProjectChange.Clear
        repository.load().mainProjectPath shouldBe projectPath.pathString
    }

    "applyMainProjectChange sets the main project like the popup and drops its isolated entry" {
        val repository = newRepository()
        val metadata = registerProject(repository, projectDir)
        val projectRoot = repository.baseDir.resolve("projects").resolve(metadata.path.id.id)
        projectRoot.shouldExist()
        val handler = RecordingNotificationHandler()

        applyMainProjectChange(repository, MainProjectChange.Set(metadata.path), handler)

        repository.load().mainProjectPath shouldBe metadata.path.pathString
        projectRoot.shouldNotExist()
        handler.infos shouldContainExactly listOf(
            ProjectJugglerBundle.message("notification.success.set.main", metadata.path.name)
        )
    }

    "applyMainProjectChange clears the main project like the popup" {
        val repository = newRepository()
        MainProjectService.setMainProject(repository, ProjectPath(projectDir.toString()))
        val handler = RecordingNotificationHandler()

        applyMainProjectChange(repository, MainProjectChange.Clear, handler)

        repository.load().mainProjectPath.shouldBeNull()
        handler.infos shouldContainExactly listOf(ProjectJugglerBundle.message("notification.success.unset.main"))
    }
})
