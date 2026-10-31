package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.MainProjectService
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.CurrentIdePaths
import com.projectjuggler.plugin.test.markIsolatedInstanceOpen
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.plugin.test.writeGitHead
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting

class RecentProjectPopupBuilderTest : StringSpec({
    extensions(KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val projectsDir = createTempDir("projects")
    val configDir = createTempDir("ide-config")
    val pluginsDir = createTempDir("ide-plugins")
    // The running IDE as the popup sees it: a main instance whose config dir is outside the repository
    val currentPaths = CurrentIdePaths(configDir, pluginsDir, configDir.resolve("idea.vmoptions"))

    // A fresh repository per test whose stored base config matches the current IDE, so no update item appears
    fun newRepository(): IdeConfigRepository =
        testRepository(createTempDirectory(baseDir, "ide")).apply {
            update { it.copy(baseConfigPath = configDir.toString()) }
        }

    fun newProjectDir(name: String) = createTempDirectory(projectsDir, name)

    fun buildItems(repository: IdeConfigRepository) = RecentProjectPopupBuilder.buildItems(repository, currentPaths)

    fun List<RecentProjectPopupAction>.projects() = filterIsInstance<OpenRecentProjectAction>()

    "without projects the popup offers browse and import" {
        val repository = newRepository()

        buildItems(repository) shouldContainExactly listOf(OpenFileChooserAction, ImportRecentProjectsAction)
    }

    "recents come newest first, followed by the sync actions and the toggle" {
        val repository = newRepository()
        val p1 = registerProject(repository, newProjectDir("p1"))
        val p2 = registerProject(repository, newProjectDir("p2"))

        val items = buildItems(repository)

        items.map { it::class } shouldContainExactly listOf(
            OpenFileChooserAction::class,
            OpenRecentProjectAction::class,
            OpenRecentProjectAction::class,
            SyncAllProjectsAction::class,
            SyncAllProjectsAction::class,
            SyncAllProjectsAction::class,
            SyncAllProjectsAction::class,
            ToggleSyncOnUpdateAction::class,
        )
        items.projects() shouldContainExactly listOf(
            OpenRecentProjectAction(p2.path, gitBranch = null),
            OpenRecentProjectAction(p1.path, gitBranch = null),
        )
        items.filterIsInstance<SyncAllProjectsAction>() shouldContainExactly listOf(
            SyncAllProjectsAction(SyncType.All),
            SyncAllProjectsAction(SyncType.VmOptions),
            SyncAllProjectsAction(SyncType.Config),
            SyncAllProjectsAction(SyncType.Plugins),
        )
        items.last().shouldBeInstanceOf<ToggleSyncOnUpdateAction>().getTextFor() shouldBe
            ProjectJugglerBundle.message("popup.toggle.sync.on.update.disabled")
    }

    "the toggle shows the enabled text when auto-sync is on" {
        val repository = newRepository()
        repository.update { it.copy(syncPluginsOnIdeUpdate = true) }
        registerProject(repository, newProjectDir("p1"))

        buildItems(repository).last().shouldBeInstanceOf<ToggleSyncOnUpdateAction>().getTextFor() shouldBe
            ProjectJugglerBundle.message("popup.toggle.sync.on.update.enabled")
    }

    "the main project follows browse even when it is not a recent" {
        val repository = newRepository()
        val mainPath = ProjectPath(newProjectDir("main").toString())
        MainProjectService.setMainProject(repository, mainPath)
        val p1 = registerProject(repository, newProjectDir("p1"))

        val items = buildItems(repository)

        items[0] shouldBe OpenFileChooserAction
        items.projects() shouldContainExactly listOf(
            OpenRecentProjectAction(mainPath, gitBranch = null, isMainProject = true),
            OpenRecentProjectAction(p1.path, gitBranch = null),
        )
    }

    "a main project that is also a recent is listed once" {
        val repository = newRepository()
        val p1 = registerProject(repository, newProjectDir("p1"))
        MainProjectService.setMainProject(repository, p1.path)

        buildItems(repository).projects() shouldContainExactly listOf(
            OpenRecentProjectAction(p1.path, gitBranch = null, isMainProject = true)
        )
    }

    "recents whose directory is gone are dropped" {
        val repository = newRepository()
        val dir = newProjectDir("gone")
        registerProject(repository, dir)
        dir.deleteExisting()

        buildItems(repository) shouldContainExactly listOf(OpenFileChooserAction, ImportRecentProjectsAction)
    }

    "a stale stored config path puts the update item first" {
        val repository = newRepository()
        repository.update { it.copy(baseConfigPath = "/somewhere/else/config") }
        registerProject(repository, newProjectDir("p1"))

        val items = buildItems(repository)

        items.first().shouldBeInstanceOf<UpdatePathsAction>().mismatches shouldContainExactly listOf(
            ProjectJugglerBundle.message("ide.path.kind.config")
        )
        items[1] shouldBe OpenFileChooserAction
    }

    "project items carry the git branch when the directory is a checkout" {
        val repository = newRepository()
        val checkout = newProjectDir("checkout")
        writeGitHead(checkout, "feature")
        val plain = registerProject(repository, newProjectDir("plain"))
        val git = registerProject(repository, checkout)

        val items = buildItems(repository).projects().associateBy { it.projectPath }

        items.getValue(git.path).gitBranch shouldBe "feature"
        items.getValue(plain.path).gitBranch.shouldBeNull()
    }

    "a project with a running isolated instance is marked open" {
        val repository = newRepository()
        val running = registerProject(repository, newProjectDir("running"))
        val idle = registerProject(repository, newProjectDir("idle"))
        markIsolatedInstanceOpen(repository, running.path)

        val items = buildItems(repository).projects().associateBy { it.projectPath }

        items.getValue(running.path).isOpen shouldBe true
        items.getValue(idle.path).isOpen shouldBe false
    }

    "at most ten recents are shown (gap 8)" {
        // plugin-spec gap 8: the popup asks for 20 recents but the index keeps maxRecentProjects (10)
        val repository = newRepository()
        repeat(12) { registerProject(repository, newProjectDir("p$it")) }

        buildItems(repository).projects() shouldHaveSize 10
    }
})
