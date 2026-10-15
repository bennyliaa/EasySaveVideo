package com.easysavevideo.app.browser

import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.BrowserLoadState
import com.easysavevideo.core.model.BrowserRequest

/** One accepted history observation. No retained state or navigation/credential authority. */
internal object BrowserSameDocumentUpdate {
    fun accept(
        owner: BrowserSessionController,
        request: BrowserRequest,
        document: BrowserInspectionDocument,
        observedAddress: String?,
        observedRevision: Long,
        canGoBack: () -> Boolean,
        canGoForward: () -> Boolean,
        viewUrl: () -> String?,
        stillCurrent: () -> Boolean,
    ): Boolean {
        val address = BrowserInputPolicy.address(observedAddress ?: return false) ?: return false
        fun current(): Boolean = stillCurrent() && owner.isCurrent(request) &&
            owner.state.tabs.any { it.context == request.context && !it.incognito && it.loadState == BrowserLoadState.READY } &&
            document.revision == observedRevision && document.address == observedAddress
        if (!current()) return false
        val back: Boolean
        val forward: Boolean
        val actual: String?
        try {
            back = canGoBack()
            forward = canGoForward()
            actual = viewUrl()
        } catch (_: Exception) { return false }
        // All native getters may reenter. Validate the exact captured observation after them.
        if (!current() || actual != observedAddress) return false
        return owner.acceptSameDocument(request, address, back, forward)
    }

    /** Replaces only the exact existing private RAM slot, never native WebView history. */
    fun acceptPrivate(
        owner: BrowserSessionController,
        request: BrowserRequest,
        document: BrowserInspectionDocument,
        observedAddress: String?,
        observedRevision: Long,
        memory: SystemBrowserHost.PrivateNavigation,
        viewUrl: () -> String?,
        stillCurrent: () -> Boolean,
    ): Boolean {
        val address = BrowserInputPolicy.address(observedAddress ?: return false) ?: return false
        fun current(): Boolean = stillCurrent() && owner.isCurrent(request) &&
            owner.state.tabs.any { it.context == request.context && it.incognito && it.loadState == BrowserLoadState.READY } &&
            document.revision == observedRevision && document.address == observedAddress
        if (!current() || memory.addresses.size !in 1..128 || memory.index !in memory.addresses.indices) return false
        val position = memory.index
        val addresses = memory.addresses.toList()
        val back = memory.canBack()
        val forward = memory.canForward()
        val actual = try { viewUrl() } catch (_: Exception) { return false }
        // The native getter can reenter any Host owner. Revalidate before either RAM mutation.
        if (!current() || actual != observedAddress || memory.index != position ||
            memory.addresses != addresses || memory.canBack() != back || memory.canForward() != forward) return false
        // No native/user callback from here through both synchronous owner-thread mutations.
        if (!owner.acceptPrivateSameDocument(request, address, back, forward)) return false
        memory.committed(address.value, position)
        return true
    }
}
