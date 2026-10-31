package com.projectjuggler.plugin.shutdown

import com.intellij.openapi.diagnostic.logger
import com.projectjuggler.core.StopRequestSignal
import com.projectjuggler.plugin.rethrowIfControlFlow
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decides which stop requests the polling loop acts on: once per request id, and only while the request is at most
 * its `timeoutSeconds` old, after which the requester has given up. [fallbackMaxAge] applies when `timeoutSeconds`
 * is not positive.
 */
internal class StopRequestTracker(
    private val fallbackMaxAge: Duration = 5.minutes,
    private val now: () -> Instant = Instant::now,
) {
    private val log = logger<StopRequestTracker>()
    private val processedRequestIds = mutableSetOf<String>()
    private val rejectedRequestIds = mutableSetOf<String>()

    /**
     * True once per request id while its age is at most `timeoutSeconds` (fallback when <= 0). Stale or unparseable
     * requests are never acted on and never remembered as processed; each is logged once.
     */
    fun shouldAct(signal: StopRequestSignal): Boolean {
        if (signal.requestId in processedRequestIds) return false
        if (!isFresh(signal)) return false
        processedRequestIds += signal.requestId
        return true
    }

    private fun isFresh(signal: StopRequestSignal): Boolean {
        val firstRejection = signal.requestId !in rejectedRequestIds
        val signalTime = try {
            Instant.parse(signal.timestamp)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            if (firstRejection) {
                log.warn("Stop signal ${signal.requestId} has an unparseable timestamp: ${signal.timestamp}", e)
            }
            rejectedRequestIds += signal.requestId
            return false
        }
        val maxAge = if (signal.timeoutSeconds > 0) signal.timeoutSeconds.seconds else fallbackMaxAge
        val age = (now().toEpochMilli() - signalTime.toEpochMilli()).milliseconds
        if (age > maxAge) {
            if (firstRejection) {
                log.warn("Ignoring stale stop signal: ${signal.requestId} (age $age, max age $maxAge)")
            }
            rejectedRequestIds += signal.requestId
            return false
        }
        return true
    }
}
