package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*

/**
 * Single-owner state machine. The Android owner calls this on its UI thread and performs the
 * actual stop/destroy operations. No Provider, Store, WebView, Android Uri, or Activity dependency.
 */
class BrowserSessionController(private val maxTabs: Int = 32) {
    init { require(maxTabs in 1..100) }
    var state: BrowserSessionState = BrowserSessionState()
        private set
    private val requests = mutableMapOf<String, BrowserRequest>()
    private val usedSessionIds = mutableSetOf<String>()

    fun openTab(tabId: String, sessionId: String, incognito: Boolean = false): BrowserTab {
        require(state.tabs.size < maxTabs)
        require(state.tabs.none { it.context.tabId == tabId })
        require(usedSessionIds.add(sessionId)) { "A tab session identity cannot be reused" }
        val tab = BrowserTab(BrowserContextRef(tabId, sessionId), incognito)
        state = BrowserSessionState(state.tabs + tab, tabId)
        return tab
    }

    fun selectTab(tabId: String): Boolean {
        if (state.tabs.none { it.context.tabId == tabId }) return false
        state = state.copy(selectedTabId = tabId)
        return true
    }

    /** The returned context must be stopped and destroyed by the platform adapter. */
    fun closeTab(tabId: String): BrowserContextRef? {
        val index = state.tabs.indexOfFirst { it.context.tabId == tabId }
        if (index < 0) return null
        val removed = state.tabs[index]
        requests.remove(tabId)
        val remaining = state.tabs.filterNot { it.context.tabId == tabId }
        val selected = when {
            remaining.isEmpty() -> null
            state.selectedTabId != tabId -> state.selectedTabId
            else -> remaining[index.coerceAtMost(remaining.lastIndex)].context.tabId
        }
        state = BrowserSessionState(remaining, selected)
        return removed.context
    }

    fun beginNavigation(tabId: String, address: PageAddress, operation: OperationContext): BrowserRequest =
        begin(tabId, address, operation)

    /** The adapter must call its actual goBack/goForward with the returned request identity. */
    fun beginHistoryNavigation(tabId: String, operation: OperationContext): BrowserRequest =
        begin(tabId, null, operation)

    fun reload(tabId: String, operation: OperationContext): BrowserRequest? {
        val address = tab(tabId)?.address ?: return null
        return begin(tabId, address, operation)
    }

    private fun begin(tabId: String, address: PageAddress?, operation: OperationContext): BrowserRequest {
        val old = requireNotNull(tab(tabId)) { "Unknown tab" }
        check(old.epoch < Long.MAX_VALUE)
        val request = BrowserRequest(old.context, old.epoch + 1, address, operation)
        requests[tabId] = request
        update(old.copy(epoch = request.epoch, address = address ?: old.address,
            title = null, loadState = BrowserLoadState.LOADING, progressPercent = 0, error = null))
        return request
    }

    /** Redirects are accepted only on the adapter-verified main-frame request generation. */
    fun acceptPageStarted(request: BrowserRequest, address: PageAddress, nowMillis: Long): Boolean =
        updateLoading(request, nowMillis) { it.copy(address = address, progressPercent = 0) }

    fun acceptProgress(request: BrowserRequest, progressPercent: Int, nowMillis: Long): Boolean =
        updateLoading(request, nowMillis) { it.copy(progressPercent = progressPercent.coerceIn(0, 100)) }

    fun acceptTitle(request: BrowserRequest, title: String?, nowMillis: Long): Boolean {
        if (!isCurrent(request) || request.operation.stopReason(nowMillis) != null) return false
        val current = tab(request.context.tabId) ?: return false
        if (current.loadState != BrowserLoadState.LOADING && current.loadState != BrowserLoadState.READY) return false
        update(current.copy(title = title?.take(512)))
        return true
    }

    fun acceptPageFinished(
        request: BrowserRequest,
        address: PageAddress,
        canGoBack: Boolean,
        canGoForward: Boolean,
        nowMillis: Long,
    ): Boolean = updateLoading(request, nowMillis) {
        it.copy(address = address, loadState = BrowserLoadState.READY, progressPercent = 100,
            canGoBack = canGoBack, canGoForward = canGoForward, error = null)
    }

    /** Adapter-verified ordinary same-document observation; the completed load is not restarted. */
    fun acceptSameDocument(
        request: BrowserRequest,
        address: PageAddress,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ): Boolean {
        if (!isCurrent(request)) return false
        val current = tab(request.context.tabId) ?: return false
        if (current.incognito || current.loadState != BrowserLoadState.READY) return false
        update(current.copy(address = address, canGoBack = canGoBack, canGoForward = canGoForward))
        return true
    }

    /** Adapter-verified private observation; history flags come from the private RAM owner. */
    fun acceptPrivateSameDocument(
        request: BrowserRequest,
        address: PageAddress,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ): Boolean {
        if (!isCurrent(request)) return false
        val current = tab(request.context.tabId) ?: return false
        if (!current.incognito || current.loadState != BrowserLoadState.READY) return false
        update(current.copy(address = address, canGoBack = canGoBack, canGoForward = canGoForward))
        return true
    }

    fun acceptPageFailed(request: BrowserRequest, error: ErrorEnvelope, nowMillis: Long): Boolean =
        updateLoading(request, nowMillis) { it.copy(loadState = BrowserLoadState.FAILED, error = error) }

    /** Stops logical execution immediately. Caller must also invoke WebView.stopLoading(). */
    fun cancelNavigation(tabId: String): BrowserContextRef? {
        val current = tab(tabId) ?: return null
        if (current.loadState != BrowserLoadState.LOADING) return null
        requests.remove(tabId)
        update(current.copy(epoch = increment(current.epoch), loadState = BrowserLoadState.CANCELLED,
            error = null))
        return current.context
    }

    /** Return contexts whose real underlying operation needs stopping. */
    fun expireOperations(nowMillis: Long): List<BrowserContextRef> {
        val expired = requests.values.filter { request ->
            tab(request.context.tabId)?.loadState == BrowserLoadState.LOADING &&
                request.operation.stopReason(nowMillis) != null
        }
        expired.forEach { request ->
            val current = tab(request.context.tabId) ?: return@forEach
            val cancelled = request.operation.cancelToken.isCancelled
            requests.remove(request.context.tabId)
            update(current.copy(epoch = increment(current.epoch),
                loadState = if (cancelled) BrowserLoadState.CANCELLED else BrowserLoadState.FAILED,
                error = if (cancelled) null else ErrorEnvelope(ErrorCode.DEADLINE_EXCEEDED,
                    ErrorModule.BROWSER, true, UserAction.RETRY)))
        }
        return expired.map { it.context }
    }

    /** The adapter determines all WebViews sharing the failed renderer. Independent tasks survive. */
    fun rendererGone(affectedTabIds: Set<String>): List<BrowserContextRef> {
        val affected = state.tabs.filter { it.context.tabId in affectedTabIds }
        affected.forEach { current ->
            requests.remove(current.context.tabId)
            update(current.copy(epoch = increment(current.epoch),
                loadState = BrowserLoadState.RENDERER_UNAVAILABLE,
                error = ErrorEnvelope(ErrorCode.RENDERER_GONE, ErrorModule.BROWSER, true, UserAction.RETRY)))
        }
        return affected.map { it.context }
    }

    /** Incognito functional state is never passed to normal persistence. */
    fun persistableSession(): BrowserSessionCheckpoint {
        val normal = state.tabs.filterNot { it.incognito }.map { RestorableTab(it.context.tabId, it.address) }
        val selected = state.selectedTabId?.takeIf { id -> normal.any { it.tabId == id } }
            ?: normal.firstOrNull()?.tabId
        return BrowserSessionCheckpoint(normal, selected)
    }

    /** Restores addresses only, with fresh request/session identities and no implicit network work. */
    fun restore(checkpoint: BrowserSessionCheckpoint, sessionIdFactory: (String) -> String) {
        check(state.tabs.isEmpty()) { "Restore is only valid for an empty session" }
        checkpoint.tabs.distinctBy { it.tabId }.take(maxTabs).forEach { saved ->
            val restored = openTab(saved.tabId, sessionIdFactory(saved.tabId))
            update(restored.copy(address = saved.address))
        }
        checkpoint.selectedTabId?.let(::selectTab)
    }

    fun isCurrent(request: BrowserRequest): Boolean {
        val current = tab(request.context.tabId) ?: return false
        return current.context == request.context && current.epoch == request.epoch &&
            requests[request.context.tabId] === request
    }

    private fun updateLoading(request: BrowserRequest, nowMillis: Long, change: (BrowserTab) -> BrowserTab): Boolean {
        if (!isCurrent(request) || request.operation.stopReason(nowMillis) != null) return false
        val current = tab(request.context.tabId) ?: return false
        if (current.loadState != BrowserLoadState.LOADING) return false
        update(change(current))
        return true
    }
    private fun tab(id: String) = state.tabs.firstOrNull { it.context.tabId == id }
    private fun update(tab: BrowserTab) { state = state.copy(tabs = state.tabs.map { if (it.context == tab.context) tab else it }) }
    private fun increment(value: Long): Long { check(value < Long.MAX_VALUE); return value + 1 }
}
