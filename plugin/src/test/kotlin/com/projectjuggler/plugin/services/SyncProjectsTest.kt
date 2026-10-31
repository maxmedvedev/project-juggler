package com.projectjuggler.plugin.services

import com.intellij.openapi.progress.ProcessCanceledException
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.core.SyncOptions
import com.projectjuggler.core.SyncProgress
import com.projectjuggler.plugin.ProjectJugglerBundle
import com.projectjuggler.plugin.actions.recent.SyncType
import com.projectjuggler.process.ProjectLauncher
import com.projectjuggler.test.createTempDir
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder

class SyncProjectsTest : StringSpec({

    val projectA = ProjectMetadata(ProjectPath(createTempDir("project-a").toString()), lastOpened = "2025-01-01T00:00:00Z")
    val projectB = ProjectMetadata(ProjectPath(createTempDir("project-b").toString()), lastOpened = "2025-01-01T00:00:00Z")
    val projectC = ProjectMetadata(ProjectPath(createTempDir("project-c").toString()), lastOpened = "2025-01-01T00:00:00Z")

    class RecordingSink : SyncProgressSink {
        val indeterminate = mutableListOf<Boolean>()
        val texts = mutableListOf<String>()
        val fractions = mutableListOf<Double>()
        var onCheckCanceled: () -> Unit = {}

        override fun setIndeterminate(value: Boolean) {
            indeterminate += value
        }

        override fun setText(text: String) {
            texts += text
        }

        override fun setFraction(fraction: Double) {
            fractions += fraction
        }

        override fun checkCanceled() = onCheckCanceled()
    }

    "syncs each project with the type's parts and stop/restart options" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val sink = RecordingSink()

        syncProjects(launcher, listOf(projectA, projectB), SyncType.Config, sink)

        verifyOrder {
            launcher.syncProject(
                projectA,
                syncVmOptions = false,
                syncConfig = true,
                syncPlugins = false,
                options = match { it.stopIfRunning && it.autoRestart && it.shutdownTimeout == 60 },
            )
            launcher.syncProject(
                projectB,
                syncVmOptions = false,
                syncConfig = true,
                syncPlugins = false,
                options = match { it.stopIfRunning && it.autoRestart && it.shutdownTimeout == 60 },
            )
        }
        sink.indeterminate shouldContainExactly listOf(false)
        sink.fractions shouldContainExactly listOf(0.0, 0.5)
        sink.texts shouldContainExactly listOf(
            ProjectJugglerBundle.message("progress.sync.project.type", "configuration", projectA.path.name),
            ProjectJugglerBundle.message("progress.sync.project.type", "configuration", projectB.path.name),
        )
    }

    "a single project runs indeterminate without fractions" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val sink = RecordingSink()

        syncProjects(launcher, listOf(projectA), SyncType.Plugins, sink)

        sink.indeterminate shouldContainExactly listOf(true)
        sink.fractions.shouldBeEmpty()
        verify(exactly = 1) {
            launcher.syncProject(projectA, syncVmOptions = false, syncConfig = false, syncPlugins = true, options = any())
        }
    }

    "maps sync progress to the indicator text" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val options = slot<SyncOptions>()
        every { launcher.syncProject(any(), any(), any(), any(), capture(options)) } returns Unit
        val sink = RecordingSink()

        syncProjects(launcher, listOf(projectA), SyncType.VmOptions, sink)
        val textsBeforeProgress = sink.texts.toList()

        options.captured.onProgress(SyncProgress.Stopping(3))
        options.captured.onProgress(SyncProgress.Syncing)
        options.captured.onProgress(SyncProgress.Restarting)
        options.captured.onProgress(SyncProgress.Error("boom"))

        sink.texts shouldContainExactly textsBeforeProgress + listOf(
            ProjectJugglerBundle.message("progress.sync.stopping.ide"),
            ProjectJugglerBundle.message("progress.sync.syncing.type", "VM options"),
            ProjectJugglerBundle.message("progress.sync.restarting.ide"),
        )
    }

    "a failing project is recorded and the next one is still synced" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val boom = IllegalStateException("boom")
        every { launcher.syncProject(projectA, any(), any(), any(), any()) } throws boom

        val result = syncProjects(launcher, listOf(projectA, projectB), SyncType.All, RecordingSink())

        verify(exactly = 1) { launcher.syncProject(projectB, any(), any(), any(), any()) }
        result.synced shouldContainExactly listOf(projectB)
        result.failures shouldContainExactly listOf(SyncFailure(projectA, boom))
        result.succeeded shouldBe false
    }

    "a batch without failures succeeded" {
        val result = syncProjects(mockk(relaxed = true), listOf(projectA, projectB), SyncType.All, RecordingSink())

        result.synced shouldContainExactly listOf(projectA, projectB)
        result.failures.shouldBeEmpty()
        result.succeeded shouldBe true
    }

    "cancellation thrown by a project's sync propagates and skips the remaining projects" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        every { launcher.syncProject(projectA, any(), any(), any(), any()) } throws ProcessCanceledException()

        shouldThrow<ProcessCanceledException> {
            syncProjects(launcher, listOf(projectA, projectB), SyncType.All, RecordingSink())
        }

        verify(exactly = 0) { launcher.syncProject(projectB, any(), any(), any(), any()) }
    }

    "cancellation before the first project stops without syncing" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        val sink = RecordingSink().apply { onCheckCanceled = { throw ProcessCanceledException() } }

        shouldThrow<ProcessCanceledException> {
            syncProjects(launcher, listOf(projectA, projectB), SyncType.All, sink)
        }

        verify { launcher wasNot Called }
    }

    "cancellation before the second project stops after the first" {
        val launcher = mockk<ProjectLauncher>(relaxed = true)
        var checks = 0
        val sink = RecordingSink().apply { onCheckCanceled = { if (++checks == 2) throw ProcessCanceledException() } }

        shouldThrow<ProcessCanceledException> {
            syncProjects(launcher, listOf(projectA, projectB), SyncType.All, sink)
        }

        verify(exactly = 1) { launcher.syncProject(projectA, any(), any(), any(), any()) }
        verify(exactly = 0) { launcher.syncProject(projectB, any(), any(), any(), any()) }
    }

    "describeFailures gives the bare message for a single-project batch" {
        val result = SyncBatchResult(emptyList(), listOf(SyncFailure(projectA, IllegalStateException("boom"))))

        describeFailures(result, total = 1) shouldBe "boom"
    }

    "describeFailures reports a failure without a message as an unknown error" {
        val result = SyncBatchResult(emptyList(), listOf(SyncFailure(projectA, IllegalStateException())))

        describeFailures(result, total = 1) shouldBe ProjectJugglerBundle.message("error.unknown")
    }

    "describeFailures summarizes failures of a larger batch by project name" {
        val one = SyncBatchResult(listOf(projectA, projectC), listOf(SyncFailure(projectB, IllegalStateException("a"))))
        val two = SyncBatchResult(
            listOf(projectB),
            listOf(SyncFailure(projectA, IllegalStateException("a")), SyncFailure(projectC, IllegalStateException("b"))),
        )

        describeFailures(one, total = 3) shouldBe
            ProjectJugglerBundle.message("sync.failure.summary", 1, 3, "${projectB.name}: a")
        describeFailures(two, total = 3) shouldBe
            ProjectJugglerBundle.message("sync.failure.summary", 2, 3, "${projectA.name}: a; ${projectC.name}: b")
        describeFailures(two, total = 3) shouldBe "2 of 3 projects failed: ${projectA.name}: a; ${projectC.name}: b"
    }
})
