package com.easysavevideo.app.browser.history

import com.easysavevideo.app.browser.bookmarks.BookmarkPolicy
import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.BrowserLoadState
import com.easysavevideo.core.model.BrowserTab

/** Private functional data. Never pass this data to diagnostics, exports or providers. */
data class BrowserHistoryItem(val id: String, val address: String, val title: String, val visitedAtMillis: Long) {
    override fun toString() = "BrowserHistoryItem(redacted)"
}

/** Explicit product choice: callers must supply a policy; there is intentionally no default TTL. */
data class HistoryRetentionPolicy(val maxItems: Int, val maxAgeMillis: Long?) {
    init { require(maxItems in 1..HistoryPolicy.MAX_ITEMS); require(maxAgeMillis == null || maxAgeMillis > 0) }
    fun retain(items: List<BrowserHistoryItem>, nowMillis: Long): List<BrowserHistoryItem> {
        require(nowMillis >= 0)
        return items.filter { item ->
            // A backward wall-clock adjustment must not prematurely erase recent visits.
            maxAgeMillis == null || item.visitedAtMillis > nowMillis || nowMillis - item.visitedAtMillis < maxAgeMillis
        }.take(maxItems)
    }
}

object HistoryPolicy {
    const val MAX_ITEMS = 200
    const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000
    fun valid(item: BrowserHistoryItem): Boolean = item.visitedAtMillis >= 0 &&
        BookmarkPolicy.valid(com.easysavevideo.app.browser.bookmarks.BrowserBookmark(item.id, item.address, item.title))
    fun validate(items: List<BrowserHistoryItem>) {
        require(items.size <= MAX_ITEMS && items.all(::valid))
        require(items.map { it.id }.distinct().size == items.size)
        require(items.map { it.address }.distinct().size == items.size)
    }

    /** Recency is acceptance order, not wall-clock order. Repeat addresses retain their stable ID. */
    fun visit(items: List<BrowserHistoryItem>, visit: HistoryVisit, id: () -> String,
        retention: HistoryRetentionPolicy, nowMillis: Long): List<BrowserHistoryItem> {
        validate(items)
        val retained = retention.retain(items, nowMillis)
        val previous = retained.firstOrNull { it.address == visit.address }
        val item = BrowserHistoryItem(previous?.id ?: id(), visit.address, visit.title, visit.visitedAtMillis)
        require(valid(item))
        return retention.retain(listOf(item) + retained.filterNot { it.address == item.address }, nowMillis)
            .also(::validate)
    }
}

/** Single-use authority minted at a navigation start. Clear invalidates all earlier permits. */
class HistoryNavigationPermit internal constructor(
    internal val owner: Any, internal val generation: Long,
    internal val context: BrowserContextRef, internal val epoch: Long, internal val view: Any,
) {
    internal var consumed = false
    override fun toString() = "HistoryNavigationPermit(redacted)"
}

class HistoryVisit internal constructor(
    internal val owner: Any, internal val generation: Long,
    val address: String, val title: String, val visitedAtMillis: Long,
) {
    internal var consumed = false
    override fun toString() = "HistoryVisit(redacted)"
}

/** Main-owner event boundary. The adapter must also verify its actual Entry and NavigationAdmission,
 * and supply true only when BrowserSessionController.acceptPageFinished accepted this callback.
 * A page-start or History API/hash callback alone never grants history-writing authority. */
class HistoryNavigationGate {
    private val owner = Any()
    private var generation = 0L
    fun begin(context: BrowserContextRef, epoch: Long, view: Any): HistoryNavigationPermit {
        require(epoch > 0)
        return HistoryNavigationPermit(owner, generation, context, epoch, view)
    }
    fun invalidate() { check(generation < Long.MAX_VALUE); generation++ }
    fun complete(permit: HistoryNavigationPermit?, tab: BrowserTab?, selected: BrowserContextRef?,
        view: Any, actualAddress: String?, admittedAddress: String?, title: String?,
        currentEntry: Boolean, acceptedFinish: Boolean, active: Boolean, nowMillis: Long): HistoryVisit? {
        if (permit == null || permit.owner !== owner || permit.generation != generation || permit.consumed ||
            !acceptedFinish || !currentEntry || !active || tab == null || tab.incognito ||
            tab.context != selected || tab.context != permit.context || tab.epoch != permit.epoch ||
            view !== permit.view || tab.loadState != BrowserLoadState.READY || nowMillis < 0 ||
            actualAddress == null || actualAddress != admittedAddress || tab.address?.value != actualAddress) return null
        val address = BookmarkPolicy.canonicalAddress(actualAddress) ?: return null
        permit.consumed = true
        return HistoryVisit(owner, generation, address, BookmarkPolicy.safeTitle(title, address), nowMillis)
    }
    internal fun take(visit: HistoryVisit): Boolean {
        if (visit.owner !== owner || visit.generation != generation || visit.consumed) return false
        visit.consumed = true
        return true
    }
}
