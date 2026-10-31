package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class SelfSyncResultStoreTest : StringSpec({
    extensions(KoinTestExtension())

    val project = ProjectMetadata(path = ProjectPath("/tmp/test-project"), lastOpened = "2025-01-01T00:00:00Z")
    val result = SelfSyncResult(
        timestamp = "2025-01-01T00:00:00Z",
        syncTypes = listOf(SyncTypeNames.VM_OPTIONS, SyncTypeNames.PLUGINS),
        succeeded = false,
        message = "copy failed",
        restartError = "Failed to restart IntelliJ: boom",
    )

    fun store() = SelfSyncResultStore.getInstance(
        IdeConfigRepository(createTempDir("test-self-sync"), IdeInstallation("/test/ide", "Test IDE"))
    )

    "consume returns the written result and deletes the file" {
        val store = store()
        store.write(project, result)

        store.consume(project) shouldBe result

        store.getResultFile(project).exists() shouldBe false
    }

    "a second consume returns null" {
        val store = store()
        store.write(project, result)
        store.consume(project)

        store.consume(project).shouldBeNull()
    }

    "consume without a result returns null and creates no file" {
        val store = store()

        store.consume(project).shouldBeNull()

        store.getResultFile(project).exists() shouldBe false
    }

    "a corrupt result file gives null and is deleted" {
        val store = store()
        val file = store.getResultFile(project)
        Files.createDirectories(file.parent)
        file.writeText("{not json")

        store.consume(project).shouldBeNull()

        file.exists() shouldBe false
    }

    "the result file survives the signal cleanup" {
        val repository = IdeConfigRepository(createTempDir("test-self-sync"), IdeInstallation("/test/ide", "Test IDE"))
        val store = SelfSyncResultStore.getInstance(repository)
        val signalManager = ShutdownSignalManager.getInstance(repository)
        signalManager.createStopRequest(project, autoRestart = true, syncTypes = result.syncTypes, timeoutSeconds = 60)
        store.write(project, result)

        signalManager.cleanup(project)

        signalManager.readStopRequest(project).shouldBeNull()
        store.consume(project) shouldBe result
    }
})
