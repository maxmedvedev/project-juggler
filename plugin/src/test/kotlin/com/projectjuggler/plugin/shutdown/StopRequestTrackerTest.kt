package com.projectjuggler.plugin.shutdown

import com.projectjuggler.core.StopRequestSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

class StopRequestTrackerTest : StringSpec({

    val now = Instant.parse("2025-06-01T12:00:00Z")

    fun signal(requestId: String, timestamp: String) = StopRequestSignal(
        requestId = requestId,
        timestamp = timestamp,
        requestedBy = "test",
        autoRestart = true,
        syncTypes = listOf("config"),
        timeoutSeconds = 60,
    )

    fun signalAgedBy(requestId: String, age: Duration) = signal(requestId, now.minus(age).toString())

    "acts on a fresh request once" {
        val tracker = StopRequestTracker(now = { now })
        val fresh = signalAgedBy("req-1", Duration.ofSeconds(10))

        tracker.shouldAct(fresh) shouldBe true
        tracker.shouldAct(fresh) shouldBe false
    }

    "never acts on a six-minute-old request" {
        val tracker = StopRequestTracker(now = { now })
        val stale = signalAgedBy("req-stale", Duration.ofMinutes(6))

        tracker.shouldAct(stale) shouldBe false
        tracker.shouldAct(stale) shouldBe false
    }

    "ignores an unparseable timestamp" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signal("req-garbage", "garbage")) shouldBe false
    }

    "acts on a request exactly five minutes old" {
        val tracker = StopRequestTracker(now = { now })

        tracker.shouldAct(signalAgedBy("req-5", Duration.ofMinutes(5))) shouldBe true
    }
})
