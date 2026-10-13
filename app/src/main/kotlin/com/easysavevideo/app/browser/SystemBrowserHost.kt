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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.core.domain.BrowserSessionController
import com.easysavevideo.core.model.*
import com.easysavevideo.app.discovery.*
import com.easysavevideo.app.save.SaveInspectionPage
import com.easysavevideo.app.browser.bookmarks.BookmarkPageToken
import com.easysavevideo.app.browser.bookmarks.BookmarkPolicy
import com.easysavevideo.discovery.PageSnapshot
import com.easysavevideo.discovery.VideoSearchResult
import java.util.UUID

/** Main-thread Android adapter. WebView and Android objects never cross into Core. */
class SystemBrowserHost(
    private val context: Context,
    private val errors: ErrorSink,
    private val onCheckpoint: (BrowserSessionCheckpoint) -> Unit,
    private val browserCommandAllowed: () -> Boolean,
    private val browserSurfaceAllowed: () -> Boolean,
    private val onChooseFiles: (FileChoice) -> Unit,
    private val onDiscoverySaveCompleted: DiscoverySaveOriginCompletion,
) : BrowserAdapter, AutoCloseable {
    private val controller = BrowserSessionController(maxTabs = 12)
    var state by mutableStateOf(controller.state); private set
    var settings by mutableStateOf(BrowserSettings()); private set
    var notice by mutableStateOf<String?>(null); private set
    var fullScreenView by mutableStateOf<View?>(null); private set
    var viewRevision by mutableStateOf(0); private set
    var platformAvailable by mutableStateOf(true); private set
    var bookmarkNavigationRevision by mutableStateOf(0L); private set
    private data class Entry(val context: BrowserContextRef, val view: WebView, var request: BrowserRequest? = null, var suppressed: Boolean = false, var admission: NavigationAdmission? = null, var bookmarkAddress: String? = null, val inspectionDocument: BrowserInspectionDocument = BrowserInspectionDocument(), var jsDocument: Long = 0, var presenceBudget: BrowserVideoPresenceBudget = BrowserVideoPresenceBudget(), var presenceNavigationRevision: Long = 0, var jsDialogCount: Int = 0, var jsDialogsBlocked: Boolean = false)
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private val history = mutableMapOf<String, Bundle>()
    private val released = WeakHashMap<WebView, Boolean>()
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
        if (closed || !foreground || !videoPresenceUiVisible || !browserSurfaceAllowed() || !settings.javaScriptEnabled) return null
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
    private fun jsDialogAllowed(entry: Entry): Boolean = !closed && foreground && jsDialogUiVisible &&
        !jsDialogUiBlocked && settings.javaScriptEnabled && !entry.suppressed && !entry.jsDialogsBlocked &&
        entries[entry.context.tabId] === entry && state.selectedTab?.context == entry.context &&
        state.selectedTab?.loadState !in setOf(BrowserLoadState.CANCELLED, BrowserLoadState.FAILED) &&
        current(entry) != null && entry.admission?.hasStarted() == true &&
        fileChoice == null && !pickerInFlight && fullScreenView == null

    private fun showJsDialog(entry: Entry, view: WebView, url: String, message: String,
        kind: BrowserJsDialogKind, defaultValue: String = "", result: JsResult): Boolean {
        requireMain()
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
    private var interactionRevision = 0L
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
        if (closed || !foreground || entry.context != tab.context || entry.suppressed ||
            current(entry) == null || entry.admission?.hasStarted() != true) return null
        return SaveInspectionPage(tab.context, tab.epoch, tab.loadState == BrowserLoadState.READY,
            entry.inspectionDocument.address, entry.view.url, entry.view, saveInspectionRevision,
            entry.inspectionDocument.revision)
    }
    private val timeout = object : Runnable {
        override fun run() {
            if (closed) return
            discoveryOrigins.pruneExpired()
            val expired = controller.expireOperations(System.currentTimeMillis())
            expired.forEach(::stop)
            if (expired.isNotEmpty()) publish()
            handler.postDelayed(this, 1_000)
        }
    }
    init { newTab(userAction = false); handler.postDelayed(timeout, 1_000) }

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
        if (checkpoint == null || hasUserInteracted || closed) return
        invalidateDiscoveryOrigin()
        controller.state.tabs.map { it.context.tabId }.forEach { id -> controller.closeTab(id)?.let(::destroy) }
        controller.restore(checkpoint) { UUID.randomUUID().toString() }
        if (controller.state.tabs.isEmpty()) newTab(userAction = false)
        publish(persist = false)
    }
    fun newTab(userAction: Boolean = true) {
        requireMain(); if (closed) return
        if (userAction) touched()
        if (controller.state.tabs.size >= 12) { notice = "最多保留 12 个标签，请先关闭一个。"; return }
        leaveCurrentTab()
        controller.openTab(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        publish()
    }
    fun selectTab(id: String) {
        requireMain(); if (closed || id == state.selectedTabId) return
        touched(); leaveCurrentTab()
        if (controller.selectTab(id)) {
            controller.state.selectedTab?.let { tab ->
                if (id in history) ensureEntry(tab.context)
                entries[id]?.view?.onResume()
            }
            publish()
        }
    }
    fun closeTab(id: String) {
        requireMain(); if (closed) return
        touched(); jsDialogs.cancel(); cancelFiles(); hideFullscreen()
        controller.closeTab(id)?.let(::destroy)
        if (controller.state.tabs.isEmpty()) newTab(userAction = false)
        publish()
    }
    fun navigate(input: String) {
        requireMain(); if (closed) return
        touched()
        val address = BrowserInputPolicy.address(input, allowBareHost = true)
        if (address == null) { notice = "请输入有效的 http 或 https 网页地址。暂不提供搜索 Provider。"; return }
        val tab = controller.state.selectedTab ?: return
        val request = controller.beginNavigation(tab.context.tabId, address, operation())
        load(request)
    }
    /** Snapshot identity is revalidated synchronously on the explicit add click. No storage or load. */
    fun bookmarkPage(): BookmarkPageToken? {
        requireMain()
        val tab = state.selectedTab ?: return null
        val entry = entries[tab.context.tabId] ?: return null
        if (current(entry) == null) return null
        return BookmarkPolicy.capture(tab, entry.view.url, entry.bookmarkAddress, entry.view.title,
            interactionRevision, bookmarkNavigationRevision, !closed && foreground)
    }
    fun acceptBookmarkPage(expected: BookmarkPageToken?): BookmarkPageToken? =
        BookmarkPolicy.accept(expected, bookmarkPage())
    /** Origin authority is process-local and never restored with tab addresses or history. */
    fun openDiscoveryResult(target: DiscoveryControlTarget?, page: PageSnapshot, result: VideoSearchResult): Boolean {
        requireMain()
        navigate(result.canonicalPageUrl)
        val tab = state.selectedTab ?: return false
        val entry = entries[tab.context.tabId] ?: return false
        if (closed || tab.incognito || tab.loadState != BrowserLoadState.LOADING ||
            tab.address?.value != result.canonicalPageUrl || current(entry) == null) return false
        if (target != null) {
            discoveryOrigin = discoveryOrigins.open(target, page, result, discoveryBrowserState(tab))
            if (discoveryOrigin != null) discoveryNavigation = DiscoveryNavigationFence(result.canonicalPageUrl)
        }
        return true
    }
    fun captureSaveOrigin(): DiscoverySaveOriginHandle? {
        requireMain()
        val tab = state.selectedTab ?: return null
        val entry = entries[tab.context.tabId] ?: return null
        val token = discoveryOrigin ?: return null
        if (closed || tab.incognito || tab.loadState != BrowserLoadState.READY ||
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
    override fun load(request: BrowserRequest) {
        requireMain()
        entries[request.context.tabId]?.takeIf { it.context == request.context }?.let { jsDialogs.cancel(it.view) }
        invalidateDiscoveryOrigin(); cancelFiles(); hideFullscreen()
        if (!browserCommandAllowed()) { fail(request, ErrorCode.POLICY_BLOCKED); return }
        val entry = recreateEntry(request.context) ?: run { fail(request, ErrorCode.WEBVIEW_UNAVAILABLE); return }
        entry.view.stopLoading()
        entry.request = request; entry.suppressed = false; entry.admission = NavigationAdmission(request.address?.value)
        val address = request.address ?: return
        entry.view.loadUrl(address.value)
        publish()
    }
    fun back(): Boolean {
        requireMain(); if (fullScreenView != null) { hideFullscreen(); return true }
        val tab = controller.state.selectedTab ?: return false
        val old = entries[tab.context.tabId] ?: return false
        if (!old.view.canGoBack()) return false
        touched(); jsDialogs.cancel(); cancelFiles()
        val list = old.view.copyBackForwardList()
        val target = list.getItemAtIndex(list.currentIndex - 1)?.url ?: return false
        val request = controller.beginHistoryNavigation(tab.context.tabId, operation())
        val entry = recreateEntry(tab.context) ?: return false
        entry.request = request; entry.admission = NavigationAdmission(target)
        entry.view.goBack(); publish(); return true
    }
    fun forward() {
        requireMain(); val tab = controller.state.selectedTab ?: return
        val old = entries[tab.context.tabId] ?: return
        if (!old.view.canGoForward()) return
        touched(); jsDialogs.cancel(); cancelFiles()
        val list = old.view.copyBackForwardList()
        val target = list.getItemAtIndex(list.currentIndex + 1)?.url ?: return
        val request = controller.beginHistoryNavigation(tab.context.tabId, operation())
        val entry = recreateEntry(tab.context) ?: return
        entry.request = request; entry.admission = NavigationAdmission(target)
        entry.view.goForward(); publish()
    }
    fun reload() {
        requireMain(); val tab = controller.state.selectedTab ?: return
        val request = controller.reload(tab.context.tabId, operation()) ?: return
        touched(); load(request)
    }
    fun stopSelected() {
        requireMain(); touched()
        controller.state.selectedTabId?.let { controller.cancelNavigation(it)?.let(::stop) }
        cancelFiles(); publish()
    }
    override fun stop(context: BrowserContextRef) {
        requireMain(); discoveryOrigins.invalidate(context); entries[context.tabId]?.takeIf { it.context == context }?.let {
            jsDialogs.cancel(it.view); it.request = null; it.suppressed = true; it.admission?.cancel(); it.view.stopLoading()
        }
    }
    fun selectedView(): WebView? {
        requireMain(); val tab = state.selectedTab ?: return null
        return entries[tab.context.tabId]?.view
    }
    fun applySettings(value: BrowserSettings) {
        requireMain(); settings = value
        if (!value.javaScriptEnabled) jsDialogs.cancel()
        entries.values.forEach { applyPreferences(it.view) }
        syncVideoPresence()
        trimViews()
    }
    fun clearCookies(onComplete: (Boolean) -> Unit) {
        requireMain(); cancelFiles()
        CookieManager.getInstance().removeAllCookies { changed ->
            CookieManager.getInstance().flush()
            notice = "所有普通标签共享的 Cookie 已清除，登录状态可能失效。"
            onComplete(changed)
        }
    }
    fun clearCache() {
        requireMain()
        try {
            val existing = entries.values.firstOrNull()?.view
            if (existing != null) existing.clearCache(true) else {
                // An empty, detached platform View clears the shared disk cache without loading a page.
                val temporary = WebView(context)
                try { temporary.clearCache(true) } finally { releaseView(temporary) }
            }
            notice = "网页缓存清理已请求。Cookie 需单独清除。"
        } catch (_: RuntimeException) { notice = "系统 WebView 未能清除缓存，请稍后重试。" }
    }
    fun persistCurrentSession() { requireMain(); onCheckpoint(controller.persistableSession()) }
    fun dismissNotice() { notice = null }
    fun showNotice(text: String) { notice = text }
    fun resume() {
        requireMain(); foreground = true
        entries.values.firstOrNull()?.view?.resumeTimers()
        state.selectedTabId?.let { entries[it]?.view?.onResume() }
        syncVideoPresence()
    }
    fun pause() {
        requireMain(); foreground = false; jsDialogs.cancel(); videoPresenceController.update(null); if (!pickerInFlight) cancelFiles(); hideFullscreen()
        entries.values.forEach { it.view.onPause() }
        entries.values.firstOrNull()?.view?.pauseTimers()
        CookieManager.getInstance().flush()
    }
    fun hideFullscreen() {
        requireMain()
        (fullScreenView?.parent as? ViewGroup)?.removeView(fullScreenView)
        fullScreenView = null; fullscreenOwner = null
        val callback = fullscreenCallback; fullscreenCallback = null; callback?.onCustomViewHidden()
    }
    fun finishFiles(choice: FileChoice, uris: List<Uri>?) {
        requireMain()
        if (fileChoice !== choice) return
        pickerInFlight = false; fileChoice = null
        val tab = state.selectedTab
        val entry = entries[choice.context.tabId]
        val valid = tab?.context == choice.context && tab.epoch == choice.epoch &&
            BrowserInputPolicy.origin(entry?.view?.url) == choice.origin && !closed
        val accepted = uris?.take(if (choice.multiple) 8 else 1)?.filter { it.scheme == "content" }
        choice.callback.onReceiveValue(if (valid && !accepted.isNullOrEmpty()) accepted.toTypedArray() else null)
    }
    fun beginFilePicker(choice: FileChoice): Boolean {
        requireMain()
        if (fileChoice !== choice || closed || !foreground) return false
        pickerInFlight = true; return true
    }
    fun cancelFiles() {
        pickerInFlight = false
        val pending = fileChoice; fileChoice = null; pending?.callback?.onReceiveValue(null)
    }
    private fun leaveCurrentTab() {
        jsDialogs.cancel(); cancelFiles(); hideFullscreen(); state.selectedTabId?.let { entries[it]?.view?.onPause() }
    }
    private fun touched() { interactionRevision++; invalidateDiscoveryOrigin() }
    private fun operation() = OperationContext(UUID.randomUUID().toString(), System.currentTimeMillis() + 60_000, CancellationSource())
    private fun publish(persist: Boolean = true) {
        state = controller.state
        syncVideoPresence()
        if (persist) onCheckpoint(controller.persistableSession())
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyPreferences(view: WebView) {
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
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, settings.acceptThirdPartyCookies)
    }
    private fun saveHistory(entry: Entry) {
        val bundle = Bundle()
        runCatching { entry.view.saveState(bundle) }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            if (parcel.dataSize() <= 128 * 1024) history[entry.context.tabId] = bundle
            else notice = "标签历史过大，已仅保留当前地址。"
        } finally { parcel.recycle() }
    }
    private fun recreateEntry(ref: BrowserContextRef): Entry? {
        entries.remove(ref.tabId)?.let { old -> saveHistory(old); releaseView(old.view) }
        return ensureEntry(ref)
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureEntry(ref: BrowserContextRef): Entry? {
        entries[ref.tabId]?.let { return it }
        return try {
            WebView.setWebContentsDebuggingEnabled(false)
            val view = WebView(context)
            val entry = Entry(ref, view)
            entries[ref.tabId] = entry
            applyPreferences(view)
            history.remove(ref.tabId)?.let { view.restoreState(it) }
            view.webViewClient = client(entry)
            view.webChromeClient = chrome(entry)
            view.setDownloadListener { _, _, _, _, _ -> notice = "网页下载未自动开始。请使用保存按钮检测并选择兼容 MP4。" }
            if (foreground) view.resumeTimers()
            if (!foreground || ref.tabId != state.selectedTabId) view.onPause()
            trimViews(except = ref.tabId); viewRevision++; platformAvailable = true
            entry
        } catch (_: RuntimeException) {
            entries.remove(ref.tabId)?.let { releaseView(it.view) }
            platformAvailable = false; notice = "系统 WebView 不可用。请检查系统 WebView，或用系统浏览器打开地址。"
            errors.report(ErrorEnvelope(ErrorCode.WEBVIEW_UNAVAILABLE, ErrorModule.BROWSER, true, UserAction.OPEN_SYSTEM_BROWSER))
            null
        }
    }
    private fun trimViews(except: String? = state.selectedTabId) {
        while (entries.size > settings.maxActiveWebViews) {
            val id = entries.keys.firstOrNull { it != except } ?: break
            val entry = entries.remove(id) ?: break
            saveHistory(entry)
            controller.cancelNavigation(id)
            releaseView(entry.view)
        }
        viewRevision++; publish(persist = false)
    }
    override fun destroy(context: BrowserContextRef) {
        requireMain(); discoveryOrigins.invalidate(context); history.remove(context.tabId)
        entries.remove(context.tabId)?.let { releaseView(it.view) }
        viewRevision++
    }
    private fun releaseView(view: WebView, rendererDead: Boolean = false) {
        if (released.put(view, true) != null) return
        jsDialogs.cancel(view)
        (view.parent as? ViewGroup)?.removeView(view)
        if (!rendererDead) {
            view.stopLoading(); view.onPause(); view.webChromeClient = null
            view.webViewClient = WebViewClient(); view.setDownloadListener(null)
            view.removeAllViews()
        }
        view.destroy()
    }
    private fun current(entry: Entry): BrowserRequest? = entry.request?.takeIf {
        !closed && entries[entry.context.tabId] === entry && controller.isCurrent(it)
    }
    private fun verifiedMainAddress(entry: Entry, url: String?): PageAddress? {
        val address = BrowserInputPolicy.address(url ?: return null) ?: return null
        return address.takeIf { entry.view.url == url }
    }
    private fun fail(request: BrowserRequest, code: ErrorCode, status: Int? = null) {
        val error = ErrorEnvelope(code, ErrorModule.BROWSER, code != ErrorCode.TLS_REJECTED,
            UserAction.RETRY, SafeContext(httpStatus = status))
        if (controller.acceptPageFailed(request, error, System.currentTimeMillis())) {
            entries[request.context.tabId]?.let { jsDialogs.cancel(it.view) }
            discoveryOrigins.invalidate(request.context)
            errors.report(error); publish()
        }
    }
    private fun client(entry: Entry) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val address = BrowserInputPolicy.address(request.url.toString())
            if (address == null) { notice = "已阻止网页发起的外部或不安全地址跳转。"; return true }
            if (!request.isForMainFrame) return false
            if (entries[entry.context.tabId] !== entry || closed) return true
            jsDialogs.cancel(view); cancelFiles(); hideFullscreen()
            if (request.isRedirect) discoveryOrigins.invalidate(entry.context)
            if (!request.hasGesture() && current(entry) != null && entry.admission?.expects(address.value) == true) return false
            if (request.isRedirect && current(entry) != null) {
                entry.admission?.allowRedirect(address.value); return false
            }
            val next = controller.beginNavigation(entry.context.tabId, address, operation())
            discoveryOrigins.invalidate(entry.context)
            // Recreate on every controlled navigation. Callbacks from the old View cannot
            // borrow a newer request identity, including same-URL reload/back/forward.
            handler.post { if (controller.isCurrent(next)) load(next) }
            publish(); return true
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (entries[entry.context.tabId] !== entry || closed || entry.suppressed) return
            val address = BrowserInputPolicy.address(url ?: return) ?: return
            entry.inspectionDocument.started()
            entry.jsDocument++; entry.presenceBudget = BrowserVideoPresenceBudget()
            if (entry.context == state.selectedTab?.context) videoPresenceController.update(null)
            entry.jsDialogCount = 0; entry.jsDialogsBlocked = false
            jsDialogs.cancel(view)
            if (entry.context == state.selectedTab?.context && discoveryNavigation?.onStarted(address.value) == false)
                invalidateDiscoveryOrigin()
            var request = current(entry)
            val tab = controller.state.tabs.firstOrNull { it.context == entry.context } ?: return
            // POST/script/history navigation may not call shouldOverrideUrlLoading.
            if (request == null || tab.loadState == BrowserLoadState.READY) {
                request = controller.beginNavigation(entry.context.tabId, address, operation()); entry.request = request
                discoveryOrigins.invalidate(entry.context)
                entry.admission = NavigationAdmission(address.value)
            }
            if (entry.admission?.onStarted(address.value) != true) return
            controller.acceptPageStarted(request, address, System.currentTimeMillis()); cancelFiles(); publish()
        }
        override fun onPageFinished(view: WebView, url: String?) {
            val request = current(entry) ?: return
            val address = verifiedMainAddress(entry, url) ?: return
            if (entry.admission?.accepts(address.value) != true) return
            if (controller.acceptPageFinished(request, address, view.canGoBack(), view.canGoForward(), System.currentTimeMillis())) {
                entry.inspectionDocument.finished(address.value, view.url)
                entry.bookmarkAddress = address.value
                entry.presenceNavigationRevision++
                bookmarkNavigationRevision++
                if (entry.context == state.selectedTab?.context) discoveryNavigation?.onFinished()
                publish()
            }
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (closed || entries[entry.context.tabId] !== entry) return
            jsDialogs.cancel(view)
            bookmarkNavigationRevision++
            entry.presenceNavigationRevision++
            val tab = state.tabs.firstOrNull { it.context == entry.context }
            entry.inspectionDocument.history(url, view.url, isReload,
                current(entry) != null && !entry.suppressed && entry.admission?.hasStarted() == true,
                tab?.loadState == BrowserLoadState.READY)
            // History API/hash changes are same-document only. A new or unverified document
            // cannot borrow a prior READY title/address; a stale callback fails URL equality.
            entry.bookmarkAddress = url?.takeIf { actual ->
                !isReload && current(entry) != null && tab?.loadState == BrowserLoadState.READY && view.url == actual &&
                    BrowserInputPolicy.origin(actual) != null &&
                    BrowserInputPolicy.origin(actual) == BrowserInputPolicy.origin(tab.address?.value)
            }
            if (entry.context == state.selectedTab?.context &&
                (isReload || discoveryNavigation?.onVisitedHistory(url ?: "") == false)) invalidateDiscoveryOrigin()
            syncVideoPresence()
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || verifiedMainAddress(entry, request.url.toString()) == null ||
                entry.admission?.accepts(request.url.toString()) != true) return
            current(entry)?.let { fail(it, when (error.errorCode) {
                ERROR_TIMEOUT -> ErrorCode.NETWORK_TIMEOUT
                ERROR_HOST_LOOKUP, ERROR_CONNECT -> ErrorCode.NETWORK_UNAVAILABLE
                else -> ErrorCode.PAGE_LOAD_FAILED
            }) }
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame || verifiedMainAddress(entry, request.url.toString()) == null ||
                entry.admission?.accepts(request.url.toString()) != true) return
            current(entry)?.let { fail(it, ErrorCode.HTTP_FAILURE, response.statusCode.takeIf { n -> n in 100..599 }) }
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            current(entry)?.takeIf { entry.admission?.expects(error.url) == true }?.let { fail(it, ErrorCode.TLS_REJECTED) }
        }
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            handler.cancel(); notice = "此版本不处理 HTTP 身份验证弹窗；普通网站登录表单可在网页内使用。"
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (closed || entries[entry.context.tabId] !== entry) { releaseView(view, rendererDead = true); return true }
            jsDialogs.cancel(view); cancelFiles(); hideFullscreen()
            discoveryOrigins.invalidate(entry.context)
            // Android dispatches this callback to every affected WebView, including
            // views sharing a renderer. Handle each exact instance, preserving healthy tabs.
            entries.remove(entry.context.tabId)
            controller.rendererGone(setOf(entry.context.tabId))
            history.remove(entry.context.tabId); releaseView(view, rendererDead = true)
            errors.report(ErrorEnvelope(ErrorCode.RENDERER_GONE, ErrorModule.BROWSER, true, UserAction.RETRY))
            notice = "网页渲染进程已停止，受影响页面已释放。点击重新加载可安全重建。"
            viewRevision++; publish(); return true
        }
    }
    private fun chrome(entry: Entry) = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            current(entry)?.takeIf { entry.admission?.hasStarted() == true }?.let { if (controller.acceptProgress(it, newProgress, System.currentTimeMillis())) publish(persist = false) }
        }
        override fun onReceivedTitle(view: WebView, title: String?) {
            current(entry)?.takeIf { entry.admission?.accepts(view.url ?: "") == true }?.let { if (controller.acceptTitle(it, view.title, System.currentTimeMillis())) publish(persist = false) }
        }
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
            notice = "${BrowserInputPolicy.origin(request.origin.toString()) ?: "未知来源"} 请求摄像头或麦克风；此版本未启用，已拒绝。"
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
            notice = "${BrowserInputPolicy.origin(origin) ?: "未知来源"} 请求位置；此版本未启用，已拒绝。"
        }
        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            cancelFiles()
            val tab = state.selectedTab
            val origin = BrowserInputPolicy.origin(webView.url)
            if (!foreground || tab?.context != entry.context || tab.loadState in setOf(BrowserLoadState.CANCELLED, BrowserLoadState.FAILED) || origin == null || entries[entry.context.tabId] !== entry) {
                callback.onReceiveValue(null); return true
            }
            jsDialogs.cancel(webView)
            val choice = FileChoice(entry.context, tab.epoch, origin,
                BrowserInputPolicy.safeAcceptTypes(params.acceptTypes), params.mode == FileChooserParams.MODE_OPEN_MULTIPLE, callback)
            fileChoice = choice; onChooseFiles(choice); return true
        }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (closed || entries[entry.context.tabId] !== entry || !foreground || state.selectedTab?.context != entry.context ||
                state.selectedTab?.loadState in setOf(BrowserLoadState.CANCELLED, BrowserLoadState.FAILED) || fullScreenView != null) {
                callback.onCustomViewHidden(); return
            }
            jsDialogs.cancel(); cancelFiles(); fullScreenView = view; fullscreenCallback = callback; fullscreenOwner = entry
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
        requireMain(); if (closed) return
        closed = true; jsDialogs.cancel(); videoPresenceController.close(); handler.removeCallbacks(timeout); cancelFiles(); hideFullscreen()
        discoveryOrigins.close(); discoveryOrigin = null; discoveryNavigation = null
        val views = entries.values.toList(); entries.clear(); views.forEach { releaseView(it.view) }; history.clear()
    }
}
