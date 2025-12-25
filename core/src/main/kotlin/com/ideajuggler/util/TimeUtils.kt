package com.ideajuggler.util

import java.time.Duration
import java.time.Instant

object TimeUtils {
    fun formatRelativeTime(timestampStr: String): String {
        return try {
            val timestamp = Instant.parse(timestampStr)
            val now = Instant.now()
            val duration = Duration.between(timestamp, now)

            when {
                duration.toMinutes() < 1 -> "just now"
                duration.toMinutes() < 60 -> "${duration.toMinutes()} minute${if (duration.toMinutes() == 1L) "" else "s"} ago"
                duration.toHours() < 24 -> "${duration.toHours()} hour${if (duration.toHours() == 1L) "" else "s"} ago"
                duration.toDays() < 7 -> "${duration.toDays()} day${if (duration.toDays() == 1L) "" else "s"} ago"
                duration.toDays() < 30 -> "${duration.toDays() / 7} week${if (duration.toDays() / 7 == 1L) "" else "s"} ago"
                else -> "${duration.toDays() / 30} month${if (duration.toDays() / 30 == 1L) "" else "s"} ago"
            }
        } catch (e: Exception) {
            timestampStr
        }
    }
}
