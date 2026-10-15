package com.easysavevideo.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import java.util.WeakHashMap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.save.SaveInspectionPage
import com.easysavevideo.app.save.PageSaveSelection
import com.easysavevideo.app.save.AuthenticationOriginToken
import com.easysavevideo.app.save.AuthenticationOriginDocument
import com.easysavevideo.app.save.AuthenticationOriginDocuments
import com.easysavevideo.app.save.authenticationOriginInteractionCurrent
import com.easysavevideo.app.browser.bookmarks.BookmarkPageToken
import com.easysavevideo.app.browser.bookmarks.BookmarkPolicy
import com.easysavevideo.app.browser.history.AndroidHistoryRuntime
import com.easysavevideo.app.browser.history.HistoryRecordingAdapter
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.VideoSearchResult
import java.util.UUID
import com.easysavevideo.app.browser.privacy.*

/** Main-thread Android adapter. WebView and Android objects never cross into Core. */
class SystemBrowserHost(
    private val context: Context,
    private val errors: ErrorSink,
    private val onCheckpoint: (BrowserSessionCheckpoint) -> Unit,
    private val browserCommandAllowed: () -> Boolean,
    private val browserSurfaceAllowed: () -> Boolean,
    private val onChooseFiles: (FileChoice) -> Unit,
    private val onDiscoverySaveCompleted: DiscoverySaveOriginCompletion,
    private val ordinaryHistory: AndroidHistoryRuntime? = null,
) : BrowserAdapter, AutoCloseable {
    private val ordinaryController = BrowserSessionController(maxTabs = 12)
    private var privateController = BrowserSessionController(maxTabs = 12)
    private var privateMode = false
    private val controller: BrowserSessionController get() = if (privateMode) privateController else ordinaryController
    private val privateRuntime = PrivateBrowsingRuntime.get(context)
    private var privateSession: PrivateBrowsingSession? = null
    private var privateLease: PrivateSessionLease? = null
    private var privateIntegration: PrivateBrowserIntegration? = null
    private var privatePresentationBinding: PrivateBrowserPresentationBinding? = null
    private var boundPrivatePresentation: PrivateBrowsingPresentationClaim? = null
    private var issuedPrivatePresentation: PrivateBrowsingPresentationClaim? = null
    private var privatePresentationGeneration: Any = Any()
    private var openingPrivate = false
    private var openingGeneration = 0L
    private var transitioning = false
    private var checkingPrivateAudio = false
    private var retiringPrivate = false
    private var privacyBlocked = false
    private var privateReleaseFailed = false
    private var closing = false
    private val privateViews = WeakHashMap<WebView, PrivateBrowsingSession>()
    // Failed native releases must stay strongly owned until process-level recovery; GC is not drain.
    private val quarantinedViews = mutableSetOf<WebView>()
    internal val isPrivateMode: Boolean get() = privateMode
    internal val privateStatus: PrivateBrowsingStatus get() = privateRuntime.status
    internal val privateIntegrationReady: Boolean get() = integrationReady() && !privacyBlocked && !closed
    internal val privateEntryAvailable: Boolean get() = canAct() && !privateMode && foreground &&
        browserSurfaceAllowed() && integrationReady() && canEnterPrivateMode() &&
        privateRuntime.status.phase in setOf(PrivateBrowsingPhase.READY, PrivateBrowsingPhase.ACTIVE)
    internal fun installPrivateIntegration(value: PrivateBrowserIntegration) {
        requireMain()
        check(!closed && !closing && !openingPrivate && privateSession == null && privateIntegration == null)
        // Only the trusted Activity composition installs this port after all three Save owners,
        // typed persistence, capture/window barriers and actual drain adapters have been wired.
        privateIntegration = value
    }
    /** Root input ownership is mandatory and default-closed, independent of capture eligibility. */
    internal fun installPrivatePresentationBinding(value: PrivateBrowserPresentationBinding) {
        requireMain()
        check(!closed && !closing && !openingPrivate && privateSession == null && privatePresentationBinding == null)
        check(!value.inputLifetime.isAdmitted)
        privatePresentationBinding = value
    }
    private fun canEnterPrivateMode(): Boolean = try { privateIntegration?.canEnterPrivateMode() == true } catch (_: Exception) { false }
    private fun integrationReady(): Boolean = try {
        privateIntegration?.ready() == true && privatePresentationBinding?.ready() == true
    } catch (_: Exception) { false }
    /** Revoke before any root/capture callback or native transition. No provider work is performed. */
    private fun revokePrivatePresentationInput() {
        privatePresentationGeneration = Any()
        boundPrivatePresentation = null
        privatePresentationBinding?.inputLifetime?.revoke()
        privateViews.keys.toList().forEach(privateRuntime::revokeViewInput)
    }
    private fun canAct(): Boolean = !closed && !closing && !openingPrivate && !transitioning && !checkingPrivateAudio && !retiringPrivate && !privacyBlocked
    private fun protectPrivateTransition(lifecycle: PrivateBrowserLifecycleTransition? = null): Boolean {
        if (transitioning) return false
        transitioning = true
        return try {
            revokePrivatePresentationInput()
            val integration = privateIntegration ?: return false
            val protected = if (lifecycle == null) integration.protectForPresentationTransition()
                else integration.protectForLifecycle(lifecycle)
            protected && !privacyBlocked
        }
        catch (_: Exception) { false }
        finally { transitioning = false }
    }
    private fun cancelPrivateOpening() {
        if (!openingPrivate) return
        openingPrivate = false; openingGeneration++
        privateRuntime.cancelOpening()
    }
    internal fun enterPrivateMode(): Boolean {
        requireMain()
        if (!canAct() || privateMode || !foreground || !browserSurfaceAllowed() || !integrationReady() || !canEnterPrivateMode()) return false
        if (!protectPrivateTransition()) { blockPrivateExposure(); return false }
        if (!leaveCurrentTab()) return false
        val existing = privateSession
        if (existing != null) {
            if (!privateRuntime.owns(existing) || privateLease?.isLive() != true) { exitPrivateSession(); return false }
            privateMode = true; interactionChanged(); publish(); resumeSelectedView(allowRehydrate = true); return !privacyBlocked
        }
        openingPrivate = true
        val generation = ++openingGeneration
        val accepted = try { privateRuntime.open { session ->
            val expected = openingPrivate && generation == openingGeneration
            if (expected) openingPrivate = false
            if (session == null) return@open
            if (!expected || closed || closing || privacyBlocked || !foreground || !browserSurfaceAllowed() ||
                !integrationReady() || !canEnterPrivateMode() || !protectPrivateTransition()) {
                // An unpublished session has no Host-owned Views or Save leases.
                privateRuntime.retire(session, { it() }, { it() })
                return@open
            }
            privateSession = session; privateLease = PrivateSessionLease(session); privateReleaseFailed = false
            privateController = BrowserSessionController(maxTabs = 12)
            privateController.openTab(UUID.randomUUID().toString(), UUID.randomUUID().toString(), incognito = true)
            privateMode = true; interactionChanged(); publish()
        } } catch (_: Exception) { false }
        if (!accepted && generation == openingGeneration) openingPrivate = false
        return accepted && !privacyBlocked
    }
    internal fun returnToOrdinaryMode(): Boolean {
        requireMain()
        if (closed || closing || transitioning || retiringPrivate || privacyBlocked) return false
        cancelPrivateOpening()
        if (!privateMode) return true
        if (!leaveCurrentTab()) return false
        privateMode = false; interactionChanged(); publish(); resumeSelectedView(allowRehydrate = true)
        return !privacyBlocked
    }
    /** Host fences first; runtime alone revokes the actual session and owns asynchronous clearing. */
    internal fun exitPrivateSession() {
        requireMain()
        if (retiringPrivate || transitioning) return
        cancelPrivateOpening()
        val session = privateSession ?: return
        val lease = privateLease
        retiringPrivate = true
        val protected = protectPrivateTransition()
        if (!protected) blockPrivateExposure(retire = false)
        transitioning = true
        val owner = privateController
        // Remove live authority before invoking any native/user callback. Local lease revocation
        // dispatches real network-source cancellations before page destruction; independent projection is not a source.
        privateMode = false; privateSession = null; privateLease = null
        interactionChanged(); authenticationDocuments.clear()
        lease?.revokeAndDrain { }
        // Fullscreen and file/JS results are independent native holders; retire them even when
        // one callback throws, and never acknowledge pages released after uncertain teardown.
        try { jsDialogs.cancel() } catch (_: Exception) { privateReleaseFailed = true }
        try { cancelFiles() } catch (_: Exception) { privateReleaseFailed = true }
        try { hideFullscreen() } catch (_: Exception) { privateReleaseFailed = true }
        var pagesAttempted = false
        var pagesReleased = false
        fun retirePages(done: () -> Unit) {
            if (!pagesAttempted) {
                pagesAttempted = true
                var failed = !protected || privateReleaseFailed || lease == null
                owner.state.tabs.toList().forEach { tab ->
                    try { owner.closeTab(tab.context.tabId) } catch (_: Exception) { failed = true }
                    try { if (!destroyEntry(tab.context)) failed = true } catch (_: Exception) { failed = true }
                }
                // Include an entry created before a native callback interrupted publication.
                entries.values.filter { it.session === session }.toList().forEach { entry ->
                    try { if (!destroyEntry(entry.context)) failed = true } catch (_: Exception) { failed = true }
                }
                privateNavigation.clear()
                pagesReleased = !failed && !privateReleaseFailed
            }
            if (pagesReleased) done() // Never acknowledge an uncertain detach/destroy/mute.
        }
        val accepted = try { privateRuntime.retire(session, ::retirePages, { done ->
            if (lease != null) lease.revokeAndDrain(done)
        }) } catch (_: Exception) { false }
        if (!accepted) { retirePages { }; blockPrivateExposure(retire = false) }
        transitioning = false; retiringPrivate = false
        if (privateReleaseFailed) blockPrivateExposure(retire = false)
        publish(); if (!privacyBlocked) resumeSelectedView()
    }
    private fun blockPrivateExposure(retire: Boolean = true) {
        revokePrivatePresentationInput()
        privacyBlocked = true; platformAvailable = false; privateReleaseFailed = true
        // This fallback is additional to the required Activity opaque cover. It is not proof that
        // window/recents protection succeeded; the Host stays blocked until it is recreated.
        privateViews.keys.toList().forEach { view ->
            try { view.visibility = View.INVISIBLE } catch (_: Exception) { quarantinedViews += view }
            try { privateRuntime.mute(view) } catch (_: Exception) { quarantinedViews += view }
        }
        try { fullScreenView?.visibility = View.INVISIBLE } catch (_: Exception) { }
        state = BrowserSessionState()
        notice = BrowserNotice.Text("无痕保护未能确认，页面已停用。请关闭并重新打开应用后重试。")
        if (retire && !transitioning && !retiringPrivate) exitPrivateSession()
    }
    /** Protection-path only: synchronously mute every private View without consulting grants. */
    internal fun mutePrivateCaptureAudio(): Boolean {
        requireMain()
        var success = true
        privateViews.keys.toList().forEach { view ->
            try { privateRuntime.mute(view) } catch (_: Exception) { success = false }
        }
        // Existing fallback hides/quarantines and retires only when not in a transition.
        if (!success) blockPrivateExposure()
        return success
    }
    /** Re-evaluates existing composition proof after capture state changes; grants no new authority. */
    internal fun refreshPrivateCaptureAudio() {
        requireMain()
        resumeSelectedPrivateAudio()
    }
    private fun resumeSelectedPrivateAudio() {
        if (transitioning || checkingPrivateAudio || retiringPrivate) return
        val session = privateSession ?: return
        val selected = entries.values.firstOrNull { isSelected(it) }
        for (view in privateViews.keys.toList()) {
            try {
                val candidate = !privacyBlocked && foreground && selected != null && selected.view === view &&
                    selected.session === session && entryLive(selected)
                // Selected visible browser playback is independent of the recording audio-track choice.
                // Unknown native ownership remains closed; hidden/stale Views never become eligible.
                checkingPrivateAudio = true
                val allowed = try {
                    val binding = privatePresentationBinding
                    candidate && binding != null && privateBrowsingAudioAllowed(
                        binding.captureAudioDisposition(view),
                        normalPresentationVisible = {
                            val claim = boundPrivatePresentation
                            claim != null && claim.webView === view && browserSurfaceAllowed() &&
                                isPrivateBrowsingPresentationCurrent(claim) && binding.isVisible(claim)
                        },
                        existingIntegrationAllows = { privateIntegration?.allowVisibleAudio(view) == true },
                    )
                } finally { checkingPrivateAudio = false }
                if (allowed && foreground && !privacyBlocked && selected != null && isSelected(selected) &&
                    entryLive(selected) && privateSession === session && privateLease?.isLive() == true)
                    privateRuntime.unmuteVisible(session, view)
                else privateRuntime.mute(view)
            } catch (_: Exception) { checkingPrivateAudio = false; blockPrivateExposure(); return }
        }
    }
    private fun resumeSelectedView(allowRehydrate: Boolean = false) {
        // Lifecycle/automatic retirement must never turn address-only cold restoration into IO.
        // Only explicit mode/tab selection (or closing a tab to select its neighbor) opts in.
        if (!canAct() || !foreground || !browserSurfaceAllowed()) return
        val owner = controller
        val tab = owner.state.selectedTab ?: return
        var entry = authenticationEntry(tab.context)?.takeIf { entryLive(it) }
        val address = tab.address
        if (entry == null && allowRehydrate) {
            if (!tab.incognito && tab.context.tabId in history) entry = ensureEntry(tab.context)
            else if (address != null) {
                // A mode/tab return is explicit navigation. Rehydrate only this exact partition;
                // private entries replay the RAM current address, never WebView saved state.
                val request = owner.beginNavigation(tab.context.tabId, address, operation())
                loadOwned(request, if (tab.incognito) privateNavigation[tab.context.tabId]?.index?.takeIf { it >= 0 } else null)
                entry = authenticationEntry(tab.context)?.takeIf { entryLive(it) }
            }
        }
        val selected = entry ?: return
        if (!entryLive(selected) || !isSelected(selected)) return
        try { selected.view.onResume(); resumeSelectedPrivateAudio() }
        catch (_: Exception) { if (selected.session != null) blockPrivateExposure() }
    }
    var state by mutableStateOf(controller.state); private set
    var settings by mutableStateOf(BrowserSettings()); private set
    internal var notice by mutableStateOf<BrowserNotice?>(null); private set
    var fullScreenView by mutableStateOf<View?>(null); private set
    var viewRevision by mutableStateOf(0); private set
    var platformAvailable by mutableStateOf(true); private set
    var bookmarkNavigationRevision by mutableStateOf(0L); private set
    private class Entry(
        val context: BrowserContextRef,
        val view: WebView,
        val owner: BrowserSessionController,
        val session: PrivateBrowsingSession?,
        val lease: PrivateSessionLease?,
        val historyRecorder: HistoryRecordingAdapter? = null,
        var request: BrowserRequest? = null,
        var suppressed: Boolean = false,
        var admission: NavigationAdmission? = null,
        var bookmarkAddress: String? = null,
        val inspectionDocument: BrowserInspectionDocument = BrowserInspectionDocument(),
        var jsDocument: Long = 0,
        var presenceBudget: BrowserVideoPresenceBudget = BrowserVideoPresenceBudget(),
        var presenceNavigationRevision: Long = 0,
        var jsDialogCount: Int = 0,
        var jsDialogsBlocked: Boolean = false,
        var historyPosition: Int? = null,
        var captureDocumentIdentity: Any = Any(),
    )
    /** URL-only history in RAM. Never a Bundle, checkpoint, diagnostic, or restored authority. */
    internal class PrivateNavigation {
        val addresses = mutableListOf<String>()
        var index = -1
        fun committed(address: String, position: Int?) {
            if (position != null && position in addresses.indices) {
                index = position; addresses[index] = address
            } else {
                while (addresses.size > index + 1) addresses.removeAt(addresses.lastIndex)
                addresses += address
                if (addresses.size > 128) addresses.removeAt(0)
                index = addresses.lastIndex
            }
        }
        fun target(delta: Int): Pair<Int, String>? = (index + delta).takeIf { it in addresses.indices }
            ?.let { it to addresses[it] }
        fun canBack() = index > 0
        fun canForward() = index >= 0 && index < addresses.lastIndex
    }
    private val privateNavigation = mutableMapOf<String, PrivateNavigation>()
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private val history = mutableMapOf<String, Bundle>()
    private val authenticationDocuments = AuthenticationOriginDocuments(android.os.SystemClock::elapsedRealtime)
    private enum class ReleaseState { RELEASING, RELEASED, QUARANTINED }
    private val released = WeakHashMap<WebView, ReleaseState>()
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var foreground = true
    private var videoPresenceUiVisible = false
    internal var videoPresence by mutableStateOf(BrowserVideoPresence.UNKNOWN); private set
    private val videoPresenceController = BrowserVideoPresenceController(
        android.os.SystemClock::elapsedRealtime,
        schedule = { delay, action ->
            val work = Runnable { action() }
            handler.postDelayed(work, delay)
            val cancel: () -> Unit = { handler.removeCallbacks(work) }; cancel
        },
        evaluate = { target, reply ->
            // Revalidate immediately before execution AND receipt; no source text is interpolated.
            syncVideoPresence()
            if (target == videoPresenceTarget()) {
                (target.owner as WebView).evaluateJavascript(BrowserVideoPresenceScript.SCRIPT) { raw ->
                    syncVideoPresence()
                    if (target == videoPresenceTarget()) reply(raw)
                }
            }
        },
        changed = { videoPresence = it },
    )
    internal fun setVideoPresenceUiVisible(visible: Boolean) {
        requireMain(); videoPresenceUiVisible = visible; syncVideoPresence()
    }
    private fun videoPresenceTarget(): BrowserVideoPresenceTarget? {
        if (!canAct() || !foreground || !videoPresenceUiVisible || !browserSurfaceAllowed() || !settings.javaScriptEnabled) return null
        val tab = state.selectedTab ?: return null
        val entry = entries[tab.context.tabId] ?: return null
        if (tab.loadState != BrowserLoadState.READY || entry.context != tab.context || entry.suppressed ||
            current(entry) == null || entry.bookmarkAddress == null || entry.bookmarkAddress != entry.view.url) return null
        return BrowserVideoPresenceTarget(entry.view, entry.presenceBudget, tab.epoch,
            interactionRevision, entry.presenceNavigationRevision)
    }
    private fun syncVideoPresence() { videoPresenceController.update(videoPresenceTarget()) }
    private var jsDialogUiVisible = false
    private var jsDialogUiBlocked = false
    internal var jsDialog by mutableStateOf<BrowserJsDialogRequest?>(null); private set
    private val jsDialogs = BrowserJsDialogController(android.os.SystemClock::elapsedRealtime) { jsDialog = it }
    internal fun setJsDialogUiVisible(visible: Boolean) {
        requireMain(); jsDialogUiVisible = visible
        if (!visible) jsDialogs.cancel()
    }
    internal fun setJsDialogUiBlocked(blocked: Boolean) {
        requireMain(); jsDialogUiBlocked = blocked
        if (blocked) jsDialogs.cancel()
    }
    internal fun finishJsDialog(request: BrowserJsDialogRequest, accepted: Boolean, input: String? = null, block: Boolean = false) {
        requireMain(); jsDialogs.finish(request, accepted, input, block)
    }
    private fun jsDialogAllowed(entry: Entry): Boolean = canAct() && isSelected(entry) && foreground && jsDialogUiVisible &&
        !jsDialogUiBlocked && settings.javaScriptEnabled && !entry.suppressed && !entry.jsDialogsBlocked &&
        entries[entry.context.tabId] === entry && state.selectedTab?.context == entry.context &&
        state.selectedTab?.loadState !in setOf(BrowserLoadState.CANCELLED, BrowserLoadState.FAILED) &&
        current(entry) != null && entry.admission?.hasStarted() == true &&
        fileChoice == null && !pickerInFlight && fullScreenView == null

    private fun showJsDialog(entry: Entry, view: WebView, url: String, message: String,
        kind: BrowserJsDialogKind, defaultValue: String = "", result: JsResult): Boolean {
        requireMain()
        if (view !== entry.view || !jsDialogAllowed(entry) || entry.session != null && !prepareEntryNavigation(entry)) {
            try { result.cancel() } catch (_: Exception) { }
            return true
        }
        entry.captureDocumentIdentity = Any()
        val document = entry.jsDocument
        val epoch = state.selectedTab?.epoch
        val origin = BrowserJsDialogPolicy.origin(url, view.url)
        val valid = { jsDialogAllowed(entry) && entry.view === view && entry.jsDocument == document &&
            state.selectedTab?.epoch == epoch && origin != null && BrowserJsDialogPolicy.origin(view.url, view.url) == origin }
        if (jsDialogs.offer(kind, origin, message, defaultValue,
                allowed = valid() && entry.jsDialogCount < BrowserJsDialogPolicy.MAX_PER_DOCUMENT,
                valid = valid, blockDocument = { entry.jsDialogsBlocked = true }, owner = view) { accepted, input ->
                // No page text, prompt input, URL or native exception details enter diagnostics.
                try {
                    if (!accepted) result.cancel()
                    else if (kind == BrowserJsDialogKind.PROMPT) (result as JsPromptResult).confirm(input.orEmpty())
                    else result.confirm()
                } catch (_: RuntimeException) { /* Retired native result. Never retry a completion. */ }
            }) entry.jsDialogCount++
        return true
    }
    var pickerInFlight: Boolean = false
        private set
    private var fullscreenOwner: Entry? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileChoice: FileChoice? = null
    internal val hasPendingFileChoice: Boolean get() = fileChoice != null
    private var interactionRevision by mutableLongStateOf(0L)
    private val discoveryOrigins = DiscoveryOriginTracker()
    private var discoveryOrigin: DiscoveryOriginToken? = null
    private var discoveryNavigation: DiscoveryNavigationFence? = null
    private var discoveryNavigationGeneration = 0L
    val hasUserInteracted: Boolean get() = interactionRevision > 0
    /** Fences user-selected detection across tab switches, including switch-away-and-back. */
    val saveInspectionRevision: Long get() = interactionRevision
    /** Only a host-accepted live document can authorize explicit media inspection. */
    internal fun saveInspectionPage(): SaveInspectionPage? {
        requireMain()
        val tab = state.selectedTab ?: return null
        val entry = entries[tab.context.tabId] ?: return null
        if (!canAct() || !foreground || !browserSurfaceAllowed() || !isSelected(entry) || entry.context != tab.context || entry.suppressed ||
            current(entry) == null || entry.admission?.hasStarted() != true) return null
        return SaveInspectionPage(tab.context, tab.epoch, tab.loadState == BrowserLoadState.READY,
            entry.inspectionDocument.address, entry.view.url, entry.view, saveInspectionRevision,
            entry.inspectionDocument.revision)
    }
    /** Availability inspection must not touch the access-order View LRU. */
    private fun authenticationEntry(context: BrowserContextRef): Entry? =
        entries.entries.firstOrNull { it.key == context.tabId }?.value?.takeIf { it.context == context }
    /** Process-local document identity, never reconstructed from a URL or persisted tab. */
    private fun authenticationDocument(context: BrowserContextRef): AuthenticationOriginDocument? {
        val tab = state.tabs.singleOrNull { it.context == context } ?: return null
        val entry = authenticationEntry(context) ?: return null
        val request = entry.request ?: return null
        val address = entry.inspectionDocument.address ?: return null
        if (!canAct() || !foreground || !platformAvailable || !entryLive(entry) || entry.owner !== controller || entry.context != context || entry.suppressed ||
            request.context != context || !entry.owner.isCurrent(request) ||
            entry.admission?.hasStarted() != true || address != entry.view.url) return null
        return AuthenticationOriginDocument(context, tab.epoch, entry.inspectionDocument.revision,
            entry, entry.view, address, tab.loadState == BrowserLoadState.READY, tab.incognito)
    }
    /**
     * Issues a fresh normal-browsing transition claim; never creates/loads a View or inspects page
     * text/cookies. Callers reserve/cover the root and bind this claim before attaching private Views.
     * READY-only PrivateCaptureTarget is deliberately not used for empty/loading/error/fullscreen.
     */
    internal fun capturePrivateBrowsingPresentation(): PrivateBrowsingPresentationClaim? {
        requireMain()
        return try {
            privateBrowsingPresentationIdentity(PrivateBrowsingPresentationKind.BROWSER)?.let { PrivateBrowsingPresentationClaim(it) }?.also {
                issuedPrivatePresentation = it
            }
        } catch (_: Exception) { null }
    }
    /** A private app route may show its protected Compose shell, never an attached browser View. */
    internal fun capturePrivateApplicationPresentation(): PrivateBrowsingPresentationClaim? {
        requireMain()
        return try {
            privateBrowsingPresentationIdentity(PrivateBrowsingPresentationKind.APPLICATION)?.let { PrivateBrowsingPresentationClaim(it) }?.also {
                issuedPrivatePresentation = it
            }
        } catch (_: Exception) { null }
    }
    private fun privateBrowsingPresentationIdentity(kind: PrivateBrowsingPresentationKind): PrivateBrowsingPresentationIdentity? {
        if (closed || closing || openingPrivate || transitioning || retiringPrivate || privacyBlocked ||
            !privateMode || !foreground || !platformAvailable) return null
        if ((kind == PrivateBrowsingPresentationKind.BROWSER) != browserSurfaceAllowed()) return null
        val session = privateSession ?: return null
        val lease = privateLease?.takeIf { it.isLive() && privateRuntime.owns(session) } ?: return null
        val owner = privateController
        val tab = owner.state.selectedTab ?: return null
        if (!tab.incognito || state.selectedTab?.context != tab.context || state.selectedTab?.epoch != tab.epoch) return null
        val entry = authenticationEntry(tab.context)
        val fullscreen = fullScreenView
        if (kind == PrivateBrowsingPresentationKind.APPLICATION && fullscreen != null) return null
        val renderedContent = privateBrowsingRenderedContent(tab.loadState, entry != null,
            tab.address != null, fullscreen != null) ?: return null
        if (entry != null && (!entryLive(entry) || !isSelected(entry) || entry.owner !== owner ||
            entry.session !== session || entry.lease !== lease)) return null
        if (fullscreen != null && (fullscreenOwner !== entry || fullscreenCallback == null)) return null
        val errorShell = renderedContent == PrivateBrowsingRenderedContent.ERROR_SHELL
        return PrivateBrowsingPresentationIdentity(session, lease, owner, entry ?: tab.context,
            tab.context, tab.epoch, interactionRevision, privatePresentationGeneration,
            entry?.captureDocumentIdentity, entry?.jsDocument, entry?.view, fullscreen, kind,
            renderedContent, tab.loadState.takeIf { errorShell }, tab.error?.code.takeIf { errorShell })
    }
    private fun privateBrowsingIdentityCurrent(claim: PrivateBrowsingPresentationClaim): Boolean =
        privateBrowsingPresentationIdentity(claim.kind)?.let(claim.identity::sameAs) == true

    /**
     * Short read-only main-thread predicate. Does not touch the access-order LRU, create/load pages,
     * inspect URLs/cookies, reenter commands, or require media inspection to have reached READY.
     * The selected WebView/fullscreen attachment is the real root owner's separate exact check.
     */
    internal fun isPrivateBrowsingPresentationCurrent(claim: PrivateBrowsingPresentationClaim): Boolean {
        requireMain()
        return try {
            privateBrowsingIdentityCurrent(claim) && privateViews.keys.toList().all { view ->
                view === claim.webView || (view.parent == null && privateRuntime.isMuted(view))
            }
        } catch (_: Exception) { false }
    }
    /**
     * Main owner calls under its cover BEFORE first attachment/focus. The runtime already fixed
     * this View's root lifetime at construction; only this exact presentation is admitted here.
     * A later reveal publishes a fresh root generation, so old retained connections remain dead.
     */
    internal fun bindPrivatePresentationInput(claim: PrivateBrowsingPresentationClaim): Boolean {
        requireMain()
        val binding = privatePresentationBinding ?: return false
        return try {
            if (!privateBrowsingIdentityCurrent(claim) ||
                (binding.inputLifetime.isAdmitted && boundPrivatePresentation !== claim)) return false
            val session = privateSession ?: return false
            privateViews.keys.toList().forEach { view ->
                if (view !== claim.webView) privateRuntime.revokeViewInput(view)
                privateRuntime.mute(view)
            }
            claim.webView?.let { privateRuntime.bindViewInput(session, it, claim.presentationIdentity) }
            if (!privateBrowsingIdentityCurrent(claim)) {
                claim.webView?.let(privateRuntime::revokeViewInput)
                false
            } else { boundPrivatePresentation = claim; true }
        } catch (_: Exception) { blockPrivateExposure(); false }
    }
    /**
     * Called after removing the old AndroidView parent and before the new attachment/focus. The
     * old detach may have revoked the binding installed during prepareComposition. Rebind only
     * the still-bound claim under the exact root cover; never issue or replace presentation proof.
     */
    internal fun beforePrivateViewAttachment(view: View): Boolean {
        requireMain()
        val binding = privatePresentationBinding ?: return false
        val claim = boundPrivatePresentation ?: return false
        val webView = claim.webView ?: return false
        fun attachmentCurrent(): Boolean = view.parent == null && privateViewAttachmentAllowed(
            claim, boundPrivatePresentation, view, binding.isCovered(), binding.inputLifetime.isAdmitted,
            privateBrowsingIdentityCurrent(claim),
        )
        return try {
            if (!attachmentCurrent()) return false
            val session = privateSession ?: return false
            privateRuntime.mute(webView)
            if (!attachmentCurrent()) return false
            // Runtime retains the lifetime fixed at View construction; this cannot retarget it.
            privateRuntime.bindViewInput(session, webView, claim.presentationIdentity)
            if (!attachmentCurrent()) {
                privateRuntime.revokeViewInput(webView)
                false
            } else true
        } catch (_: Exception) { blockPrivateExposure(); false }
    }
    /**
     * Read-only owner evidence, never merely !privateMode. Memory-only private sessions/network
     * sources may remain; every old presentation callback/input binding must already be retired.
     * Quarantined private Views remain in privateViews and cannot disappear from this readback.
     * Main adds exact ordinary composition, app-dialog absence and real capture-drain evidence.
     */
    internal fun readPrivateOrdinaryDeparture(): PrivateHostOrdinaryDepartureReadback {
        requireMain()
        return try {
            val ordinary = !closed && !closing && !openingPrivate && !transitioning && !retiringPrivate &&
                !privacyBlocked && !privateMode && controller === ordinaryController &&
                state.selectedTab?.context == ordinaryController.state.selectedTab?.context &&
                state.selectedTab?.epoch == ordinaryController.state.selectedTab?.epoch
            val views = privateViews.keys.toList()
            val detached = !privateReleaseFailed && fullScreenView == null && fullscreenOwner == null &&
                views.all { it.parent == null && privateRuntime.isMuted(it) } &&
                quarantinedViews.none { it in privateViews }
            val oldClaim = issuedPrivatePresentation
            val retired = boundPrivatePresentation == null &&
                privatePresentationBinding?.inputLifetime?.isAdmitted != true &&
                (oldClaim == null || oldClaim.identity.hostGeneration !== privatePresentationGeneration) &&
                views.none(privateRuntime::hasViewInputBinding) && jsDialog == null &&
                fileChoice == null && !pickerInFlight && fullscreenCallback == null
            PrivateHostOrdinaryDepartureReadback(ordinary, detached, retired)
        } catch (_: Exception) { PrivateHostOrdinaryDepartureReadback(false, false, false) }
    }

    /** Identity only. The recording owner separately binds fresh user consent, grant and surface proof. */
    internal fun capturePrivateCaptureTarget(): PrivateCaptureTarget? {
        requireMain()
        return try { capturePrivateCaptureTargetChecked() } catch (_: Exception) { null }
    }
    private fun capturePrivateCaptureTargetChecked(): PrivateCaptureTarget? {
        val page = saveInspectionPage()?.takeIf { it.isUsable() } ?: return null
        val entry = authenticationEntry(page.context) ?: return null
        val session = entry.session ?: return null
        if (!isSelected(entry) || !entryLive(entry) || !privateMode || !browserSurfaceAllowed() ||
            fullScreenView != null || pickerInFlight || fileChoice != null || jsDialog != null) return null
        return PrivateCaptureTarget(session, entry, entry.captureDocumentIdentity, entry.view)
    }
    internal fun isPrivateCaptureTargetCurrent(target: PrivateCaptureTarget): Boolean {
        requireMain()
        return try { isPrivateCaptureTargetCurrentChecked(target) } catch (_: Exception) { false }
    }
    private fun isPrivateCaptureTargetCurrentChecked(target: PrivateCaptureTarget): Boolean {
        val entry = target.tabIdentity as? Entry ?: return false
        // Route-independent authority: surfaceCurrent(evidence) separately proves the exact
        // isolated capture surface. This method alone never authorizes clearing window protection.
        if (closed || closing || openingPrivate || transitioning || retiringPrivate || privacyBlocked ||
            !privateMode || !foreground || !entryLive(entry) || !isSelected(entry) ||
            entry.session !== target.session || entry.view !== target.viewIdentity ||
            entry.captureDocumentIdentity !== target.documentIdentity || current(entry) == null || entry.suppressed ||
            fullScreenView != null || pickerInFlight || fileChoice != null || jsDialog != null) return false
        val tab = entry.owner.state.selectedTab ?: return false
        return tab.loadState == BrowserLoadState.READY && entry.admission?.hasStarted() == true &&
            entry.inspectionDocument.address != null && entry.inspectionDocument.address == entry.view.url
    }
    /** Recording③ binds the exact target directly; it does not require a detected media candidate. */

    private fun invalidateDocumentAuthority(entry: Entry) {
        if (entry.session != null && isSelected(entry)) revokePrivatePresentationInput()
        else if (entry.session != null) privateRuntime.revokeViewInput(entry.view)
        entry.captureDocumentIdentity = Any()
        entry.inspectionDocument.started(); authenticationDocuments.invalidate(entry)
    }
    internal fun captureSaveRequestContext(selection: PageSaveSelection, source: String): BrowserSaveRequestContext? {
        requireMain()
        return try { captureSaveRequestContextChecked(selection, source) } catch (_: Exception) { null }
    }
    private fun captureSaveRequestContextChecked(selection: PageSaveSelection, source: String): BrowserSaveRequestContext? {
        if (!selection.candidates.any { it.url.value == source && it.supported && !it.protected }) return null
        if (BrowserInputPolicy.address(source)?.value != source || Uri.parse(source).scheme != "https") return null
        val page = saveInspectionPage() ?: return null
        if (!selection.isCurrent() || !page.isUsable() || page.context != selection.context ||
            page.epoch != selection.epoch || page.pageUrl != selection.pageUrl) return null
        val entry = authenticationEntry(selection.context) ?: return null
        if (page.viewIdentity !== entry.view || page.documentRevision != entry.inspectionDocument.revision) return null
        val document = entry.inspectionDocument.revision
        val revision = page.revision
        val session = entry.session
        val lease = if (session != null) entry.lease?.takeIf { it.isLive() && it === privateLease && privateRuntime.owns(session) } ?: return null else null
        fun valid() = canAct() && foreground && browserSurfaceAllowed() && selection.isCurrent() && isSelected(entry) && current(entry) != null &&
            !entry.suppressed && entry.admission?.hasStarted() == true && entry.inspectionDocument.address == selection.pageUrl &&
            entries[selection.context.tabId] === entry &&
            state.selectedTab?.context == selection.context && state.selectedTab?.epoch == selection.epoch &&
            interactionRevision == revision && entry.inspectionDocument.revision == document && entry.view.url == selection.pageUrl &&
            (session == null || (privateSession === session && lease?.isLive() == true && privateRuntime.owns(session)))
        if (!valid()) return null
        return try {
            val sameOrigin = BrowserInputPolicy.origin(source) == BrowserInputPolicy.origin(selection.pageUrl)
            val cookie = if (!sameOrigin) null else if (session != null) privateRuntime.cookie(session, source)
                else CookieManager.getInstance().getCookie(source)
            val agent = entry.view.settings.userAgentString
            if (!valid()) null else {
                val resume = if (session != null && lease != null) retainedPrivateRequestAuthority(
                    entry, session, lease, source, selection.pageUrl, document, selection.epoch) else null
                BrowserSaveRequestContext(
                    if (lease == null) BrowserSavePartition.Ordinary else BrowserSavePartition.Private(lease), cookie, agent, resume)
            }
        } catch (_: Exception) { null }
    }
    /** Initial exact candidate checks have already succeeded. Never accepts a replacement source URL. */
    private fun retainedPrivateRequestAuthority(entry: Entry, session: PrivateBrowsingSession, lease: PrivateSessionLease,
        source: String, pageAddress: String, document: Long, epoch: Long): BrowserSaveResumeAuthority {
        lateinit var authority: BrowserSaveResumeAuthority
        authority = BrowserSaveResumeAuthority {
            requireMain()
            fun valid() = canAct() && foreground && privateMode && isSelected(entry) && entryLive(entry) &&
                entry.session === session && privateSession === session && entry.lease === lease && privateLease === lease &&
                lease.isLive() && privateRuntime.owns(session) && current(entry) != null && !entry.suppressed &&
                entry.owner.state.selectedTab?.epoch == epoch && entry.owner.state.selectedTab?.loadState == BrowserLoadState.READY &&
                entry.inspectionDocument.revision == document && entry.inspectionDocument.address == pageAddress && entry.view.url == pageAddress &&
                entry.admission?.hasStarted() == true && fileChoice == null && !pickerInFlight && jsDialog == null && fullScreenView == null
            if (!valid()) null else {
                val sameOrigin = BrowserInputPolicy.origin(source) == BrowserInputPolicy.origin(pageAddress)
                val cookie = if (sameOrigin) privateRuntime.cookie(session, source) else null
                val agent = entry.view.settings.userAgentString
                if (!valid()) null else BrowserSaveRequestContext(BrowserSavePartition.Private(lease), cookie, agent, authority)
            }
        }
        return authority
    }

    internal fun captureAuthenticationOrigin(selection: PageSaveSelection, source: String): AuthenticationOriginToken? {
        requireMain()
        val page = saveInspectionPage() ?: return null
        if (!selection.isCurrent() || !page.isUsable() || page.context != selection.context ||
            page.epoch != selection.epoch || page.pageUrl != selection.pageUrl) return null
        val document = authenticationDocument(selection.context) ?: return null
        return authenticationDocuments.capture(document, source)
    }
    internal fun canSelectAuthenticationOrigin(token: AuthenticationOriginToken): Boolean {
        requireMain()
        if (!canAct() || !foreground || !platformAvailable || !browserCommandAllowed() ||
            pickerInFlight || fileChoice != null || jsDialog != null || fullScreenView != null) return false
        val context = authenticationDocuments.context(token) ?: return false
        val document = authenticationDocument(context) ?: return false
        return authenticationDocuments.resolve(token, document)
    }
    /** Explicit selection of an already-live entry only. Never restore history or create a View. */
    internal fun selectAuthenticationOrigin(token: AuthenticationOriginToken, expectedBrowserRevision: Long): Boolean {
        requireMain()
        if (!authenticationOriginInteractionCurrent(expectedBrowserRevision, interactionRevision)) return false
        if (!canSelectAuthenticationOrigin(token)) return false
        val context = authenticationDocuments.context(token) ?: return false
        val entry = authenticationEntry(context) ?: return false
        if (state.selectedTab?.context == context) return true
        if (!touched()) return false
        val selectionRevision = interactionRevision
        if (!leaveCurrentTab()) return false
        // Recheck after native dismissal callbacks; they cannot redirect the original claim.
        if (!authenticationOriginInteractionCurrent(selectionRevision, interactionRevision) ||
            !canSelectAuthenticationOrigin(token) || authenticationEntry(context) !== entry) return false
        if (entry.owner !== controller || !entry.owner.selectTab(context.tabId)) return false
        entry.view.onResume()
        publish()
        return state.selectedTab?.context == context
    }
    private val timeout = object : Runnable {
        override fun run() {
            if (closed) return
            discoveryOrigins.pruneExpired()
            val now = System.currentTimeMillis()
            val expired = ordinaryController.expireOperations(now) + privateController.expireOperations(now)
            expired.forEach(::stop)
            if (expired.isNotEmpty()) publish()
            handler.postDelayed(this, 1_000)
        }
    }
    init { privateRuntime.recover(); newTab(userAction = false); handler.postDelayed(timeout, 1_000) }

    class FileChoice internal constructor(
        internal val context: BrowserContextRef,
        internal val epoch: Long,
        val origin: String,
        val acceptTypes: Array<String>,
        val multiple: Boolean,
        internal val callback: ValueCallback<Array<Uri>>,
    )

    fun restore(checkpoint: BrowserSessionCheckpoint?) {
        requireMain()
        if (checkpoint == null || hasUserInteracted || !canAct() || privateMode) return
        invalidateDiscoveryOrigin()
        ordinaryController.state.tabs.map { it.context.tabId }.forEach { id -> ordinaryController.closeTab(id)?.let(::destroy) }
        ordinaryController.restore(checkpoint) { UUID.randomUUID().toString() }
        if (ordinaryController.state.tabs.isEmpty()) newTab(userAction = false)
        publish(persist = false)
    }
    fun newTab(userAction: Boolean = true) {
        requireMain(); if (!canAct()) return
        val owner = controller
        if (owner.state.tabs.size >= 12) { notice = BrowserNotice.Text("最多保留 12 个标签，请先关闭一个。"); return }
        if (userAction && !touched()) return
        if (!leaveCurrentTab() || owner !== controller || !canAct()) return
        owner.openTab(UUID.randomUUID().toString(), UUID.randomUUID().toString(), incognito = privateMode)
        publish()
    }
    fun selectTab(id: String) {
        requireMain(); if (!canAct() || id == state.selectedTabId) return
        val owner = controller
        if (owner.state.tabs.none { it.context.tabId == id } || !touched() || !leaveCurrentTab() || owner !== controller) return
        if (owner.selectTab(id)) {
            owner.state.selectedTab?.let { tab -> if (!tab.incognito && id in history) ensureEntry(tab.context) }
            publish(); resumeSelectedView(allowRehydrate = true)
        }
    }
    fun closeTab(id: String) {
        requireMain(); if (!canAct()) return
        val owner = controller
        if (owner.state.tabs.none { it.context.tabId == id } || !touched() || !dismissTransientUi() || owner !== controller) return
        owner.closeTab(id)?.let(::destroy)
        if (!canAct()) return
        if (owner.state.tabs.isEmpty()) { if (privateMode) exitPrivateSession() else newTab(userAction = false) }
        publish(); resumeSelectedView(allowRehydrate = true)
    }
    fun navigate(input: String) {
        requireMain(); if (!canAct()) return
        val address = BrowserInputPolicy.address(input, allowBareHost = true)
        if (address == null) { notice = BrowserNotice.Text("请输入有效的 http 或 https 网页地址。暂不提供搜索 Provider。"); return }
        val owner = controller
        val tab = owner.state.selectedTab ?: return
        if (!touched() || owner !== controller || owner.state.selectedTab?.context != tab.context) return
        load(owner.beginNavigation(tab.context.tabId, address, operation()))
    }
    private var openingOrdinaryAddress = false

    /** Explicit Discovery address admission; never changes mode or creates card/Seen authority. */
    internal fun openOrdinaryAddress(input: String, expectedContext: BrowserContextRef,
        expectedEpoch: Long, expectedInteractionRevision: Long, sourceCurrent: () -> Boolean): Boolean {
        requireMain()
        if (openingOrdinaryAddress) return false
        openingOrdinaryAddress = true
        try {
            fun sourceAllowed(): Boolean = sourceCurrent() && canAct() && foreground && browserCommandAllowed() &&
                !privateMode && controller === ordinaryController &&
                ordinaryController.state.selectedTab?.let { !it.incognito && it.context == expectedContext } == true
            if (!sourceAllowed() || ordinaryController.state.selectedTab?.epoch != expectedEpoch ||
                interactionRevision != expectedInteractionRevision) return false
            if (input.length > 8192 || input.any { it.code < 32 || it.code == 127 }) return false
            val address = BrowserInputPolicy.address(input, allowBareHost = true) ?: return false
            if (!touched()) return false
            val acceptedRevision = interactionRevision
            if (!sourceAllowed() || interactionRevision != acceptedRevision ||
                ordinaryController.state.selectedTab?.epoch != expectedEpoch) return false
            val request = ordinaryController.beginNavigation(expectedContext.tabId, address, operation())
            return loadOwned(request, continuation = {
                sourceAllowed() && interactionRevision == acceptedRevision && ordinaryController.isCurrent(request)
            })
        } catch (_: Exception) { return false }
        finally { openingOrdinaryAddress = false }
    }

    /** A history row opens only in the selected ordinary session, without changing its mode. */
    fun openOrdinaryHistory(address: String): Boolean {
        requireMain()
        if (!canAct() || privateMode || !foreground || !browserCommandAllowed() || !browserSurfaceAllowed() || state.selectedTab?.incognito != false ||
            BrowserInputPolicy.address(address)?.value != address) return false
        navigate(address)
        val tab = state.selectedTab ?: return false
        val entry = entries[tab.context.tabId] ?: return false
        return !closed && !tab.incognito && tab.loadState == BrowserLoadState.LOADING &&
            tab.address?.value == address && entry.context == tab.context && !entry.suppressed && current(entry) != null
    }
    /** Snapshot identity is revalidated synchronously on the explicit add click. No storage or load. */
    fun bookmarkPage(): BookmarkPageToken? {
        requireMain()
        val tab = state.selectedTab ?: return null
        val entry = entries[tab.context.tabId] ?: return null
        if (!canAct() || entry.session != null || !isSelected(entry) || current(entry) == null) return null
        return BookmarkPolicy.capture(tab, entry.view.url, entry.bookmarkAddress, entry.view.title,
            interactionRevision, bookmarkNavigationRevision, !closed && foreground)
    }
    fun acceptBookmarkPage(expected: BookmarkPageToken?): BookmarkPageToken? =
        BookmarkPolicy.accept(expected, bookmarkPage())
    /** Origin authority is process-local and never restored with tab addresses or history. */
    fun openDiscoveryResult(target: DiscoveryControlTarget, page: PageSnapshot, result: VideoSearchResult): Boolean {
        requireMain()
        if (!canAct() || privateMode || !foreground || !browserCommandAllowed() || state.selectedTab?.incognito == true ||
            !com.easysavevideo.app.discovery.discoveryTargetMatchesPage(target, page, result)) return false
        navigate(result.canonicalPageUrl)
        val tab = state.selectedTab ?: return false
        val entry = entries[tab.context.tabId] ?: return false
        if (closed || tab.incognito || tab.loadState != BrowserLoadState.LOADING ||
            tab.address?.value != result.canonicalPageUrl || current(entry) == null) return false
        discoveryOrigin = discoveryOrigins.open(target, page, result, discoveryBrowserState(tab))
        if (discoveryOrigin != null) discoveryNavigation = DiscoveryNavigationFence(result.canonicalPageUrl)
        return true
    }
    fun captureSaveOrigin(): DiscoverySaveOriginHandle? {
        requireMain()
        val tab = state.selectedTab ?: return null
        val entry = entries[tab.context.tabId] ?: return null
        val token = discoveryOrigin ?: return null
        if (!canAct() || entry.session != null || !isSelected(entry) || tab.incognito || tab.loadState != BrowserLoadState.READY ||
            entry.view.url != tab.address?.value || discoveryNavigation?.accepts(entry.view.url) != true) return null
        val proof = discoveryOrigins.selectForSave(token, discoveryBrowserState(tab)) ?: return null
        return DiscoverySaveOriginHandle(discoveryOrigins, proof, onDiscoverySaveCompleted)
    }
    private fun discoveryBrowserState(tab: BrowserTab) = DiscoveryOriginBrowserState(
        tab.context, tab.epoch, discoveryNavigationGeneration, tab.incognito, !closed)
    private fun invalidateDiscoveryOrigin() {
        discoveryNavigationGeneration++
        discoveryOrigins.invalidateAll(); discoveryOrigin = null; discoveryNavigation = null
    }
    override fun load(request: BrowserRequest) { loadOwned(request) }
    private fun loadOwned(request: BrowserRequest, historyPosition: Int? = null,
        continuation: (() -> Boolean)? = null): Boolean {
        requireMain()
        val owner = ownerOf(request.context) ?: return false
        var capturedEntry: Entry? = null
        var accepted = false
        fun continuing(): Boolean = continuation?.invoke() != false && canAct() && owner.isCurrent(request)
        fun bound(entry: Entry): Boolean = continuing() && entryLive(entry) && entry.owner === owner &&
            current(entry) === request && (continuation == null ||
                entry.session == null && !entry.suppressed && isSelected(entry))
        try {
            if (!continuing()) return false
            val privateRequest = owner === privateController
            val displayed = owner === controller && owner.state.selectedTab?.context == request.context
            if (privateRequest && displayed && !preparePrivateNavigation()) return false
            if (displayed && !dismissTransientUi() || !owner.isCurrent(request)) return false
            if (continuation != null && !continuing()) return false
            if (displayed) invalidateDiscoveryOrigin()
            if (!browserCommandAllowed()) { fail(request, ErrorCode.POLICY_BLOCKED, owner = owner); return false }
            val entry = recreateEntry(request.context) ?: run { fail(request, ErrorCode.WEBVIEW_UNAVAILABLE, owner = owner); return false }
            capturedEntry = entry
            if (!entryLive(entry) || entry.owner !== owner || !owner.isCurrent(request)) return false
            if (continuation != null && !continuing()) return false
            val address = request.address ?: return false
            entry.request = request; entry.suppressed = false; entry.admission = NavigationAdmission(address.value)
            entry.historyPosition = historyPosition
            try {
                entry.view.stopLoading()
                if (current(entry) !== request) return false
                if (continuation != null && !bound(entry)) return false
                entry.historyRecorder?.controlledNavigationRequested()
                if (continuation != null && !bound(entry)) return false
                entry.view.loadUrl(address.value)
            } catch (_: Exception) {
                if (entry.session != null) blockPrivateExposure()
                else fail(request, ErrorCode.WEBVIEW_UNAVAILABLE, owner = owner)
                if (continuation != null) return false
            }
            if (continuation != null && !bound(entry)) return false
            publish()
            // READY and an admitted redirect retain the exact request; neither is a failure.
            accepted = if (continuation == null) true else bound(entry) &&
                owner.state.selectedTab?.loadState in setOf(BrowserLoadState.LOADING, BrowserLoadState.READY)
            return accepted
        } catch (failure: Exception) {
            if (continuation == null) throw failure
            return false
        } finally {
            if (continuation != null && !accepted) {
                val stopped = settleOrdinaryAddressRequest(owner, request) {
                    // Never refetch by tabId: stop only the captured view still bound to this request.
                    val entry = capturedEntry
                    if (entry != null && entryLive(entry) && entry.owner === owner &&
                        entries[request.context.tabId] === entry && entry.context == request.context &&
                        entry.request === request && owner.isCurrent(request)) entry.view.stopLoading()
                }
                if (stopped) publish()
            }
        }
    }
    fun back(): Boolean {
        requireMain(); if (!canAct()) return false
        if (fullScreenView != null) {
            if (!touched()) return false
            hideFullscreen(); return true
        }
        return moveHistory(-1)
    }
    fun forward() { requireMain(); moveHistory(1) }
    private fun moveHistory(delta: Int): Boolean {
        if (!canAct()) return false
        val owner = controller
        val tab = owner.state.selectedTab ?: return false
        val old = entries[tab.context.tabId] ?: return false
        if (!entryLive(old) || !isSelected(old)) return false
        if (old.session != null) {
            val target = privateNavigation[tab.context.tabId]?.target(delta) ?: return false
            val address = BrowserInputPolicy.address(target.second) ?: return false
            if (!touched() || owner !== controller || !entryLive(old)) return false
            val request = owner.beginNavigation(tab.context.tabId, address, operation())
            loadOwned(request, target.first)
            return entries[tab.context.tabId]?.let { current(it) === request } == true
        }
        if (delta < 0 && !old.view.canGoBack() || delta > 0 && !old.view.canGoForward()) return false
        val list = old.view.copyBackForwardList()
        val target = list.getItemAtIndex(list.currentIndex + delta)?.url ?: return false
        if (!touched() || !dismissTransientUi() || owner !== controller || !entryLive(old)) return false
        val request = owner.beginHistoryNavigation(tab.context.tabId, operation())
        val entry = recreateEntry(tab.context) ?: return false
        if (!entryLive(entry) || !owner.isCurrent(request)) return false
        entry.request = request; entry.admission = NavigationAdmission(target)
        entry.historyRecorder?.controlledNavigationRequested()
        if (delta < 0) entry.view.goBack() else entry.view.goForward()
        publish(); return true
    }
    fun reload() {
        requireMain(); if (!canAct()) return
        val owner = controller
        val tab = owner.state.selectedTab ?: return
        if (!touched() || owner !== controller || owner.state.selectedTab?.context != tab.context) return
        val request = owner.reload(tab.context.tabId, operation()) ?: return
        loadOwned(request, if (tab.incognito) privateNavigation[tab.context.tabId]?.index?.takeIf { it >= 0 } else null)
    }
    fun stopSelected() {
        requireMain(); if (!canAct() || !touched()) return
        controller.state.selectedTabId?.let { controller.cancelNavigation(it)?.let(::stop) }
        cancelFiles(); publish()
    }
    override fun stop(context: BrowserContextRef) {
        requireMain(); discoveryOrigins.invalidate(context)
        authenticationEntry(context)?.let { entry ->
            entry.historyRecorder?.cancel()
            entry.request = null; entry.suppressed = true; entry.admission?.cancel()
            invalidateDocumentAuthority(entry)
            try { jsDialogs.cancel(entry.view); entry.view.stopLoading() }
            catch (_: Exception) { if (entry.session != null) blockPrivateExposure() }
        }
    }
    fun selectedView(): WebView? {
        requireMain()
        if (!canAct()) return null
        val tab = state.selectedTab ?: return null
        return entries[tab.context.tabId]?.takeIf { it.context == tab.context && isSelected(it) && entryLive(it) }?.view
    }
    fun applySettings(value: BrowserSettings) {
        requireMain(); if (!canAct()) return
        settings = value
        if (!value.javaScriptEnabled) jsDialogs.cancel()
        for (entry in entries.values.toList()) {
            if (!entryLive(entry)) continue
            try { applyPreferences(entry) }
            catch (_: Exception) {
                if (entry.session != null) { blockPrivateExposure(); return }
                platformAvailable = false
            }
        }
        syncVideoPresence(); trimViews()
    }
    private val cookieClearFeedback = BrowserCookieClearFeedback()
    /** Boolean compatibility: true only for confirmed removal; false also covers failure/rejection. */
    fun clearCookies(feedbackCurrent: () -> Boolean = { true }, onComplete: (Boolean) -> Unit) {
        requireMain()
        if (!canAct() || privateMode) {
            if (privateMode) notice = BrowserNotice.Text("无痕数据由独立会话管理；退出无痕会话后统一清理。")
            onComplete(false); return
        }
        val request = cookieClearFeedback.begin()
        val interaction = interactionRevision
        val presentation = privatePresentationGeneration
        fun currentPresentation(): Boolean {
            if (!canAct() || privateMode || !foreground || !jsDialogUiVisible) return false
            val current = feedbackCurrent() && browserSurfaceAllowed()
            return current && canAct() && !privateMode && foreground && jsDialogUiVisible &&
                interactionRevision == interaction && privatePresentationGeneration === presentation
        }
        if (notice is BrowserNotice.CookieClear) notice = null
        requestBrowserCookieClear(
            isAllowed = { cookieClearFeedback.isCurrent(request) && currentPresentation() && cookieClearFeedback.isCurrent(request) },
            prepare = ::cancelFiles,
            acquire = {
                val manager = CookieManager.getInstance()
                object : BrowserCookieClearPort {
                    override fun removeAll(onRemoved: (Boolean) -> Unit) = manager.removeAllCookies { onRemoved(it) }
                    override fun flush() = manager.flush()
                }
            },
        ) { result ->
            // Evaluate presentation, then recheck and consume this request immediately before publication.
            if (cookieClearFeedback.consume(request, ::currentPresentation)) notice = BrowserNotice.CookieClear(result)
            onComplete(result.removed)
        }
    }
    fun clearCache() { clearCacheWithResult() }
    internal fun clearCacheWithResult(): BrowserCacheClearResult {
        requireMain()
        // Never route a private UI command to the default profile's cache or create a default View.
        if (!canAct() || privateSession != null || privateMode) {
            if (privateSession != null || privateMode) notice = BrowserNotice.Text("请先退出无痕会话，再清理普通网页缓存。")
            return BrowserCacheClearResult.FAILED
        }
        val result = requestBrowserCacheClear {
            val existing = entries.values.firstOrNull { it.session == null && entryLive(it) }?.view
            if (existing != null) existing.clearCache(true) else {
                val temporary = WebView(context)
                try { temporary.clearCache(true) } finally { releaseView(temporary) }
            }
        }
        notice = BrowserNotice.Text(when (result) {
            BrowserCacheClearResult.REQUESTED -> "普通网页缓存清理已请求。Cookie 需单独清除。"
            BrowserCacheClearResult.FAILED -> "系统 WebView 未能清除缓存，请稍后重试。"
        })
        return result
    }
    fun persistCurrentSession() { requireMain(); if (!closed) onCheckpoint(ordinaryController.persistableSession()) }
    fun dismissNotice() { notice = null }
    fun showNotice(text: String) { notice = BrowserNotice.Text(text) }
    fun resume() {
        requireMain(); if (closed || closing || privacyBlocked) return
        if (privateMode && (!integrationReady() || !protectPrivateTransition(PrivateBrowserLifecycleTransition.RESUME))) { blockPrivateExposure(); return }
        foreground = true
        ordinaryHistory?.load()
        try { entries.values.firstOrNull { entryLive(it) }?.view?.resumeTimers() }
        catch (_: Exception) { if (privateMode) { blockPrivateExposure(); return } }
        resumeSelectedView(); syncVideoPresence()
    }
    fun pause() {
        requireMain(); if (closed || closing) return
        if (privateSession != null) revokePrivatePresentationInput()
        foreground = false; cancelPrivateOpening(); authenticationDocuments.clear()
        var failed = privateSession != null && !protectPrivateTransition(PrivateBrowserLifecycleTransition.PAUSE)
        // Protection is restored before dismissing or replacing any native presentation.
        privateViews.keys.toList().forEach { view ->
            try { privateRuntime.mute(view) } catch (_: Exception) { failed = true }
        }
        try { jsDialogs.cancel() } catch (_: Exception) { if (privateSession != null) failed = true }
        videoPresenceController.update(null)
        if (!pickerInFlight) try { cancelFiles() } catch (_: Exception) { if (privateSession != null) failed = true }
        try { hideFullscreen() } catch (_: Exception) { if (privateSession != null) failed = true }
        entries.values.toList().forEach { entry ->
            entry.historyRecorder?.cancel()
            try { entry.view.onPause() } catch (_: Exception) { if (entry.session != null) failed = true }
        }
        try { entries.values.firstOrNull()?.view?.pauseTimers() } catch (_: Exception) { if (privateSession != null) failed = true }
        try { CookieManager.getInstance().flush() } catch (_: Exception) { }
        // The ordinary jar alone is flushed; private persistence is never flushed here.
        if (failed) blockPrivateExposure()
    }
    fun hideFullscreen() {
        requireMain()
        val view = fullScreenView
        val owner = fullscreenOwner
        val callback = fullscreenCallback
        if (view != null && owner?.session != null) {
            // Provider-initiated hide must protect before detaching/replacing the inline surface.
            if (!transitioning && !retiringPrivate && !protectPrivateTransition()) blockPrivateExposure(retire = false)
            else revokePrivatePresentationInput()
        }
        fullScreenView = null; fullscreenOwner = null; fullscreenCallback = null
        owner?.captureDocumentIdentity = Any()
        var failed = false
        try { (view?.parent as? ViewGroup)?.removeView(view) } catch (_: Exception) { failed = true }
        try { callback?.onCustomViewHidden() } catch (_: Exception) { failed = true }
        if (failed && owner?.session != null) blockPrivateExposure()
    }
    fun finishFiles(choice: FileChoice, uris: List<Uri>?) {
        requireMain()
        if (fileChoice !== choice) return
        pickerInFlight = false; fileChoice = null
        val tab = state.selectedTab
        val entry = entries[choice.context.tabId]
        val valid = canAct() && foreground && tab?.context == choice.context && tab.epoch == choice.epoch &&
            entry != null && isSelected(entry) && current(entry) != null && BrowserInputPolicy.origin(entry.view.url) == choice.origin
        val accepted = uris?.take(if (choice.multiple) 8 else 1)?.filter { it.scheme == "content" }
        choice.callback.onReceiveValue(if (valid && !accepted.isNullOrEmpty()) accepted.toTypedArray() else null)
    }
    fun beginFilePicker(choice: FileChoice): Boolean {
        requireMain()
        if (fileChoice !== choice || !canAct() || !foreground) return false
        val entry = authenticationEntry(choice.context) ?: return false
        if (!isSelected(entry) || current(entry) == null || entry.owner.state.selectedTab?.epoch != choice.epoch) return false
        pickerInFlight = true; return true
    }
    fun cancelFiles() {
        pickerInFlight = false
        val pending = fileChoice; fileChoice = null; pending?.callback?.onReceiveValue(null)
    }
    private fun dismissTransientUi(): Boolean {
        if (!canAct()) return false
        transitioning = true
        var success = true
        try {
            try { jsDialogs.cancel() } catch (_: Exception) { success = false }
            try { cancelFiles() } catch (_: Exception) { success = false }
            try { hideFullscreen() } catch (_: Exception) { success = false }
        } finally { transitioning = false }
        if (!success && privateMode) blockPrivateExposure()
        return success && canAct()
    }
    private fun preparePrivateNavigation(): Boolean {
        if (!canAct()) return false
        val session = privateSession
        if (session == null || privateLease?.isLive() != true || !privateRuntime.owns(session) ||
            !integrationReady() || !protectPrivateTransition()) { blockPrivateExposure(); return false }
        return canAct() && privateSession === session && privateLease?.isLive() == true
    }
    private fun prepareEntryNavigation(entry: Entry): Boolean {
        if (!entryLive(entry)) return false
        if (entry.session == null) return true
        if (isSelected(entry)) return preparePrivateNavigation() && entryLive(entry)
        // Departure already restored protection and revoked the old target. A hidden page cannot
        // stop an unrelated ordinary capture or dismiss the current tab's UI. Keep it muted.
        return try { privateRuntime.revokeViewInput(entry.view); privateRuntime.mute(entry.view); entryLive(entry) }
        catch (_: Exception) { blockPrivateExposure(); false }
    }
    private fun leaveCurrentTab(): Boolean {
        if (!canAct() || privateMode && !preparePrivateNavigation()) return false
        val selected = entries.values.firstOrNull { isSelected(it) }
        if (selected?.session != null) {
            selected.captureDocumentIdentity = Any()
            try { privateRuntime.mute(selected.view) }
            catch (_: Exception) { blockPrivateExposure(); return false }
        }
        if (!dismissTransientUi()) return false
        if (selected != null && entryLive(selected)) {
            selected.historyRecorder?.cancel()
            try { selected.view.onPause() }
            catch (_: Exception) { if (selected.session != null) blockPrivateExposure(); return false }
        }
        return canAct()
    }
    private fun interactionChanged() {
        if (privateSession != null) revokePrivatePresentationInput()
        entries.values.firstOrNull { isSelected(it) && it.session != null }?.captureDocumentIdentity = Any()
        interactionRevision++; invalidateDiscoveryOrigin()
    }
    private fun touched(): Boolean {
        if (!canAct() || privateMode && !preparePrivateNavigation()) return false
        interactionChanged(); return true
    }
    private fun operation() = OperationContext(UUID.randomUUID().toString(), System.currentTimeMillis() + 60_000, CancellationSource())
    private fun publish(persist: Boolean = true) {
        state = if (privacyBlocked || closed) BrowserSessionState() else controller.state
        resumeSelectedPrivateAudio(); syncVideoPresence()
        if (persist && !closed) onCheckpoint(ordinaryController.persistableSession())
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyPreferences(entry: Entry) {
        val view = entry.view
        check(entryLive(entry))
        view.settings.apply {
            javaScriptEnabled = settings.javaScriptEnabled
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            builtInZoomControls = true; displayZoomControls = false
        }
        val session = entry.session
        if (session != null) {
            check(entry.lease?.isLive() == true && entry.lease === privateLease && privateSession === session)
            privateRuntime.configureCookies(session, view, settings.acceptThirdPartyCookies)
        }
        else {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, settings.acceptThirdPartyCookies)
        }
    }
    private fun saveHistory(entry: Entry) {
        if (entry.session != null) return
        val bundle = Bundle()
        runCatching { entry.view.saveState(bundle) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            if (parcel.dataSize() <= 128 * 1024) history[entry.context.tabId] = bundle
            else notice = BrowserNotice.Text("标签历史过大，已仅保留当前地址。")
        } finally { parcel.recycle() }
    }
    private fun recreateEntry(ref: BrowserContextRef): Entry? {
        val old = authenticationEntry(ref)
        if (old != null) {
            entries.remove(ref.tabId); old.historyRecorder?.cancel(); authenticationDocuments.invalidate(old)
            saveHistory(old)
            if (!releaseView(old.view) && old.session != null) { blockPrivateExposure(); return null }
        }
        return ensureEntry(ref)
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureEntry(ref: BrowserContextRef): Entry? {
        if (!canAct()) return null
        entries[ref.tabId]?.let { return it.takeIf { value -> value.context == ref && entryLive(value) } }
        val owner = ownerOf(ref) ?: return null
        val session = if (owner === privateController) privateSession ?: return null else null
        val lease = if (session != null) privateLease?.takeIf { it.isLive() && privateRuntime.owns(session) } ?: return null else null
        var created: WebView? = null
        return try {
            WebView.setWebContentsDebuggingEnabled(false)
            val view = if (session != null) privateRuntime.createView(context, session,
                checkNotNull(privatePresentationBinding).inputLifetime).also { privateViews[it] = session }
                else WebView(context)
            created = view
            if (session != null) {
                // Apply after profile binding but before publishing/configuring/loading the View.
                // Private fields must not enter OS autofill/content-capture or parent saved state.
                view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                view.isSaveEnabled = false
                view.isSaveFromParentEnabled = false
                check(view.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS &&
                    view.importantForContentCapture == View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS &&
                    !view.isSaveEnabled && !view.isSaveFromParentEnabled)
            }
            val entry = Entry(ref, view, owner, session, lease, if (session != null) null else ordinaryHistory?.recorder(ref, view))
            entries[ref.tabId] = entry
            applyPreferences(entry)
            if (session == null) history.remove(ref.tabId)?.let { view.restoreState(it) }
            view.webViewClient = client(entry)
            view.webChromeClient = chrome(entry)
            view.setDownloadListener { _, _, _, _, _ ->
                if (entryLive(entry) && isSelected(entry)) notice = BrowserNotice.Text("网页下载未自动开始。请使用保存按钮检测并选择兼容 MP4。")
            }
            if (foreground) view.resumeTimers()
            if (!foreground || !isSelected(entry)) view.onPause()
            if (session != null) privateRuntime.mute(view)
            trimViews(except = ref.tabId)
            if (!entryLive(entry)) null else { viewRevision++; platformAvailable = true; entry }
        } catch (_: Exception) {
            entries[ref.tabId]?.takeIf { it.view === created }?.let { entries.remove(ref.tabId) }
            created?.let { releaseView(it) }
            if (session != null) blockPrivateExposure()
            else {
                platformAvailable = false
                notice = BrowserNotice.Text("系统 WebView 不可用。请检查系统 WebView，或用系统浏览器打开地址。")
                errors.report(ErrorEnvelope(ErrorCode.WEBVIEW_UNAVAILABLE, ErrorModule.BROWSER, true, UserAction.OPEN_SYSTEM_BROWSER))
            }
            null
        }
    }
    private fun trimViews(except: String? = state.selectedTabId) {
        while (entries.size > settings.maxActiveWebViews) {
            // Protect the displayed View even if a hidden callback created the newest Entry.
            // Under a one-View budget, drop that hidden newcomer rather than blank the display.
            val visible = controller.state.selectedTabId
            val id = entries.keys.firstOrNull { it != except && it != visible }
                ?: entries.keys.firstOrNull { it != visible } ?: break
            val entry = entries.remove(id) ?: break
            entry.historyRecorder?.cancel(); authenticationDocuments.invalidate(entry); saveHistory(entry)
            entry.owner.cancelNavigation(id)
            if (!releaseView(entry.view) && entry.session != null) { blockPrivateExposure(); break }
        }
        viewRevision++; publish(persist = false)
    }
    override fun destroy(context: BrowserContextRef) {
        requireMain()
        if (!destroyEntry(context)) blockPrivateExposure()
    }
    private fun destroyEntry(context: BrowserContextRef): Boolean {
        discoveryOrigins.invalidate(context); history.remove(context.tabId); privateNavigation.remove(context.tabId)
        val entry = authenticationEntry(context) ?: return true
        entries.remove(context.tabId)
        entry.historyRecorder?.cancel(); authenticationDocuments.invalidate(entry)
        entry.request = null; entry.suppressed = true; entry.admission?.cancel(); invalidateDocumentAuthority(entry)
        val released = releaseView(entry.view)
        viewRevision++
        return released || entry.session == null
    }
    private fun releaseView(view: WebView, rendererDead: Boolean = false): Boolean {
        released[view]?.let { return it == ReleaseState.RELEASED }
        released[view] = ReleaseState.RELEASING
        val session = privateViews[view]
        val privateView = session != null
        var success = true
        fun attempt(action: () -> Unit) { try { action() } catch (_: Exception) { success = false } }
        // Revoke synchronously before any provider, detach or release callback.
        if (privateView) attempt { privateRuntime.revokeViewInput(view) }
        // Continue through every cleanup step even if stop/detach/mute throws. Never retry destroy.
        if (privateView && !rendererDead) attempt { privateRuntime.mute(view) }
        attempt { jsDialogs.cancel(view) }
        attempt { view.visibility = View.INVISIBLE }
        attempt { (view.parent as? ViewGroup)?.removeView(view) }
        if (!rendererDead) {
            attempt { view.stopLoading() }; attempt { view.onPause() }
            attempt { view.webChromeClient = null }; attempt { view.webViewClient = WebViewClient() }
            attempt { view.setDownloadListener(null) }; attempt { view.removeAllViews() }
        }
        attempt { view.destroy() }
        if (success && privateView) attempt { privateRuntime.viewReleased(view) }
        if (!success && session != null) {
            // Process-lifetime ownership survives this Host/Activity. A late callback cannot
            // recycle a quarantined registry slot or turn uncertain release into completion.
            try { privateRuntime.quarantineView(session, view) } catch (_: Exception) { privateReleaseFailed = true }
        }
        released[view] = if (success) ReleaseState.RELEASED else ReleaseState.QUARANTINED
        if (success) { privateViews.remove(view); quarantinedViews.remove(view) }
        else {
            quarantinedViews += view
            if (privateView) privateReleaseFailed = true
        }
        return success
    }
    private fun ownerOf(ref: BrowserContextRef): BrowserSessionController? = when {
        ordinaryController.state.tabs.any { it.context == ref } -> ordinaryController
        privateController.state.tabs.any { it.context == ref } -> privateController
        else -> null
    }
    private fun entryLive(entry: Entry): Boolean = !closed && !closing && !privacyBlocked && !retiringPrivate && !transitioning &&
        entries.entries.any { it.key == entry.context.tabId && it.value === entry } && released[entry.view] == null &&
        entry.owner.state.tabs.any { it.context == entry.context } &&
        (if (entry.session == null) entry.owner === ordinaryController && entry.lease == null
        else entry.owner === privateController && entry.session === privateSession && entry.lease === privateLease &&
            entry.lease?.isLive() == true && privateRuntime.owns(entry.session))
    private fun isSelected(entry: Entry): Boolean = !privacyBlocked && entry.owner === controller &&
        controller.state.selectedTab?.context == entry.context
    private fun current(entry: Entry): BrowserRequest? = entry.request?.takeIf {
        entryLive(entry) && it.context == entry.context && entry.owner.isCurrent(it)
    }
    private fun verifiedMainAddress(entry: Entry, url: String?): PageAddress? {
        if (!entryLive(entry)) return null
        val address = BrowserInputPolicy.address(url ?: return null) ?: return null
        return address.takeIf { entry.view.url == url }
    }
    private fun fail(request: BrowserRequest, code: ErrorCode, status: Int? = null,
        owner: BrowserSessionController? = ownerOf(request.context)) {
        val exactOwner = owner ?: return
        val failingEntry = authenticationEntry(request.context)
        if (exactOwner.isCurrent(request) && failingEntry?.session != null &&
            !prepareEntryNavigation(failingEntry)) return
        val error = ErrorEnvelope(code, ErrorModule.BROWSER, code != ErrorCode.TLS_REJECTED,
            UserAction.RETRY, SafeContext(httpStatus = status))
        if (exactOwner.acceptPageFailed(request, error, System.currentTimeMillis())) {
            authenticationEntry(request.context)?.takeIf { it.owner === exactOwner }?.let { entry ->
                entry.historyRecorder?.cancel(); invalidateDocumentAuthority(entry)
                jsDialogs.cancel(entry.view)
            }
            discoveryOrigins.invalidate(request.context)
            errors.report(error); publish()
        }
    }
    private fun client(entry: Entry) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (view !== entry.view || !entryLive(entry)) return true
            val address = BrowserInputPolicy.address(request.url.toString())
            if (address == null) {
                if (isSelected(entry)) notice = BrowserNotice.Text("已阻止网页发起的外部或不安全地址跳转。")
                return true
            }
            if (!request.isForMainFrame) return false
            if (entry.session != null && !prepareEntryNavigation(entry)) return true
            if (!entryLive(entry)) return true
            entry.captureDocumentIdentity = Any(); authenticationDocuments.invalidate(entry)
            if (isSelected(entry) && !dismissTransientUi()) return true
            else jsDialogs.cancel(view)
            if (!entryLive(entry)) return true
            if (request.isRedirect) discoveryOrigins.invalidate(entry.context)
            if (!request.hasGesture() && current(entry) != null && entry.admission?.expects(address.value) == true) return false
            if (request.isRedirect && current(entry) != null) {
                entry.admission?.allowRedirect(address.value); return false
            }
            val owner = entry.owner
            val next = owner.beginNavigation(entry.context.tabId, address, operation())
            invalidateDocumentAuthority(entry); discoveryOrigins.invalidate(entry.context)
            // Capture both Entry and owner. A queued callback can neither target a different
            // controller nor revive a retired generation after mode changes / same-URL ABA.
            handler.post { if (entryLive(entry) && owner.isCurrent(next)) load(next) }
            publish(); return true
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== entry.view || !entryLive(entry) || entry.suppressed) return
            val address = BrowserInputPolicy.address(url ?: return) ?: return
            if (entry.session != null && !prepareEntryNavigation(entry)) return
            if (!entryLive(entry)) return
            invalidateDocumentAuthority(entry)
            entry.jsDocument++; entry.presenceBudget = BrowserVideoPresenceBudget()
            if (isSelected(entry)) videoPresenceController.update(null)
            entry.jsDialogCount = 0; entry.jsDialogsBlocked = false
            jsDialogs.cancel(view)
            if (!entryLive(entry)) return
            if (isSelected(entry) && discoveryNavigation?.onStarted(address.value) == false) invalidateDiscoveryOrigin()
            var request = current(entry)
            val owner = entry.owner
            val tab = owner.state.tabs.firstOrNull { it.context == entry.context } ?: return
            // POST/script/history navigation may not call shouldOverrideUrlLoading.
            if (request == null || tab.loadState == BrowserLoadState.READY) {
                request = owner.beginNavigation(entry.context.tabId, address, operation()); entry.request = request
                entry.historyPosition = null
                discoveryOrigins.invalidate(entry.context); entry.admission = NavigationAdmission(address.value)
            }
            if (entry.admission?.onStarted(address.value) != true) return
            val acceptedStart = owner.acceptPageStarted(request, address, System.currentTimeMillis())
            entry.historyRecorder?.started(request,
                owner.state.tabs.firstOrNull { it.context == entry.context }, owner.state.selectedTab?.context,
                view, address.value, entry.admission, currentEntry = current(entry) === request && !entry.suppressed,
                acceptedStart = acceptedStart, active = entryLive(entry) && isSelected(entry) && foreground && browserSurfaceAllowed())
            if (isSelected(entry)) cancelFiles()
            publish()
        }
        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== entry.view || entry.suppressed) return
            val request = current(entry) ?: return
            val address = verifiedMainAddress(entry, url) ?: return
            if (entry.admission?.accepts(address.value) != true) return
            val owner = entry.owner
            val tab = owner.state.tabs.firstOrNull { it.context == entry.context } ?: return
            if (tab.loadState != BrowserLoadState.LOADING) return
            val memory = if (entry.session != null) privateNavigation.getOrPut(entry.context.tabId) { PrivateNavigation() } else null
            val position = entry.historyPosition?.takeIf { memory != null && it in memory.addresses.indices }
            val canBack = memory?.let { if (position != null) position > 0 else it.index >= 0 } ?: view.canGoBack()
            val canForward = memory?.let { position != null && position < it.addresses.lastIndex } ?: view.canGoForward()
            val nowMillis = System.currentTimeMillis()
            val acceptedFinish = owner.acceptPageFinished(request, address, canBack, canForward, nowMillis)
            if (acceptedFinish) {
                memory?.committed(address.value, position)
                entry.historyRecorder?.finished(request,
                    owner.state.tabs.firstOrNull { it.context == entry.context }, owner.state.selectedTab?.context,
                    view, view.url, address.value, entry.admission, view.title,
                    currentEntry = current(entry) === request && !entry.suppressed, acceptedFinish = true,
                    active = entryLive(entry) && isSelected(entry) && foreground && browserSurfaceAllowed(), nowMillis = nowMillis)
                entry.inspectionDocument.finished(address.value, view.url)
                entry.bookmarkAddress = address.value; entry.presenceNavigationRevision++
                if (isSelected(entry)) { bookmarkNavigationRevision++; discoveryNavigation?.onFinished() }
                publish()
            }
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (view !== entry.view || !entryLive(entry)) return
            if (entry.session != null && !prepareEntryNavigation(entry)) return
            if (!entryLive(entry)) return
            entry.captureDocumentIdentity = Any(); authenticationDocuments.invalidate(entry)
            jsDialogs.cancel(view); entry.presenceNavigationRevision++
            if (isSelected(entry)) bookmarkNavigationRevision++
            val owner = entry.owner
            val request = current(entry)
            val session = entry.session
            val lease = entry.lease
            val document = entry.inspectionDocument
            val memory = privateNavigation[entry.context.tabId]
            val tab = owner.state.tabs.firstOrNull { it.context == entry.context }
            entry.inspectionDocument.history(url, view.url, isReload,
                current(entry) != null && !entry.suppressed && entry.admission?.hasStarted() == true,
                tab?.loadState == BrowserLoadState.READY)
            val observedAddress = entry.inspectionDocument.address
            val observedRevision = entry.inspectionDocument.revision
            entry.bookmarkAddress = url?.takeIf { actual ->
                !isReload && current(entry) != null && tab?.loadState == BrowserLoadState.READY && view.url == actual &&
                    BrowserInputPolicy.origin(actual) != null && BrowserInputPolicy.origin(actual) == BrowserInputPolicy.origin(tab.address?.value)
            }
            if (isSelected(entry) && (isReload || discoveryNavigation?.onVisitedHistory(url ?: "") == false)) invalidateDiscoveryOrigin()
            syncVideoPresence()
            if (session != null && lease != null && request != null && memory != null && owner === privateController) {
                if (BrowserSameDocumentUpdate.acceptPrivate(owner, request, document,
                    observedAddress, observedRevision, memory, viewUrl = { view.url }, stillCurrent = {
                        view === entry.view && entries[entry.context.tabId] === entry && entry.owner === owner &&
                            owner === privateController && entry.session === session && session === privateSession &&
                            entry.lease === lease && lease === privateLease && lease.isLive() && privateRuntime.owns(session) &&
                            privateViews[view] === session && current(entry) === request && !entry.suppressed &&
                            entry.admission?.hasStarted() == true && entry.inspectionDocument === document &&
                            document.revision == observedRevision && document.address == observedAddress &&
                            privateNavigation[entry.context.tabId] === memory
                    })) publish(persist = false)
            } else if (entry.session == null && owner === ordinaryController && request != null &&
                BrowserSameDocumentUpdate.accept(owner, request, entry.inspectionDocument,
                    observedAddress, observedRevision,
                    canGoBack = { view.canGoBack() }, canGoForward = { view.canGoForward() },
                    viewUrl = { view.url }, stillCurrent = {
                        view === entry.view && entry.owner === owner && owner === ordinaryController &&
                            current(entry) === request && !entry.suppressed &&
                            entry.admission?.hasStarted() == true
                    })) publish()
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== entry.view || !request.isForMainFrame || verifiedMainAddress(entry, request.url.toString()) == null ||
                entry.admission?.accepts(request.url.toString()) != true) return
            if (entry.session != null && !prepareEntryNavigation(entry)) return
            current(entry)?.let { fail(it, when (error.errorCode) {
                ERROR_TIMEOUT -> ErrorCode.NETWORK_TIMEOUT
                ERROR_HOST_LOOKUP, ERROR_CONNECT -> ErrorCode.NETWORK_UNAVAILABLE
                else -> ErrorCode.PAGE_LOAD_FAILED
            }, owner = entry.owner) }
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view !== entry.view || !request.isForMainFrame || verifiedMainAddress(entry, request.url.toString()) == null ||
                entry.admission?.accepts(request.url.toString()) != true) return
            if (entry.session != null && !prepareEntryNavigation(entry)) return
            current(entry)?.let { fail(it, ErrorCode.HTTP_FAILURE, response.statusCode.takeIf { n -> n in 100..599 }, owner = entry.owner) }
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (view !== entry.view || !entryLive(entry)) return
            if (entry.session != null && !prepareEntryNavigation(entry)) return
            current(entry)?.takeIf { entry.admission?.expects(error.url) == true }?.let { fail(it, ErrorCode.TLS_REJECTED, owner = entry.owner) }
        }
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            handler.cancel()
            if (view === entry.view && entryLive(entry) && isSelected(entry))
                notice = BrowserNotice.Text("此版本不处理 HTTP 身份验证弹窗；普通网站登录表单可在网页内使用。")
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== entry.view) return true
            val belongs = authenticationEntry(entry.context) === entry
            val privateSessionAffected = entry.session != null && entry.session === privateSession
            // Protect before any release/replacement, including callbacks from a no-longer-visible View.
            if (privateSessionAffected && !protectPrivateTransition()) blockPrivateExposure(retire = false)
            if (belongs) {
                invalidateDocumentAuthority(entry)
                if (isSelected(entry)) { try { cancelFiles(); hideFullscreen() } catch (_: Exception) { } }
                entry.historyRecorder?.cancel(); entries.remove(entry.context.tabId)
                entry.owner.rendererGone(setOf(entry.context.tabId))
                history.remove(entry.context.tabId); discoveryOrigins.invalidate(entry.context)
            }
            val released = releaseView(view, rendererDead = true)
            if (!released && entry.session != null) blockPrivateExposure(retire = false)
            if (privateSessionAffected) exitPrivateSession()
            if (belongs && !closed) {
                errors.report(ErrorEnvelope(ErrorCode.RENDERER_GONE, ErrorModule.BROWSER, true, UserAction.RETRY))
                if (!privacyBlocked) notice = BrowserNotice.Text(if (entry.session != null) "无痕渲染进程已停止，该无痕会话正在清理。" else "网页渲染进程已停止，受影响页面已释放。点击重新加载可安全重建。")
                viewRevision++; publish()
            }
            return true
        }
    }
    private fun chrome(entry: Entry) = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            current(entry)?.takeIf { view === entry.view && entry.admission?.hasStarted() == true }?.let { if (entry.owner.acceptProgress(it, newProgress, System.currentTimeMillis())) publish(persist = false) }
        }
        override fun onReceivedTitle(view: WebView, title: String?) {
            current(entry)?.takeIf { view === entry.view && entry.admission?.accepts(view.url ?: "") == true }?.let { if (entry.owner.acceptTitle(it, view.title, System.currentTimeMillis())) publish(persist = false) }
        }
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            if (foreground && browserSurfaceAllowed() && isSelected(entry) && current(entry) != null)
                notice = BrowserNotice.PermissionDenied(BrowserPermissionKind.CAMERA_OR_MICROPHONE,
                    if (entry.session == null) BrowserInputPolicy.origin(request.origin.toString()) else null)
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
            if (foreground && browserSurfaceAllowed() && isSelected(entry) && current(entry) != null)
                notice = BrowserNotice.PermissionDenied(BrowserPermissionKind.LOCATION,
                    if (entry.session == null) BrowserInputPolicy.origin(origin) else null)
        }
        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val tab = state.selectedTab
            val origin = BrowserInputPolicy.origin(webView.url)
            if (!canAct() || webView !== entry.view || !entryLive(entry) || !isSelected(entry) || !foreground || tab?.context != entry.context || tab.loadState in setOf(BrowserLoadState.CANCELLED, BrowserLoadState.FAILED) || origin == null || entries[entry.context.tabId] !== entry) {
                callback.onReceiveValue(null); return true
            }
            if (entry.session != null && !prepareEntryNavigation(entry) || !dismissTransientUi() || !entryLive(entry)) {
                callback.onReceiveValue(null); return true
            }
            entry.captureDocumentIdentity = Any()
            val choice = FileChoice(entry.context, tab.epoch, origin,
                BrowserInputPolicy.safeAcceptTypes(params.acceptTypes), params.mode == FileChooserParams.MODE_OPEN_MULTIPLE, callback)
            fileChoice = choice; onChooseFiles(choice); return true
        }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!canAct() || !entryLive(entry) || !isSelected(entry) || !foreground || state.selectedTab?.context != entry.context ||
                state.selectedTab?.loadState in setOf(BrowserLoadState.CANCELLED, BrowserLoadState.FAILED) || fullScreenView != null) {
                callback.onCustomViewHidden(); return
            }
            if (entry.session != null && !prepareEntryNavigation(entry) || !dismissTransientUi() || !entryLive(entry)) {
                callback.onCustomViewHidden(); return
            }
            entry.captureDocumentIdentity = Any()
            fullScreenView = view; fullscreenCallback = callback; fullscreenOwner = entry
        }
        override fun onHideCustomView() { if (fullscreenOwner === entry) hideFullscreen() }
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
            showJsDialog(entry, view, url, message, BrowserJsDialogKind.ALERT, result = result)
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
            showJsDialog(entry, view, url, message, BrowserJsDialogKind.CONFIRM, result = result)
        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean =
            showJsDialog(entry, view, url, message, BrowserJsDialogKind.PROMPT, defaultValue, result)
        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
            result.cancel(); return true
        }
    }
    override fun close() {
        requireMain(); if (closed || closing) return
        closing = true // Fence immediately, even if Activity disposal reenters an integration hook.
        revokePrivatePresentationInput()
        if (transitioning || checkingPrivateAudio || retiringPrivate) handler.post { completeClose() }
        else completeClose()
    }
    private fun completeClose() {
        if (closed) return
        if (transitioning || checkingPrivateAudio || retiringPrivate) { handler.post { completeClose() }; return }
        cancelPrivateOpening(); exitPrivateSession()
        closed = true; authenticationDocuments.clear()
        try { jsDialogs.cancel() } catch (_: Exception) { }
        videoPresenceController.close(); handler.removeCallbacks(timeout)
        try { cancelFiles() } catch (_: Exception) { }
        try { hideFullscreen() } catch (_: Exception) { }
        discoveryOrigins.close(); discoveryOrigin = null; discoveryNavigation = null
        val views = entries.values.toList(); entries.clear()
        views.forEach { entry -> entry.historyRecorder?.cancel(); releaseView(entry.view) }
        history.clear(); privateNavigation.clear(); state = BrowserSessionState()
    }
}

/** Guarded-path cleanup only. Native callbacks may finish or replace the request synchronously. */
internal fun settleOrdinaryAddressRequest(owner: BrowserSessionController, request: BrowserRequest,
    stopCurrentEntry: () -> Unit): Boolean {
    fun ownLoading() = owner.isCurrent(request) && owner.state.tabs.any {
        it.context == request.context && it.loadState == BrowserLoadState.LOADING
    }
    if (!ownLoading()) return false
    try { stopCurrentEntry() } catch (_: Exception) { /* Still settle only this logical request. */ }
    if (!ownLoading()) return false
    return owner.cancelNavigation(request.context.tabId) != null
}
