package com.easysavevideo.searxng

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Shared overflow-safe not-before policy for the socket boundary and metadata/runtime layers. */
object SearxngRetryAfter {
    fun retryAt(value: String?, nowEpochMillis: Long): Long {
        val seconds = value?.trim()?.takeIf { it.matches(Regex("[0-9]+")) }
        if (seconds != null) {
            val count = seconds.toLongOrNull() ?: return Long.MAX_VALUE
            return maxOf(plus(nowEpochMillis, 1000), if (count > Long.MAX_VALUE / 1000) Long.MAX_VALUE
                else plus(nowEpochMillis, count * 1000))
        }
        val date = try { value?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } }
            catch (_: Exception) { null }
        return if (date != null && date > nowEpochMillis) date else plus(nowEpochMillis, 30_000)
    }
    private fun plus(value: Long, increment: Long): Long = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}
