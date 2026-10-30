package com.projectjuggler.core

import com.projectjuggler.config.IdeConfigRepository
import com.projectjuggler.config.IdeInstallation
import com.projectjuggler.config.ProjectMetadata
import com.projectjuggler.config.ProjectPath
import com.projectjuggler.di.KoinTestExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ShutdownSignalManagerTest : StringSpec({
    extensions(KoinTestExtension())

    "should create and read stop request signal" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(tempDir, testInstallation)
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)

            val project = ProjectMetadata(
                path = ProjectPath("/tmp/test-project"),
                lastOpened = "2025-01-01T00:00:00Z"
            )

            // Create signal
            val signal = signalManager.createStopRequest(
                project = project,
                autoRestart = true,
                syncTypes = listOf("vmoptions", "config"),
                timeoutSeconds = 60
            )

            signal.autoRestart shouldBe true
            signal.syncTypes shouldBe listOf("vmoptions", "config")

            // Read signal
            val readSignal = signalManager.readStopRequest(project)
            readSignal.shouldNotBeNull()
            readSignal.requestId shouldBe signal.requestId
            readSignal.autoRestart shouldBe true
            readSignal.syncTypes shouldBe listOf("vmoptions", "config")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should persist the given timeout" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(tempDir, testInstallation)
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)

            val project = ProjectMetadata(
                path = ProjectPath("/tmp/test-project"),
                lastOpened = "2025-01-01T00:00:00Z"
            )

            signalManager.createStopRequest(project, autoRestart = true, syncTypes = listOf("config"), timeoutSeconds = 15)

            signalManager.readStopRequest(project)!!.timeoutSeconds shouldBe 15
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should cleanup signal files" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(tempDir, testInstallation)
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)

            val project = ProjectMetadata(
                path = ProjectPath("/tmp/test-project"),
                lastOpened = "2025-01-01T00:00:00Z"
            )

            // Create signal
            signalManager.createStopRequest(
                project = project,
                autoRestart = false,
                syncTypes = listOf("config"),
                timeoutSeconds = 60
            )

            // Verify signal exists
            signalManager.readStopRequest(project).shouldNotBeNull()

            // Cleanup
            signalManager.cleanup(project)

            // Verify signal is gone
            signalManager.readStopRequest(project).shouldBeNull()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "should acquire and release sync lock" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val testInstallation = IdeInstallation("/test/ide", "Test IDE")
            val ideConfigRepository = IdeConfigRepository(tempDir, testInstallation)
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)

            val project = ProjectMetadata(
                path = ProjectPath("/tmp/test-project"),
                lastOpened = "2025-01-01T00:00:00Z"
            )

            // Acquire lock
            val lock1 = signalManager.acquireSyncLock(project)
            lock1.shouldNotBeNull()

            // Try to acquire again - should fail
            val lock2 = signalManager.acquireSyncLock(project)
            lock2.shouldBeNull()

            // Release lock
            lock1.close()

            // Now we should be able to acquire it
            val lock3 = signalManager.acquireSyncLock(project)
            lock3.shouldNotBeNull()
            lock3.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "cleanup keeps the sync lock file" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val ideConfigRepository = IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)
            val project = testProject()
            val signalsDir = signalsDir(ideConfigRepository, project)

            val lock = signalManager.acquireSyncLock(project)
            lock.shouldNotBeNull()
            try {
                signalManager.createStopRequest(project, autoRestart = false, syncTypes = listOf("config"), timeoutSeconds = 60)

                signalManager.cleanup(project)

                signalsDir.resolve(ShutdownSignalManager.STOP_REQUEST_FILE_NAME).exists() shouldBe false
                signalsDir.resolve(ShutdownSignalManager.SYNC_LOCK_FILE_NAME).exists() shouldBe true
                signalManager.acquireSyncLock(project).shouldBeNull()
            } finally {
                lock.close()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "cleanupStaleSignals keeps the sync lock file and deletes unparseable files" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val ideConfigRepository = IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)
            val project = testProject()
            val signalsDir = signalsDir(ideConfigRepository, project)

            val lock = signalManager.acquireSyncLock(project)
            lock.shouldNotBeNull()
            try {
                val garbage = signalsDir.resolve("garbage.txt")
                garbage.writeText("not json")

                signalManager.cleanupStaleSignals(project, maxAgeMinutes = 5)

                signalsDir.resolve(ShutdownSignalManager.SYNC_LOCK_FILE_NAME).exists() shouldBe true
                garbage.exists() shouldBe false
                signalManager.acquireSyncLock(project).shouldBeNull()
            } finally {
                lock.close()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "cleanupStaleSignals deletes stop requests older than maxAgeMinutes" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val ideConfigRepository = IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)
            val project = testProject()
            val signalsDir = signalsDir(ideConfigRepository, project)

            val staleSignal = StopRequestSignal(
                requestId = "stale",
                timestamp = Instant.now().minus(10, ChronoUnit.MINUTES).toString(),
                requestedBy = "system",
                autoRestart = false,
                syncTypes = listOf("config"),
                timeoutSeconds = 60
            )
            Files.createDirectories(signalsDir)
            signalsDir.resolve(ShutdownSignalManager.STOP_REQUEST_FILE_NAME)
                .writeText(Json.encodeToString(staleSignal))
            signalManager.readStopRequest(project).shouldNotBeNull()

            signalManager.cleanupStaleSignals(project, maxAgeMinutes = 5)

            signalManager.readStopRequest(project).shouldBeNull()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    "closing the sync lock keeps the file" {
        val tempDir = Files.createTempDirectory("test")
        try {
            val ideConfigRepository = IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))
            val signalManager = ShutdownSignalManager.getInstance(ideConfigRepository)
            val project = testProject()
            val lockFile = signalsDir(ideConfigRepository, project).resolve(ShutdownSignalManager.SYNC_LOCK_FILE_NAME)

            val lock1 = signalManager.acquireSyncLock(project)
            lock1.shouldNotBeNull()
            lock1.close()

            lockFile.exists() shouldBe true

            val lock2 = signalManager.acquireSyncLock(project)
            lock2.shouldNotBeNull()
            lock2.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
})

private fun testProject() = ProjectMetadata(
    path = ProjectPath("/tmp/test-project"),
    lastOpened = "2025-01-01T00:00:00Z"
)

private fun signalsDir(ideConfigRepository: IdeConfigRepository, project: ProjectMetadata): Path =
    DirectoryManager.getInstance(ideConfigRepository).getProjectRoot(project).resolve("signals")
