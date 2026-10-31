package com.projectjuggler.plugin.services

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.ProjectId
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.core.SelfSyncResult
import com.projectjuggler.core.SelfSyncResultStore
import com.projectjuggler.core.SyncTypeNames
import com.projectjuggler.di.KoinTestExtension
import com.projectjuggler.plugin.test.RecordingNotificationHandler
import com.projectjuggler.plugin.test.TestApplicationExtension
import com.projectjuggler.plugin.test.registerProject
import com.projectjuggler.plugin.test.testRepository
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.paths.shouldNotExist
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

class SelfSyncReportTest : StringSpec({
    extensions(TestApplicationExtension(), KoinTestExtension())

    val baseDir = createTempDir("juggler")
    val logFile = Path.of("/logs/sync-helper.log")

    /** A fresh repository with one tracked project. */
    fun setUp(): Pair<IdeConfigRepository, ProjectMetadata> {
        val repository = testRepository(createTempDirectory(baseDir, "ide"))
        val project = registerProject(repository, createTempDirectory(baseDir, "proj"))
        return repository to project
    }

    fun result(
        syncTypes: List<String> = listOf(SyncTypeNames.PLUGINS),
        succeeded: Boolean = true,
        message: String? = null,
        restartError: String? = null,
    ) = SelfSyncResult("2026-10-31T12:00:00Z", syncTypes, succeeded, message, restartError)

    fun report(repository: IdeConfigRepository, project: ProjectMetadata, exitCode: Int?) =
        RecordingNotificationHandler().also {
            reportSelfSyncResult(repository, project.id, exitCode, logFile, it)
        }

    "a successful sync shows the synced parts and consumes the result" {
        val (repository, project) = setUp()
        val store = SelfSyncResultStore.getInstance(repository)
        store.write(project, result(listOf(SyncTypeNames.VM_OPTIONS, SyncTypeNames.CONFIG)))

        val notifications = report(repository, project, exitCode = null)

        notifications.infos shouldContainExactly listOf("Synced VM options, configuration for ${project.name}")
        notifications.errors.shouldBeEmpty()
        repository.baseDir.resolve("projects").resolve(project.id.id)
            .resolve(SelfSyncResultStore.RESULT_FILE_NAME).shouldNotExist()

        val second = report(repository, project, exitCode = null)
        second.infos.shouldBeEmpty()
        second.errors.shouldBeEmpty()
    }

    "all three sync types read as all settings" {
        val (repository, project) = setUp()
        SelfSyncResultStore.getInstance(repository).write(
            project,
            result(listOf(SyncTypeNames.VM_OPTIONS, SyncTypeNames.CONFIG, SyncTypeNames.PLUGINS))
        )

        report(repository, project, exitCode = 0).infos shouldContainExactly
            listOf("Synced all settings for ${project.name}")
    }

    "a success with a restart error also shows the restart failure" {
        val (repository, project) = setUp()
        SelfSyncResultStore.getInstance(repository).write(project, result(restartError = "no such file"))

        val notifications = report(repository, project, exitCode = null)

        notifications.infos shouldContainExactly listOf("Synced plugins for ${project.name}")
        notifications.errors shouldContainExactly listOf(
            "Failed to reopen ${project.name} after the sync: no such file. Details: $logFile"
        )
    }

    "a failed sync shows the message and the log path" {
        val (repository, project) = setUp()
        SelfSyncResultStore.getInstance(repository).write(
            project,
            result(listOf(SyncTypeNames.VM_OPTIONS), succeeded = false, message = "Base VM options not set")
        )

        val notifications = report(repository, project, exitCode = 1)

        notifications.infos.shouldBeEmpty()
        notifications.errors shouldContainExactly listOf(
            "Sync of VM options for ${project.name} failed: Base VM options not set. Details: $logFile"
        )
    }

    "no result and a non-zero exit code shows the helper exit" {
        val (repository, project) = setUp()

        val notifications = report(repository, project, exitCode = 2)

        notifications.infos.shouldBeEmpty()
        notifications.errors shouldContainExactly listOf("sync-helper exited with code 2. Details: $logFile")
    }

    "no result at startup or after exit code 0 shows nothing" {
        val (repository, project) = setUp()

        for (exitCode in listOf(null, 0)) {
            val notifications = report(repository, project, exitCode)
            notifications.infos.shouldBeEmpty()
            notifications.errors.shouldBeEmpty()
        }
    }

    "an unknown project only shows a non-zero helper exit" {
        val (repository, _) = setUp()
        val gone = ProjectId("gone-0000000000000000")

        val atStartup = RecordingNotificationHandler()
        reportSelfSyncResult(repository, gone, null, logFile, atStartup)
        atStartup.infos.shouldBeEmpty()
        atStartup.errors.shouldBeEmpty()

        val afterExit = RecordingNotificationHandler()
        reportSelfSyncResult(repository, gone, 1, logFile, afterExit)
        afterExit.errors shouldContainExactly listOf("sync-helper exited with code 1. Details: $logFile")
    }
})
