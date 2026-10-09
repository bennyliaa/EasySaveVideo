package com.easysavevideo.app.discovery

import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.SeenEvent
import com.easysavevideo.discovery.VideoSearchResult

/** Native browser state only. No URL, title, request headers, or persisted identifier is added. */
data class DiscoveryOriginBrowserState(
    val context: BrowserContextRef,
    val epoch: Long,
    val navigationGeneration: Long,
    val incognito: Boolean = false,
    val open: Boolean = true,
) {
    internal val eligible: Boolean get() = open && !incognito && epoch > 0 && navigationGeneration >= 0
    override fun toString() = "DiscoveryOriginBrowserState([redacted])"
}

/** Object identity is the capability. It cannot be serialized and contains no discovery payload. */
class DiscoveryOriginToken internal constructor() {
    override fun toString() = "DiscoveryOriginToken([redacted])"
}

/** A one-selection, one-task capability. Retaining it does not retain a page or result. */
class DiscoveryOriginSaveProof internal constructor() {
    override fun toString() = "DiscoveryOriginSaveProof([redacted])"
}

enum class DiscoveryOriginMediaOutcome { COMMITTED, NOT_COMMITTED }
/** Page origin alone is never video-identity evidence. Current application adapters use UNKNOWN. */
enum class DiscoveryOriginMediaIdentityMatch { UNKNOWN, VERIFIED }
enum class DiscoveryOriginHistoryOutcome { RECORDED, STALE_OR_UNAVAILABLE, IDENTITY_UNVERIFIED, HISTORY_FAILED }

/** Media publication and optional history persistence are deliberately independent outcomes. */
data class DiscoveryOriginSaveOutcome(
    val media: DiscoveryOriginMediaOutcome,
    val history: DiscoveryOriginHistoryOutcome,
)

/**
 * Valid only while the owner's one-shot record callback is running under its lock. The controller
 * must also validate the complete target, page and result against its current checkpoint. Its
 * storage before-commit guard can use [isCurrent] to recheck expiry immediately before committing.
 */
class DiscoveryOriginEventProof internal constructor(
    val target: DiscoveryControlTarget,
    val page: PageSnapshot,
    val result: VideoSearchResult,
    private val current: () -> Boolean,
) {
    val event: SeenEvent get() = SeenEvent.SAVED
    fun isCurrent(): Boolean = current()
    override fun toString() = "DiscoveryOriginEventProof([redacted])"
}

/**
 * Bounded, process-local correlation only. It never observes playback, starts work, persists
 * capabilities, infers media success, or upgrades OPENED to PLAYED. The native browser owner must
 * invalidate before redirect/new navigation/close/tab switch. The controller must invalidate on
 * destruction/reinitialization. Persisted discovery revision fences remain the final authority.
 *
 * Navigation invalidation and the final history write share this lock: an invalidation that wins
 * the lock prevents the write; a completed write linearizes before a later navigation. The record
 * callback must not wait for the browser/UI thread. No browser getter is read on a worker thread.
 */
class DiscoveryOriginTracker(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 120_000,
    private val capacity: Int = 12,
) : AutoCloseable {
    private enum class Stage { OPENED, SELECTED, BOUND, COMPLETING }
    private class Entry(
        val token: DiscoveryOriginToken,
        val target: DiscoveryControlTarget,
        val page: PageSnapshot,
        val result: VideoSearchResult,
        val browser: DiscoveryOriginBrowserState,
        val deadline: Long,
        var stage: Stage = Stage.OPENED,
        var proof: DiscoveryOriginSaveProof? = null,
        var taskId: String? = null,
    )
    private val lock = Any()
    private val entries = LinkedHashMap<DiscoveryOriginToken, Entry>()
    private var lastNow: Long? = null
    private var closed = false

    init {
        require(ttlMillis in 1..300_000)
        require(capacity in 1..12)
    }

    /** Call only after the selected discovery result was accepted as the native navigation. */
    fun open(target: DiscoveryControlTarget, page: PageSnapshot, result: VideoSearchResult,
        browser: DiscoveryOriginBrowserState): DiscoveryOriginToken? = synchronized(lock) {
        val now = timeAndPrune() ?: return@synchronized null
        if (closed || !browser.eligible || target.stamp != page.stamp ||
            target.viewRevision != page.revision || target.controllerGeneration < 0 ||
            result.resultId !in page.orderedResultIds || now > Long.MAX_VALUE - ttlMillis) return@synchronized null
        // A new open in the same native context cannot leave an older selection attributable.
        entries.entries.removeAll { it.value.browser.context == browser.context }
        if (entries.size >= capacity) entries.remove(entries.keys.first())
        val token = DiscoveryOriginToken()
        entries[token] = Entry(token, target, page.copy(orderedResultIds = page.orderedResultIds.toList()),
            result.copy(provenance = result.provenance.toSet(), identityAliases = result.identityAliases.toSet(),
                rights = result.rights.detached()), browser, now + ttlMillis)
        token
    }

    /** Capture on the native user Save selection, while the exact opened page is still current. */
    fun selectForSave(token: DiscoveryOriginToken, browser: DiscoveryOriginBrowserState): DiscoveryOriginSaveProof? =
        synchronized(lock) {
            timeAndPrune() ?: return@synchronized null
            val entry = entries[token] ?: return@synchronized null
            if (!browser.eligible || browser != entry.browser) {
                entries.remove(token)
                return@synchronized null
            }
            if (entry.stage != Stage.OPENED) return@synchronized null
            DiscoveryOriginSaveProof().also { entry.proof = it; entry.stage = Stage.SELECTED }
        }

    /** Bind only the newly accepted native task. Replayed binding cannot replace that task. */
    fun bindTask(proof: DiscoveryOriginSaveProof, taskId: String): Boolean = synchronized(lock) {
        timeAndPrune() ?: return@synchronized false
        val entry = find(proof) ?: return@synchronized false
        if (entry.stage != Stage.SELECTED || taskId.isBlank() || taskId.length > 128) return@synchronized false
        entry.taskId = taskId
        entry.stage = Stage.BOUND
        true
    }

    fun abandon(proof: DiscoveryOriginSaveProof) = synchronized(lock) {
        find(proof)?.let { entries.remove(it.token) }
        Unit
    }

    /**
     * COMMITTED must come exclusively from the Save adapter's verified committed receipt. A
     * task/status/list-row observation is insufficient. This function cannot validate media bytes.
     * VERIFIED additionally requires a trusted, independent bridge proving that the saved media is
     * the discovery result itself. The same page, URL, candidate selection, or committed receipt
     * does not establish that identity. No such bridge is currently supplied by the application.
     * A failed, stale or throwing history callback never changes the supplied media outcome.
     */
    fun completeSave(proof: DiscoveryOriginSaveProof, taskId: String, media: DiscoveryOriginMediaOutcome,
        currentTarget: DiscoveryControlTarget?,
        identityMatch: DiscoveryOriginMediaIdentityMatch = DiscoveryOriginMediaIdentityMatch.UNKNOWN,
        record: (DiscoveryOriginEventProof) -> DiscoveryOriginHistoryOutcome): DiscoveryOriginSaveOutcome = synchronized(lock) {
        fun skipped() = DiscoveryOriginSaveOutcome(media, DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE)
        timeAndPrune() ?: return@synchronized skipped()
        val entry = find(proof) ?: return@synchronized skipped()
        if (entry.stage != Stage.BOUND || entry.taskId != taskId) return@synchronized skipped()
        // A matching terminal callback consumes once, even when history is stale or unavailable.
        if (media != DiscoveryOriginMediaOutcome.COMMITTED || entry.target != currentTarget) {
            entries.remove(entry.token)
            return@synchronized skipped()
        }
        if (identityMatch != DiscoveryOriginMediaIdentityMatch.VERIFIED) {
            entries.remove(entry.token)
            return@synchronized DiscoveryOriginSaveOutcome(media, DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED)
        }
        entry.stage = Stage.COMPLETING
        val event = DiscoveryOriginEventProof(entry.target, entry.page, entry.result) {
            synchronized(lock) {
                timeAndPrune() != null && entries[entry.token] === entry && entry.stage == Stage.COMPLETING
            }
        }
        val history = try {
            record(event)
        } catch (_: Exception) {
            // Never expose exception text or throw it into the completed media task's error path.
            DiscoveryOriginHistoryOutcome.HISTORY_FAILED
        } finally {
            entries.remove(entry.token)
        }
        DiscoveryOriginSaveOutcome(media, history)
    }

    fun invalidate(context: BrowserContextRef) = synchronized(lock) {
        entries.entries.removeAll { it.value.browser.context == context }
        Unit
    }

    fun invalidateAll() = synchronized(lock) { entries.clear() }

    /** Use an existing native lifecycle tick to release expired payloads; this creates no timer. */
    fun pruneExpired(): Int = synchronized(lock) {
        val previousSize = entries.size
        timeAndPrune()
        previousSize - entries.size
    }

    override fun close() = synchronized(lock) { closed = true; entries.clear() }

    private fun find(proof: DiscoveryOriginSaveProof): Entry? = entries.values.firstOrNull { it.proof === proof }

    /** Also fail closed on injected clock failure, rollback or wrap. Calls do not renew the TTL. */
    private fun timeAndPrune(): Long? {
        if (closed) return null
        val now = try { nowMillis() } catch (_: Exception) { entries.clear(); return null }
        val prior = lastNow
        lastNow = now
        if (prior != null && now < prior) { entries.clear(); return null }
        entries.entries.removeAll { now >= it.value.deadline }
        return now
    }
}
