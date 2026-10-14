package com.easysavevideo.app.browser.history

import com.easysavevideo.app.browser.NavigationAdmission
import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.BrowserLoadState
import com.easysavevideo.core.model.BrowserRequest
import com.easysavevideo.core.model.BrowserTab

/** One native Entry, one exact View. Restored addresses/Bundles never arm this recorder.
 * Authority comes only from a host-issued live navigation and its accepted native start.
 * A later POST/form navigation in that live View can start a new request without an override.
 * No Android dependency: tests supply synthetic identities, not a simulated WebView. */
internal class HistoryRecordingAdapter(
    private val context: BrowserContextRef,
    private val ownerView: Any,
    private val navigation: HistoryNavigationGate,
    private val record: (HistoryVisit) -> Unit,
) {
    private var liveNavigationRequested = false
    private var lastStartedEpoch = 0L
    private var startedRequest: BrowserRequest? = null
    private var permit: HistoryNavigationPermit? = null

    /** Call only when issuing loadUrl/goBack/goForward, never for restoreState. */
    fun controlledNavigationRequested() { liveNavigationRequested = true }

    fun started(request: BrowserRequest, tab: BrowserTab?, selected: BrowserContextRef?,
        view: Any, address: String, admission: NavigationAdmission?, currentEntry: Boolean,
        acceptedStart: Boolean, active: Boolean) {
        // Redirect/repeated-start callbacks belong to the same request. In particular, they
        // cannot mint a new post-clear generation for a navigation started before clearing.
        if (!acceptedStart || !currentEntry || view !== ownerView || request.context != context ||
            tab == null || tab.context != context || tab.epoch != request.epoch ||
            tab.loadState != BrowserLoadState.LOADING || tab.address?.value != address ||
            admission?.accepts(address) != true || request.epoch <= lastStartedEpoch) return
        lastStartedEpoch = request.epoch
        startedRequest = request
        permit = null
        if (!liveNavigationRequested || !active || tab.incognito || selected != context) return
        permit = navigation.begin(context, request.epoch, ownerView)
    }

    fun finished(request: BrowserRequest, tab: BrowserTab?, selected: BrowserContextRef?,
        view: Any, actualAddress: String?, callbackAddress: String, admission: NavigationAdmission?,
        title: String?, currentEntry: Boolean, acceptedFinish: Boolean, active: Boolean, nowMillis: Long) {
        if (startedRequest !== request) return
        val captured = permit
        // Even an inactive/rejected completion cannot be replayed after returning to the tab.
        permit = null
        if (view !== ownerView || request.context != context ||
            admission?.accepts(callbackAddress) != true) return
        navigation.complete(captured, tab, selected, view, actualAddress, callbackAddress, title,
            currentEntry, acceptedFinish, active, nowMillis)?.let(record)
    }

    /** Preserve request identity so a late redirect cannot re-arm a cancelled navigation. */
    fun cancel() { permit = null }
}
