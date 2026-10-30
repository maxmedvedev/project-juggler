package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PortAllocatorTest : StringSpec({
    extensions(KoinTestExtension())

    fun newRepository() = IdeConfigRepository(createTempDir("test-ports"), IdeInstallation("/test/ide", "Test IDE"))

    fun allocator(repository: IdeConfigRepository) = PortAllocator(ProjectManager.getInstance(repository))

    "first port is MIN_PORT" {
        allocator(newRepository()).allocatePort() shouldBe PortAllocator.MIN_PORT
    }

    "skips ports used by other projects of the same IDE" {
        val repository = newRepository()
        repository.saveProjectMetadata(
            ProjectMetadata(ProjectPath("/projects/a"), "2026-10-30T10:00:00Z", debugPort = 5001)
        )
        repository.saveProjectMetadata(
            ProjectMetadata(ProjectPath("/projects/b"), "2026-10-30T10:00:00Z", debugPort = 5002)
        )

        allocator(repository).allocatePort() shouldBe 5003
    }
})
