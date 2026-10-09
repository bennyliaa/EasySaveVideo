package com.easysavevideo.app.browser.bookmarks

interface BookmarkStorage {
    fun load(): List<BrowserBookmark>
    /** Returns only after atomic durable commit. A failure may be uncertain; reload before more writes. */
    fun save(items: List<BrowserBookmark>)
}
enum class BookmarkNotice { ADDED, DUPLICATE, REMOVED, FULL, UNAVAILABLE, SLOW, STALE_PAGE }
data class BookmarkState(
    val items: List<BrowserBookmark> = emptyList(), val ready: Boolean = false,
    val busy: Boolean = false, val notice: BookmarkNotice? = null,
) {
    override fun toString() = "BookmarkState(count=${items.size}, ready=$ready, busy=$busy, notice=$notice)"
}

/** Main-owner state, one background operation maximum. Never queues multiple taps or navigates. */
class BookmarkController(
    private val storage: BookmarkStorage,
    private val worker: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val newId: () -> String,
    private val changed: (BookmarkState) -> Unit,
) {
    var state = BookmarkState(); private set
    private var generation = 0L
    private var closed = false
    fun load() {
        if (closed || state.busy) return
        submit(null) { storage.load().also(BookmarkPolicy::validate).toList() }
    }
    fun add(page: BookmarkPageToken) {
        if (!canMutate()) return
        val address = BookmarkPolicy.canonicalAddress(page.address)
        if (address == null || address != page.address || BookmarkPolicy.safeTitle(page.title, address) != page.title) {
            stalePage(); return
        }
        if (state.items.any { it.address == address }) { set(state.copy(notice = BookmarkNotice.DUPLICATE)); return }
        if (state.items.size >= BookmarkPolicy.MAX_ITEMS) { set(state.copy(notice = BookmarkNotice.FULL)); return }
        val candidate = try { BrowserBookmark(newId(), address, page.title) } catch (_: Exception) { unavailable(); return }
        // Existing items already passed worker-side decode/validation. Do not rescan up to
        // 2 MiB of prior addresses on the UI thread; the codec validates the snapshot on IO.
        if (!BookmarkPolicy.valid(candidate) || state.items.any { it.id == candidate.id }) { unavailable(); return }
        val next = listOf(candidate) + state.items
        submit(BookmarkNotice.ADDED) { storage.save(next); next }
    }
    fun remove(item: BrowserBookmark) {
        if (!canMutate() || item !in state.items) return
        val next = state.items.filterNot { it.id == item.id }
        submit(BookmarkNotice.REMOVED) { storage.save(next); next }
    }
    /** An explicit UI click alone may resolve a still-current item to an address. */
    fun addressForOpen(item: BrowserBookmark): String? = item.takeIf {
        !closed && state.ready && !state.busy && it in state.items && BookmarkPolicy.valid(it)
    }?.address
    fun stalePage() { if (!closed && !state.busy) set(state.copy(notice = BookmarkNotice.STALE_PAGE)) }
    fun dismissNotice() { if (!closed) set(state.copy(notice = null)) }
    fun operationGeneration(): Long? = generation.takeIf { state.busy && !closed }
    /** No second writer is started after a timeout; late completion must reconcile its exact operation. */
    fun slow(operation: Long) {
        if (!closed && state.busy && generation == operation) set(state.copy(ready = false, notice = BookmarkNotice.SLOW))
    }
    fun close() { closed = true }
    private fun canMutate() = !closed && state.ready && !state.busy
    private fun unavailable() { set(state.copy(ready = false, busy = false, notice = BookmarkNotice.UNAVAILABLE)) }
    private fun submit(success: BookmarkNotice?, action: () -> List<BrowserBookmark>) {
        check(!state.busy)
        generation++
        val operation = generation
        set(state.copy(busy = true, notice = null))
        try {
            worker {
                val result = runCatching(action)
                deliver {
                    if (!closed && generation == operation && state.busy) {
                        result.fold(onSuccess = { set(BookmarkState(it.toList(), ready = true, notice = success)) },
                            onFailure = { unavailable() })
                    }
                }
            }
        } catch (_: Exception) { if (!closed && generation == operation && state.busy) unavailable() }
    }
    private fun set(value: BookmarkState) { state = value; changed(value) }
}
