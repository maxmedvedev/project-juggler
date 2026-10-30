package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.test.createTempDir
import com.projectjuggler.util.ProjectLockUtils
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject

class ShutdownWaiterTest : StringSpec({
    val repository = IdeConfigRepository(createTempDir("test-shutdown"), IdeInstallation("/test/ide", "Test IDE"))
    val projectPath = ProjectPath("/work/repo")

    beforeTest {
        mockkObject(ProjectLockUtils)
    }

    afterTest {
        unmockkObject(ProjectLockUtils)
    }

    "returns Success immediately when the project is not open" {
        every { ProjectLockUtils.isProjectOpen(repository, projectPath) } returns false
        val progress = mutableListOf<Int>()

        ShutdownWaiter.waitForShutdown(repository, projectPath, timeoutSeconds = 2) { progress += it } shouldBe
            ShutdownResult.Success

        progress.shouldBeEmpty()
    }

    "returns Success once the project closes" {
        every { ProjectLockUtils.isProjectOpen(repository, projectPath) } returnsMany listOf(true, false)

        ShutdownWaiter.waitForShutdown(repository, projectPath, timeoutSeconds = 2) shouldBe ShutdownResult.Success
    }

    "returns Timeout and reports progress when the project stays open" {
        every { ProjectLockUtils.isProjectOpen(repository, projectPath) } returns true
        val progress = mutableListOf<Int>()

        ShutdownWaiter.waitForShutdown(repository, projectPath, timeoutSeconds = 2) { progress += it } shouldBe
            ShutdownResult.Timeout

        progress shouldContain 1
    }
})
