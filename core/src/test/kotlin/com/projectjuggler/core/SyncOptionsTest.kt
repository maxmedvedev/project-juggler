package com.projectjuggler.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SyncOptionsTest : StringSpec({

    "should have correct default values" {
        val options = SyncOptions.DEFAULT

        options.stopIfRunning shouldBe false
        options.autoRestart shouldBe false
        options.shutdownTimeout shouldBe 60
    }

    "should create STOP_AND_RESTART preset" {
        val options = SyncOptions.STOP_AND_RESTART

        options.stopIfRunning shouldBe true
        options.autoRestart shouldBe true
        options.shutdownTimeout shouldBe 60
    }

    "should support custom options" {
        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = false,
            shutdownTimeout = 120
        )

        options.stopIfRunning shouldBe true
        options.autoRestart shouldBe false
        options.shutdownTimeout shouldBe 120
    }

    "should support progress callbacks" {
        var lastProgress: SyncProgress? = null

        val options = SyncOptions(
            stopIfRunning = true,
            autoRestart = true,
            onProgress = { progress ->
                lastProgress = progress
            }
        )

        // Simulate progress events
        options.onProgress(SyncProgress.Stopping(5))
        lastProgress shouldBe SyncProgress.Stopping(5)

        options.onProgress(SyncProgress.Syncing)
        lastProgress shouldBe SyncProgress.Syncing

        options.onProgress(SyncProgress.Restarting)
        lastProgress shouldBe SyncProgress.Restarting

        options.onProgress(SyncProgress.Error("test error"))
        (lastProgress as? SyncProgress.Error)?.message shouldBe "test error"
    }
})
