package com.projectjuggler.process

import com.projectjuggler.platform.Platform
import com.projectjuggler.test.createTempDir
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class ProcessLauncherImplTest : StringSpec({
    val tempDir = createTempDir("process-launcher-test")
    val sh = Paths.get("/bin/sh")
    val unixOnly: (io.kotest.core.test.TestCase) -> Boolean = { !Platform.isWindows() }

    fun newLogDir(): Path = Files.createTempDirectory(tempDir, "logs")

    fun launchLogs(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) dir.listDirectoryEntries("launch-*") else emptyList()

    /** Launches [script] and returns the failure reported within [waitSeconds], or `null`. */
    fun launchAndAwait(
        launcher: ProcessLauncher,
        script: String,
        environment: Map<String, String?> = emptyMap(),
        waitSeconds: Long = 15
    ): LaunchFailure? {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<LaunchFailure>()
        launcher.launch(sh, listOf("-c", script), environment) {
            failure.set(it)
            latch.countDown()
        }
        latch.await(waitSeconds, TimeUnit.SECONDS)
        return failure.get()
    }

    /** Waits until the watcher has removed all launch logs, as it does after an exit with code 0. */
    fun awaitNoLaunchLogs(dir: Path) {
        val deadline = System.currentTimeMillis() + 15_000
        while (launchLogs(dir).isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    "applyEnvironment sets non-null values and removes null ones" {
        val builder = ProcessBuilder()
        builder.environment()["PJ_TEST_REMOVED"] = "inherited"

        builder.applyEnvironment(mapOf("PJ_TEST_SET" to "1", "PJ_TEST_REMOVED" to null))

        builder.environment() shouldContain ("PJ_TEST_SET" to "1")
        builder.environment() shouldNotContainKey "PJ_TEST_REMOVED"
    }

    "should report exit code and output of a process that fails".config(enabledIf = unixOnly) {
        val logDir = newLogDir()

        val failure = launchAndAwait(ProcessLauncherImpl(logDir), "echo out-line; echo err-line >&2; exit 3")

        failure.shouldNotBeNull()
        failure.exitCode shouldBe 3
        failure.stdout shouldContain "out-line"
        failure.stderr shouldContain "err-line"
        val logs = launchLogs(logDir)
        logs shouldHaveSize 2
        logs.map { it.name.substringAfterLast('.') }.toSet() shouldBe setOf("out", "err")
    }

    "should not report a process that exits successfully and should remove its logs".config(enabledIf = unixOnly) {
        val logDir = newLogDir()

        val failure = launchAndAwait(ProcessLauncherImpl(logDir), "echo ok; exit 0", waitSeconds = 1)
        awaitNoLaunchLogs(logDir)

        failure.shouldBeNull()
        launchLogs(logDir).shouldBeEmpty()
    }

    "should not report a process still running after the watch timeout and should remove its logs".config(enabledIf = unixOnly) {
        val logDir = newLogDir()
        val launcher = ProcessLauncherImpl(logDir, watchTimeout = Duration.ofMillis(200))

        // The child outlives the watch window and then fails; the failure must go unreported
        val failure = launchAndAwait(launcher, "sleep 2; exit 5", waitSeconds = 1)
        awaitNoLaunchLogs(logDir)

        failure.shouldBeNull()
        launchLogs(logDir).shouldBeEmpty()
    }

    "should pass environment variables to the child".config(enabledIf = unixOnly) {
        val failure = launchAndAwait(
            ProcessLauncherImpl(newLogDir()),
            "test \"\$PJ_TEST\" = yes || exit 7",
            environment = mapOf("PJ_TEST" to "yes"),
            waitSeconds = 1
        )

        failure.shouldBeNull()
    }

    "should not block the JVM: watcher thread is a daemon".config(enabledIf = unixOnly) {
        ProcessLauncherImpl(newLogDir()).launch(sh, listOf("-c", "sleep 1"))

        val watcher = Thread.getAllStackTraces().keys.firstOrNull { it.name == ProcessLauncherImpl.WATCHER_THREAD_NAME }

        watcher.shouldNotBeNull()
        watcher.isDaemon.shouldBeTrue()
    }

    "should prune log files older than seven days".config(enabledIf = unixOnly) {
        val logDir = newLogDir()
        val old = Files.writeString(logDir.resolve("launch-old.out"), "old")
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(8))))
        val recent = Files.writeString(logDir.resolve("launch-new.out"), "new")

        launchAndAwait(ProcessLauncherImpl(logDir), "exit 0", waitSeconds = 1)

        Files.exists(old).shouldBeFalse()
        Files.exists(recent).shouldBeTrue()
    }

    "should still launch when the log directory cannot be created".config(enabledIf = unixOnly) {
        val regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "")

        val failure = launchAndAwait(ProcessLauncherImpl(regularFile.resolve("logs")), "echo lost; exit 2")

        failure.shouldNotBeNull()
        failure.exitCode shouldBe 2
        failure.stdout shouldBe ""
        failure.stderr shouldBe ""
    }
})
