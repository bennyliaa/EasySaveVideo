package com.easysavevideo.discovery

import com.easysavevideo.core.model.RepeatPolicy

enum class SeenEvent { SHOWN, OPENED, PLAYED, SAVED, DISMISSED }
data class SeenRecord(
    val profileId: String,
    val fingerprint: String,
    val firstDiscoveredAt: Long,
    val shownAt: Long? = null,
    val openedAt: Long? = null,
    val playedAt: Long? = null,
    val savedAt: Long? = null,
    val dismissedAt: Long? = null,
) {
    init { require(firstDiscoveredAt >= 0); listOf(shownAt, openedAt, playedAt, savedAt, dismissedAt).forEach { require(it == null || it >= 0) } }
    fun record(event: SeenEvent, at: Long): SeenRecord {
        require(at >= 0)
        fun latest(old: Long?) = maxOf(old ?: at, at)
        return when (event) {
            SeenEvent.SHOWN -> copy(shownAt = latest(shownAt))
            SeenEvent.OPENED -> copy(openedAt = latest(openedAt))
            SeenEvent.PLAYED -> copy(playedAt = latest(playedAt))
            SeenEvent.SAVED -> copy(savedAt = latest(savedAt))
            SeenEvent.DISMISSED -> copy(dismissedAt = latest(dismissedAt))
        }
    }
    override fun toString(): String = "SeenRecord([redacted])"
}

object SeenHistoryPolicy {
    fun allows(policy: RepeatPolicy, seen: SeenRecord?): Boolean = when (policy) {
        RepeatPolicy.PREFER_NEW, RepeatPolicy.SHOW_ALL -> true
        RepeatPolicy.HIDE_WATCHED -> seen?.openedAt == null && seen?.playedAt == null
        RepeatPolicy.HIDE_SHOWN -> seen?.shownAt == null
    }
    fun priority(policy: RepeatPolicy, seen: SeenRecord?): Int = if (policy == RepeatPolicy.PREFER_NEW && seen?.shownAt != null) 1 else 0
}

/** Feed real viewport fractions using a monotonic clock. Prefetch and page materialization never call this. */
class ShownVisibilityTracker(private val dwellMillis: Long = 500) {
    private val visibleSince = mutableMapOf<String, Long>()
    private val emitted = mutableSetOf<String>()
    init { require(dwellMillis >= 500) }
    @Synchronized fun update(fingerprint: String, visibleFraction: Double, nowMillis: Long): Boolean {
        require(nowMillis >= 0)
        if (!visibleFraction.isFinite() || visibleFraction < 0.5) { visibleSince.remove(fingerprint); return false }
        require(visibleFraction <= 1.0)
        if (fingerprint in emitted) return false
        val start = visibleSince.getOrPut(fingerprint) { nowMillis }
        if (nowMillis < start) { visibleSince[fingerprint] = nowMillis; return false }
        if (nowMillis - start < dwellMillis) return false
        emitted += fingerprint; visibleSince.remove(fingerprint); return true
    }
    @Synchronized fun clear() { visibleSince.clear(); emitted.clear() }
}

internal fun mergeSeenRecords(profileId: String, fingerprint: String, records: List<SeenRecord>): SeenRecord? {
    if (records.isEmpty()) return null
    return SeenRecord(profileId, fingerprint, records.minOf { it.firstDiscoveredAt },
        records.mapNotNull { it.shownAt }.maxOrNull(), records.mapNotNull { it.openedAt }.maxOrNull(),
        records.mapNotNull { it.playedAt }.maxOrNull(), records.mapNotNull { it.savedAt }.maxOrNull(),
        records.mapNotNull { it.dismissedAt }.maxOrNull())
}
