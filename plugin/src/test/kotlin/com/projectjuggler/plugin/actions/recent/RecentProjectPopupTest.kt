package com.projectjuggler.plugin.actions.recent

import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.services.IdePathKind
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RecentProjectPopupTest : StringSpec({

    // An existing directory: ProjectPath names a missing path like a file-based project ("parent [file]")
    val projectDir = createTempDir("demo")
    val projectPath = ProjectPath(projectDir.toString())
    val name = projectDir.fileName.toString()
    val path = projectDir.toString()

    "an isolated project offers open, main toggle, the four syncs and remove" {
        val item = OpenRecentProjectAction(projectPath, gitBranch = null)

        projectSubActions(item) shouldContainExactly listOf(
            OpenRecentProjectSubAction,
            ToggleMainProjectSubAction,
            SyncSettingsSubAction(SyncType.All),
            SyncSettingsSubAction(SyncType.VmOptions),
            SyncSettingsSubAction(SyncType.Config),
            SyncSettingsSubAction(SyncType.Plugins),
            RemoveProjectSubAction,
        )
    }

    "the main project has nothing to sync or remove" {
        val item = OpenRecentProjectAction(projectPath, gitBranch = null, isMainProject = true)

        projectSubActions(item) shouldContainExactly listOf(
            OpenRecentProjectSubAction,
            ToggleMainProjectSubAction,
        )
    }

    val ideA = IdeInstallation("/apps/a/bin/idea.sh", "IDE A")
    val ideB = IdeInstallation("/apps/b/bin/idea.sh", "IDE B")

    "an isolated project offers open with each other IDE right after open" {
        val item = OpenRecentProjectAction(projectPath, gitBranch = null)

        projectSubActions(item, listOf(ideA, ideB)) shouldContainExactly listOf(
            OpenRecentProjectSubAction,
            OpenWithIdeSubAction(ideA),
            OpenWithIdeSubAction(ideB),
            ToggleMainProjectSubAction,
            SyncSettingsSubAction(SyncType.All),
            SyncSettingsSubAction(SyncType.VmOptions),
            SyncSettingsSubAction(SyncType.Config),
            SyncSettingsSubAction(SyncType.Plugins),
            RemoveProjectSubAction,
        )
    }

    "the main project offers open with each other IDE but nothing to sync or remove" {
        val item = OpenRecentProjectAction(projectPath, gitBranch = null, isMainProject = true)

        projectSubActions(item, listOf(ideA, ideB)) shouldContainExactly listOf(
            OpenRecentProjectSubAction,
            OpenWithIdeSubAction(ideA),
            OpenWithIdeSubAction(ideB),
            ToggleMainProjectSubAction,
        )
    }

    "open with names the IDE" {
        val item = OpenRecentProjectAction(projectPath, gitBranch = null)

        OpenWithIdeSubAction(ideA).getTextFor(item) shouldBe
            ProjectJugglerBundle.message("popup.recent.projects.action.open.with", ideA.displayName)
    }

    "speed search indexes name, branch and path" {
        OpenRecentProjectAction(projectPath, gitBranch = "main").getIndexedString() shouldBe "$name main $path"
    }

    "speed search indexes name and path without a branch" {
        OpenRecentProjectAction(projectPath, gitBranch = null).getIndexedString() shouldBe "$name $path"
    }

    "the project text shows name, branch and path" {
        OpenRecentProjectAction(projectPath, gitBranch = "main").getTextFor() shouldBe "$name - [main] - $path"
    }

    "the update paths text lists the mismatches" {
        UpdatePathsAction(listOf(IdePathKind.CONFIG, IdePathKind.PLUGINS)).getTextFor() shouldContain "Config, Plugins"
    }
})
