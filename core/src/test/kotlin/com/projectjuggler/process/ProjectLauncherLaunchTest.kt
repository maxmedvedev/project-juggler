package com.projectjuggler.process

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.config.RecentProjectsIndex
import com.projectjuggler.core.BaseVMOptionsTracker
import com.projectjuggler.core.ProjectManager
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import kotlin.io.path.exists

class ProjectLauncherLaunchTest : StringSpec({
    extensions(KoinTestExtension())

    lateinit var repository: IdeConfigRepository
    lateinit var intellijLauncher: IntelliJLauncher
    lateinit var tracker: BaseVMOptionsTracker
    lateinit var launcher: ProjectLauncher
    var projectPath = ProjectPath("/unset")

    beforeEach {
        repository = IdeConfigRepository(createTempDir("test-launch"), IdeInstallation("/test/ide", "Test IDE"))
        intellijLauncher = mockk(relaxed = true)
        tracker = mockk()
        every { tracker.hasChanged() } returns false
        every { tracker.updateHash() } just Runs
        launcher = ProjectLauncher(
            repository,
            projectManager = ProjectManager.getInstance(repository),
            baseVMOptionsTracker = tracker,
            intellijLauncher = intellijLauncher,
            recentProjectsIndex = RecentProjectsIndex.getInstance(repository),
        )
        projectPath = ProjectPath(createTempDir("test-project").toString())
    }

    "main project is launched with base settings and is not tracked" {
        MainProjectService.setMainProject(repository, projectPath)

        launcher.launch(projectPath)

        verify(exactly = 1) { intellijLauncher.launchMain(projectPath.path, any()) }
        verify(exactly = 0) { intellijLauncher.launch(any(), any()) }
        repository.loadProjectMetadata(projectPath) shouldBe null
        repository.baseDir.resolve("recent.json").exists() shouldBe false
    }

    "isolated project is registered, recorded and launched" {
        val launched = slot<ProjectMetadata>()
        every { intellijLauncher.launch(capture(launched), any()) } just Runs

        launcher.launch(projectPath)

        launched.captured shouldBe repository.loadProjectMetadata(projectPath)
        launched.captured.openCount shouldBe 1
        RecentProjectsIndex.getInstance(repository).getRecent(10).first().path shouldBe projectPath
    }

    "changed base vmoptions refreshes the hash" {
        every { tracker.hasChanged() } returns true

        launcher.launch(projectPath)

        verify(exactly = 1) { tracker.updateHash() }
    }

    "unchanged base vmoptions keeps the hash" {
        launcher.launch(projectPath)

        verify(exactly = 0) { tracker.updateHash() }
    }

    "onFailure is passed through to the isolated launch" {
        val onFailure: (LaunchFailure) -> Unit = {}

        launcher.launch(projectPath, onFailure)

        verify(exactly = 1) { intellijLauncher.launch(any(), onFailure) }
    }

    "onFailure is passed through to the main launch" {
        val onFailure: (LaunchFailure) -> Unit = {}
        MainProjectService.setMainProject(repository, projectPath)

        launcher.launch(projectPath, onFailure)

        verify(exactly = 1) { intellijLauncher.launchMain(projectPath.path, onFailure) }
    }
})
