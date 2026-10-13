package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.DiscoveryCheckpoint
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.SessionStamp
import java.util.concurrent.atomic.AtomicBoolean

/** UI-only identity. The query field is SearchProfile.queryKey, never the editable/raw query. */
internal data class DiscoveryReadingPageKey(
    val stamp: SessionStamp,
    val revision: Long,
    val pageIndex: Int,
    val queryKey: String,
    val demo: Boolean,
) {
    override fun toString() = "DiscoveryReadingPageKey([redacted])"
}

/** One current-page reading position, containing no result metadata or retained result list. */
internal data class DiscoveryReadingAnchor(
    val key: DiscoveryReadingPageKey,
    val resultId: String,
    val scrollOffset: Int,
) {
    override fun toString() = "DiscoveryReadingAnchor([redacted])"
}

internal data class DiscoveryReadingPosition(val index: Int, val scrollOffset: Int)

/** Direct, in-memory display provenance from a successful restore, never task/access authority. */
internal data class DiscoveryReadingRestoreProof(val previousStamp: SessionStamp, val currentStamp: SessionStamp) {
    override fun toString() = "DiscoveryReadingRestoreProof([redacted])"
}

/** A disposed effect cannot restore or replace a newer reading anchor, even after returning. */
internal class DiscoveryReadingRestorationClaim(private val key: DiscoveryReadingPageKey) {
    private val retired = AtomicBoolean(false)

    fun isCurrent(currentKey: DiscoveryReadingPageKey?): Boolean =
        !retired.get() && validReadingPageKey(key) && key == currentKey

    fun retire() { retired.set(true) }

    override fun toString() = "DiscoveryReadingRestorationClaim([redacted])"
}

/** Pure, bounded UI state only. No storage, task, source, or page-demand side effects. */
internal object DiscoveryReadingAnchorPolicy {
    private val top = DiscoveryReadingPosition(0, 0)
    private const val codecVersion = 1
    private const val codecFields = 9

    fun key(page: PageSnapshot?, appliedQueryKey: String, demo: Boolean): DiscoveryReadingPageKey? {
        if (page == null || page.pageSize !in 1..100 || !validReadingResultIds(page.orderedResultIds)) return null
        return DiscoveryReadingPageKey(page.stamp, page.revision, page.pageIndex, appliedQueryKey, demo)
            .takeIf(::validReadingPageKey)
    }

    /** Called only after the existing coordinator restore succeeds; nothing is restored here. */
    fun restoreProof(previous: DiscoveryCheckpoint?, restored: DiscoveryCheckpoint?): DiscoveryReadingRestoreProof? {
        if (previous == null || restored == null || previous.stamp.sessionId != restored.stamp.sessionId ||
            restored.stamp.epoch <= previous.stamp.epoch || previous.profile != restored.profile ||
            previous.queryAuthorized != restored.queryAuthorized ||
            previous.sourceConfigurationKey != restored.sourceConfigurationKey ||
            !validRetainedReadingCheckpoint(previous) || !validRetainedReadingCheckpoint(restored)) return null
        val restampedPool = previous.pool.copy(pages = previous.pool.pages.map { it.copy(stamp = restored.stamp) })
        if (restampedPool != restored.pool) return null
        return DiscoveryReadingRestoreProof(previous.stamp, restored.stamp)
    }

    /** No epoch ranges or chains: this proof is valid only for its exact displayed generation. */
    fun restoredFromStamp(proof: DiscoveryReadingRestoreProof?, current: DiscoveryCheckpoint?,
        displayedPage: PageSnapshot?): SessionStamp? {
        if (proof == null || current == null || displayedPage == null ||
            proof.previousStamp.sessionId != proof.currentStamp.sessionId ||
            proof.currentStamp.epoch <= proof.previousStamp.epoch || current.stamp != proof.currentStamp ||
            displayedPage.stamp != proof.currentStamp || current.pool.pages.size !in 1..500 ||
            displayedPage !in current.pool.pages) return null
        return proof.previousStamp
    }

    /** The caller supplies the rendered order, not an index from a previously rendered page. */
    fun capture(key: DiscoveryReadingPageKey?, orderedResultIds: List<String>, firstVisibleIndex: Int,
        scrollOffset: Int): DiscoveryReadingAnchor? {
        if (key == null || !validReadingPageKey(key) || !validReadingResultIds(orderedResultIds) ||
            firstVisibleIndex !in orderedResultIds.indices || scrollOffset < 0) return null
        return DiscoveryReadingAnchor(key, orderedResultIds[firstVisibleIndex], scrollOffset)
    }

    /** A stable ID survives reorder or a directly proven restore; new searches start at the top. */
    fun resolve(saved: DiscoveryReadingAnchor?, key: DiscoveryReadingPageKey?,
        orderedResultIds: List<String>, restoredFromStamp: SessionStamp? = null): DiscoveryReadingPosition {
        if (saved == null || key == null || !validReadingAnchor(saved) || !validReadingPageKey(key) ||
            !validReadingResultIds(orderedResultIds)) return top
        val directRestore = saved.key.stamp == restoredFromStamp &&
            key.stamp.sessionId == saved.key.stamp.sessionId && key.stamp.epoch > saved.key.stamp.epoch &&
            saved.key.copy(stamp = key.stamp) == key
        if (saved.key != key && !directRestore) return top
        val index = orderedResultIds.indexOf(saved.resultId)
        return if (index >= 0) DiscoveryReadingPosition(index, saved.scrollOffset) else top
    }

    /** Small saveable primitives only. Invalid manually constructed state is not serialized. */
    fun encode(anchor: DiscoveryReadingAnchor): List<Any> {
        if (!validReadingAnchor(anchor)) return emptyList()
        val key = anchor.key
        return listOf(codecVersion, key.stamp.sessionId, key.stamp.epoch, key.revision, key.pageIndex,
            key.queryKey, key.demo, anchor.resultId, anchor.scrollOffset)
    }

    /** Fail closed without coercion, truncation, or exceptions for malformed saved-state values. */
    fun decode(saved: List<*>): DiscoveryReadingAnchor? {
        if (saved.size != codecFields || saved[0] !is Int || saved[0] != codecVersion) return null
        val sessionId = saved[1] as? String ?: return null
        val epoch = saved[2] as? Long ?: return null
        val revision = saved[3] as? Long ?: return null
        val pageIndex = saved[4] as? Int ?: return null
        val queryKey = saved[5] as? String ?: return null
        val demo = saved[6] as? Boolean ?: return null
        val resultId = saved[7] as? String ?: return null
        val scrollOffset = saved[8] as? Int ?: return null
        if (!readingSessionId.matches(sessionId) || epoch <= 0 || revision < 0 || pageIndex !in 0 until 500 ||
            !readingHash.matches(queryKey) || !readingHash.matches(resultId) || scrollOffset < 0) return null
        return DiscoveryReadingAnchor(
            DiscoveryReadingPageKey(SessionStamp(sessionId, epoch), revision, pageIndex, queryKey, demo),
            resultId, scrollOffset)
    }
}

private val readingSessionId = Regex("[A-Za-z0-9_.-]{1,128}")
private val readingHash = Regex("[a-f0-9]{64}")

private fun validReadingPageKey(key: DiscoveryReadingPageKey): Boolean =
    readingSessionId.matches(key.stamp.sessionId) && key.stamp.epoch > 0 && key.revision >= 0 &&
        key.pageIndex in 0 until 500 && readingHash.matches(key.queryKey)

private fun validReadingAnchor(anchor: DiscoveryReadingAnchor): Boolean =
    validReadingPageKey(anchor.key) && readingHash.matches(anchor.resultId) && anchor.scrollOffset >= 0

private fun validReadingResultIds(ids: List<String>): Boolean {
    if (ids.size !in 1..100) return false
    val unique = HashSet<String>(ids.size)
    return ids.all { readingHash.matches(it) && unique.add(it) }
}

private fun validRetainedReadingCheckpoint(value: DiscoveryCheckpoint): Boolean {
    val pool = value.pool
    if (pool.entries.size !in 1..500 || pool.pages.size !in 1..500 || pool.viewRevision < 0 || pool.nextOrder < 0) return false
    val entryIds = HashSet<String>(pool.entries.size)
    if (!pool.entries.all { readingHash.matches(it.result.resultId) && entryIds.add(it.result.resultId) }) return false
    val pageIds = HashSet<String>(entryIds.size)
    return pool.pages.withIndex().all { (index, page) ->
        page.stamp == value.stamp && page.revision == pool.viewRevision && page.pageIndex == index &&
            page.pageSize in 1..100 && validReadingResultIds(page.orderedResultIds) &&
            page.orderedResultIds.all { it in entryIds && pageIds.add(it) }
    }
}
