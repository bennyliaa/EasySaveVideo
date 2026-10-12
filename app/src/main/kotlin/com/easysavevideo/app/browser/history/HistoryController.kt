package com.easysavevideo.app.browser.history

import com.easysavevideo.app.browser.BrowserInputPolicy

interface HistoryStorage {
    fun load(): List<BrowserHistoryItem>
    /** Durable atomic commit followed by verification. Uncertain outcomes require a fresh read. */
    fun save(items: List<BrowserHistoryItem>)
}
enum class HistoryNotice { UNAVAILABLE, SLOW, CLEARED, REMOVED, STALE_LIST }
data class HistoryState(
    val items: List<BrowserHistoryItem> = emptyList(), val ready: Boolean = false,
    val busy: Boolean = false, val notice: HistoryNotice? = null,
) {
    override fun toString() = "HistoryState(count=${items.size}, ready=$ready, busy=$busy, notice=$notice)"
}
class HistoryRowToken internal constructor(internal val owner: Any, internal val revision: Long,
    internal val item: BrowserHistoryItem) {
    override fun toString() = "HistoryRowToken(redacted)"
}
class HistoryClearConfirmation internal constructor(internal val owner: Any, internal val revision: Long,
    internal val item: BrowserHistoryItem?) {
    internal var consumed = false
    val clearsAll: Boolean get() = item == null
    override fun toString() = "HistoryClearConfirmation(clearsAll=$clearsAll)"
}

/** One main owner, one IO operation. Automatic visits are coalesced in a bounded queue, never
 * blocking navigation. No optimistic success; uncertainty disables opens/writes until reread. */
class HistoryController(
    private val storage: HistoryStorage, val navigation: HistoryNavigationGate,
    private val retention: HistoryRetentionPolicy,
    private val worker: (() -> Unit) -> Unit, private val deliver: (() -> Unit) -> Unit,
    private val newId: () -> String, private val now: () -> Long,
    private val changed: (HistoryState) -> Unit,
) {
    var state = HistoryState(); private set
    private val owner = Any()
    private var revision = 0L
    private var operation = 0L
    private var closed = false
    private val pending = LinkedHashMap<String, HistoryVisit>()

    fun load() {
        if (closed || state.busy) return
        submit(null) {
            val stored = storage.load().also(HistoryPolicy::validate)
            val retained = retention.retain(stored, now())
            if (retained != stored) storage.save(retained)
            retained
        }
    }
    fun record(visit: HistoryVisit) {
        if (closed || !navigation.take(visit)) return
        if (!state.ready && !state.busy) return
        pending.remove(visit.address)
        pending[visit.address] = visit
        while (pending.size > HistoryPolicy.MAX_ITEMS) pending.remove(pending.keys.first())
        drain()
    }
    fun row(item: BrowserHistoryItem): HistoryRowToken? = item.takeIf {
        !closed && state.ready && !state.busy && it in state.items
    }?.let { HistoryRowToken(owner, revision, it) }
    fun addressForOpen(token: HistoryRowToken): String? {
        if (!current(token)) return null
        return BrowserInputPolicy.address(token.item.address)?.value?.takeIf { it == token.item.address }
    }
    fun requestRemove(token: HistoryRowToken): HistoryClearConfirmation? =
        token.takeIf(::current)?.let { HistoryClearConfirmation(owner, revision, it.item) }
    /** Explicit reset is possible even for an unreadable/corrupt store, never automatic recovery. */
    fun requestClear(): HistoryClearConfirmation? = if (closed || state.busy) null
        else HistoryClearConfirmation(owner, revision, null)
    fun cancel(confirmation: HistoryClearConfirmation) {
        if (confirmation.owner === owner) confirmation.consumed = true
    }
    fun confirm(confirmation: HistoryClearConfirmation) {
        if (closed || state.busy || confirmation.owner !== owner || confirmation.consumed) return
        confirmation.consumed = true
        if (confirmation.revision != revision || confirmation.item != null &&
            (!state.ready || confirmation.item !in state.items)) {
            set(state.copy(notice = HistoryNotice.STALE_LIST)); return
        }
        // No earlier in-flight navigation or queued completion may recreate removed history.
        navigation.invalidate(); pending.clear()
        val next = confirmation.item?.let { item -> state.items.filterNot { it.id == item.id } } ?: emptyList()
        submit(if (confirmation.clearsAll) HistoryNotice.CLEARED else HistoryNotice.REMOVED) {
            storage.save(next); next
        }
    }
    fun dismissNotice() { if (!closed) set(state.copy(notice = null)) }
    fun operationGeneration(): Long? = operation.takeIf { !closed && state.busy }
    fun slow(expected: Long) {
        if (!closed && state.busy && operation == expected) set(state.copy(ready = false, notice = HistoryNotice.SLOW))
    }
    fun close() { closed = true; navigation.invalidate(); pending.clear() }
    private fun current(token: HistoryRowToken) = !closed && state.ready && !state.busy &&
        token.owner === owner && token.revision == revision && token.item in state.items && HistoryPolicy.valid(token.item)
    private fun drain() {
        if (closed || !state.ready || state.busy || pending.isEmpty()) return
        val visits = pending.values.toList(); pending.clear()
        val previous = state.items
        submit(null) {
            val nowMillis = now()
            val next = visits.fold(previous) { items, visit -> HistoryPolicy.visit(items, visit, newId, retention, nowMillis) }
            storage.save(next); next
        }
    }
    private fun submit(notice: HistoryNotice?, action: () -> List<BrowserHistoryItem>) {
        check(!state.busy && operation < Long.MAX_VALUE && revision < Long.MAX_VALUE)
        val expected = ++operation; revision++
        set(state.copy(busy = true, notice = null))
        try {
            worker {
                val result = runCatching(action)
                deliver {
                    if (!closed && operation == expected && state.busy) {
                        result.fold(onSuccess = {
                            set(HistoryState(it.toList(), ready = true, notice = notice)); drain()
                        }, onFailure = { unavailable() })
                    }
                }
            }
        } catch (_: Exception) { if (!closed && operation == expected && state.busy) unavailable() }
    }
    private fun unavailable() {
        pending.clear(); navigation.invalidate()
        set(state.copy(ready = false, busy = false, notice = HistoryNotice.UNAVAILABLE))
    }
    private fun set(value: HistoryState) { state = value; changed(value) }
}
