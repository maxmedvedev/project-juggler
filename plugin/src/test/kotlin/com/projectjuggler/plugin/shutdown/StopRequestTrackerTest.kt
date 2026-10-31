package com.projectjuggler.plugin.shutdown

import com.projectjuggler.core.StopRequestSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class StopRequestTrackerTest : StringSpec({

    val now = Instant.parse("2025-06-01T12:00:00Z")

    fun signal(requestId: String, timestamp: String, timeoutSeconds: Int = 60) = StopRequestSignal(
        requestId = requestId,
        timestamp = timestamp,
        requestedBy = "test",
        autoRestart = true,
        syncTypes = listOf("config"),
        timeoutSeconds = timeoutSeconds,
    )

    fun signalAgedBy(requestId: String, age: Duration, timeoutSeconds: Int = 60) =
        signal(requestId, now.minus(age).toString(), timeoutSeconds)

    "acts on a fresh request once" {
        val tracker = StopRequestTracker(now = { now })
        val fresh = signalAgedBy("req-1", Duration.ofSeconds(10))

        tracker.shouldAct(fresh) shouldBe true
        tracker.shouldAct(fresh) shouldBe false
    }

    "never acts on a request older than its timeoutSeconds" {
        val tracker = StopRequestTracker(now = { now })
        val stale = signalAgedBy("req-stale", Duration.ofSeconds(61))

        tracker.shouldAct(stale) shouldBe false
        tracker.shouldAct(stale) shouldBe false
    }

    "acts on a request exactly timeoutSeconds old" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signalAgedBy("req-60", Duration.ofSeconds(60))) shouldBe true
    }

    "ignores a four-minute-old request with a 60 second timeout" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signalAgedBy("req-4m", Duration.ofMinutes(4))) shouldBe false
    }

    "falls back to five minutes when timeoutSeconds is not positive" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signalAgedBy("req-0-4m", Duration.ofMinutes(4), timeoutSeconds = 0)) shouldBe true
        tracker.shouldAct(signalAgedBy("req-0-6m", Duration.ofMinutes(6), timeoutSeconds = 0)) shouldBe false
    }

    "acts on a nine-minute-old request with a 600 second timeout" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signalAgedBy("req-600", Duration.ofMinutes(9), timeoutSeconds = 600)) shouldBe true
    }

    "keeps rejecting a stale request on later polls" {
        var clock = now
        val tracker = StopRequestTracker(now = { clock })
        val stale = signalAgedBy("req-later", Duration.ofSeconds(90))

        tracker.shouldAct(stale) shouldBe false
        clock = clock.plusSeconds(1)
        tracker.shouldAct(stale) shouldBe false
    }

    "ignores an unparseable timestamp" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signal("req-garbage", "garbage")) shouldBe false
    }
})
