package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.core.StopRequestSignal
import com.projectjuggler.plugin.rethrowIfControlFlow
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Decides which stop requests the polling loop acts on: once per request id, and only while the request is at most
 * [maxAge] old. Age is compared in whole minutes, as it always was.
 */
internal class StopRequestTracker(
    private val maxAge: Duration = 5.minutes,
    private val now: () -> Instant = Instant::now,
) {
    private val log = logger<StopRequestTracker>()
    private val processedRequestIds = mutableSetOf<String>()

    /** True once per fresh request id; stale or unparseable timestamps are never acted on and never remembered. */
    fun shouldAct(signal: StopRequestSignal): Boolean {
        if (signal.requestId in processedRequestIds) return false
        if (!isFresh(signal)) {
            log.warn("Ignoring stale stop signal: ${signal.requestId}")
            return false
        }
        processedRequestIds += signal.requestId
        return true
    }

    private fun isFresh(signal: StopRequestSignal): Boolean {
        val signalTime = try {
            Instant.parse(signal.timestamp)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.warn("Stop signal ${signal.requestId} has an unparseable timestamp: ${signal.timestamp}", e)
            return false
        }
        val age = (now().toEpochMilli() - signalTime.toEpochMilli()).milliseconds
        return age.inWholeMinutes <= maxAge.inWholeMinutes
    }
}
