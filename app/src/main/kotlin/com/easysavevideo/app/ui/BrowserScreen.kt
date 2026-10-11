package com.easysavevideo.app.ui

import com.easysavevideo.app.library.destination.AndroidOrdinaryDestinationRuntime
import com.easysavevideo.app.library.destination.OrdinaryDestinationSettings

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.easysavevideo.app.browser.bookmarks.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.entitlements.DevelopmentEntitlementPanel
import com.easysavevideo.app.theme.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.core.model.*

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    browser: SystemBrowserHost,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    filePrompt: SystemBrowserHost.FileChoice?,
    onFileCancel: () -> Unit,
    onFileConfirm: (SystemBrowserHost.FileChoice) -> Unit,
    openExternal: () -> Unit,
    onDiscovery: () -> Unit,
    onManageProviders: () -> Unit,
    onSave: () -> Unit,
    onSaveTasks: () -> Unit,
    onLibrary: () -> Unit,
    saveScanning: Boolean,
    saveNotice: String?,
    onDismissSaveNotice: () -> Unit,
    destinations: AndroidOrdinaryDestinationRuntime? = null,
    bookmarks: AndroidBookmarkRuntime? = null,
) {
    val visuals = LocalEsvVisuals.current
    val addressDescription = stringResource(R.string.flow_address_description)
    val closeTabDescription = stringResource(R.string.flow_close_tab_description)
    var moreControls by rememberSaveable { mutableStateOf(false) }
    val state = browser.state
    val selected = state.selectedTab
    var settingsOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    // Read this observable revision even when a same-document navigation leaves tab state unchanged.
    val bookmarkRevision = browser.bookmarkNavigationRevision
    val bookmarkPage = remember(state, bookmarkRevision, browser.viewRevision) { browser.bookmarkPage() }
    var confirmCookies by remember { mutableStateOf(false) }
    var address by remember(selected?.context?.tabId) { mutableStateOf(selected?.address?.value.orEmpty()) }
    var editing by remember(selected?.context?.tabId) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(selected?.address?.value) { if (!editing) address = selected?.address?.value.orEmpty() }
    fun go() { editing = false; focus.clearFocus(); browser.navigate(address) }
    BackHandler(enabled = bookmarksOpen || settingsOpen || browser.fullScreenView != null || browser.selectedView()?.canGoBack() == true) {
        if (bookmarksOpen) { bookmarksOpen = false; focus.clearFocus() } else if (settingsOpen) settingsOpen = false else browser.back()
    }
    EsvBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val wide = maxWidth >= 840.dp
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = visuals.spacingMedium), verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvText(stringResource(R.string.ui_browser), style = visuals.titleTextStyle)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvButton(stringResource(R.string.flow_browserscreen_1), onClick = onDiscovery)
                        EsvButton(stringResource(R.string.flow_browserscreen_2), onClick = { bookmarksOpen = false; settingsOpen = !settingsOpen; focus.clearFocus() })
                    }
                }
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    items(state.tabs, key = { it.context.tabId }) { tab ->
                        Row(Modifier.border(visuals.borderWidth,
                            if (tab.context.tabId == state.selectedTabId) visuals.colors.accent else visuals.colors.border,
                            RoundedCornerShape(visuals.corner)).padding(visuals.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                            EsvButton(tab.title?.take(28) ?: tab.address?.value?.take(28) ?: stringResource(R.string.flow_new_tab),
                                onClick = { editing = false; browser.selectTab(tab.context.tabId) })
                            EsvButton(stringResource(R.string.flow_browserscreen_3), onClick = { browser.closeTab(tab.context.tabId) },
                                modifier = Modifier.semantics { contentDescription = closeTabDescription })
                        }
                    }
                    item { EsvButton(stringResource(R.string.flow_browserscreen_4), onClick = { editing = false; browser.newTab() }) }
                }
                Row(Modifier.fillMaxWidth().padding(visuals.spacingMedium), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    OutlinedTextField(value = address, onValueChange = { editing = true; address = it.take(8192) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = addressDescription },
                        textStyle = visuals.bodyTextStyle, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        placeholder = { Text(stringResource(R.string.flow_browserscreen_5)) })
                    EsvButton(stringResource(R.string.flow_browserscreen_6), onClick = { go() }, enabled = address.isNotBlank())
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvButton(stringResource(if (saveScanning) R.string.flow_inspecting else R.string.flow_inspect), onClick = onSave, enabled = !saveScanning && selected?.loadState == BrowserLoadState.READY)
                    EsvButton(stringResource(R.string.flow_browserscreen_7), onClick = onSaveTasks)
                    EsvButton(stringResource(R.string.flow_browserscreen_8), onClick = onLibrary)
                    EsvButton(stringResource(if (moreControls) R.string.flow_less else R.string.flow_more),
                        onClick = { moreControls = !moreControls })
                }
                if (moreControls) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    bookmarks?.let { runtime ->
                        EsvButton(stringResource(R.string.flow_browserscreen_9), onClick = {
                            editing = false; focus.clearFocus()
                            val page = browser.acceptBookmarkPage(bookmarkPage)
                            if (page == null) runtime.stalePage() else runtime.add(page)
                        }, enabled = runtime.state.ready && !runtime.state.busy && bookmarkPage != null)
                        EsvButton(stringResource(R.string.flow_browserscreen_10), onClick = { editing = false; focus.clearFocus(); settingsOpen = false; bookmarksOpen = !bookmarksOpen })
                    }
                    EsvButton(stringResource(R.string.flow_browserscreen_11), onClick = { browser.back() }, enabled = selected?.canGoBack == true && browser.selectedView() != null)
                    EsvButton(stringResource(R.string.flow_browserscreen_12), onClick = browser::forward, enabled = selected?.canGoForward == true && browser.selectedView() != null)
                    if (selected?.loadState == BrowserLoadState.LOADING) EsvButton(stringResource(R.string.flow_browserscreen_13), onClick = browser::stopSelected)
                    else EsvButton(stringResource(R.string.flow_browserscreen_14), onClick = browser::reload, enabled = selected?.address != null)
                    EsvText(if (selected?.loadState == BrowserLoadState.LOADING) "${selected.progressPercent}%" else stringResource(R.string.flow_ordinary_tab),
                        style = visuals.captionTextStyle, modifier = Modifier.align(Alignment.CenterVertically))
                }
                bookmarks?.state?.notice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(bookmarkNoticeText(message), modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton(stringResource(R.string.flow_browserscreen_15), onClick = bookmarks::dismissNotice)
                    }
                }
                saveNotice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(message, modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton(stringResource(R.string.flow_browserscreen_15), onClick = onDismissSaveNotice)
                    }
                }
                browser.notice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(message, modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton(stringResource(R.string.flow_browserscreen_15), onClick = browser::dismissNotice)
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = visuals.spacingSmall)) {
                        val revision = browser.viewRevision
                        val view = browser.selectedView()
                        val blockedPage = selected?.loadState in setOf(BrowserLoadState.FAILED, BrowserLoadState.CANCELLED, BrowserLoadState.RENDERER_UNAVAILABLE)
                        if (view != null && !blockedPage) key(selected?.context?.tabId, revision, view) {
                            AndroidView(factory = {
                                (view.parent as? ViewGroup)?.removeView(view); view
                            }, modifier = Modifier.fillMaxSize())
                        }
                        val showEmpty = view == null || blockedPage
                        if (showEmpty) Column(Modifier.fillMaxSize().background(visuals.colors.bg).padding(visuals.spacingLarge)
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            EsvText(if (!browser.platformAvailable) stringResource(R.string.flow_web_unavailable) else if (selected?.loadState == BrowserLoadState.CANCELLED) stringResource(R.string.flow_web_stopped) else if (selected?.error != null) errorText(selected.error!!.code) else stringResource(R.string.flow_browse_empty),
                                style = visuals.titleTextStyle)
                            Spacer(Modifier.height(visuals.spacingMedium))
                            EsvText(stringResource(R.string.flow_browserscreen_18), color = visuals.colors.muted)
                            Spacer(Modifier.height(visuals.spacingMedium))
                            if (selected?.address != null) EsvButton(stringResource(R.string.flow_browserscreen_19), onClick = browser::reload)
                            if (!browser.platformAvailable && selected?.address != null) EsvButton(stringResource(R.string.flow_browserscreen_20), onClick = openExternal)
                            Spacer(Modifier.height(visuals.spacingLarge))
                            EsvText(stringResource(R.string.flow_browserscreen_21), style = visuals.captionTextStyle, color = visuals.colors.muted)
                        }
                    }
                    if (bookmarksOpen && wide && bookmarks != null) Box(Modifier.width(360.dp).fillMaxHeight().background(visuals.colors.surface)) {
                        BookmarkPanel(bookmarks, onOpen = { item ->
                            bookmarks.addressForOpen(item)?.let { target ->
                                bookmarksOpen = false; editing = false; focus.clearFocus(); browser.navigate(target)
                            }
                        }, onClose = { bookmarksOpen = false; focus.clearFocus() })
                    }
                    if (settingsOpen && wide) Box(Modifier.width(360.dp).fillMaxHeight().background(visuals.colors.surface)) {
                        SettingsPanel(settings, onSettings, browser, onClose = { settingsOpen = false }, clearCookies = { confirmCookies = true },
                            onManageProviders = { settingsOpen = false; onManageProviders() }, destinations = destinations)
                    }
                }
            }
            if (bookmarksOpen && !wide && bookmarks != null) EsvDialog(onDismiss = { bookmarksOpen = false; focus.clearFocus() }) {
                BookmarkPanel(bookmarks, onOpen = { item ->
                    bookmarks.addressForOpen(item)?.let { target ->
                        bookmarksOpen = false; editing = false; focus.clearFocus(); browser.navigate(target)
                    }
                }, onClose = { bookmarksOpen = false; focus.clearFocus() })
            }
            if (settingsOpen && !wide) EsvDialog(onDismiss = { settingsOpen = false }) {
                SettingsPanel(settings, onSettings, browser, onClose = { settingsOpen = false }, clearCookies = { confirmCookies = true },
                            onManageProviders = { settingsOpen = false; onManageProviders() }, destinations = destinations)
            }
            browser.fullScreenView?.let { view ->
                Box(Modifier.fillMaxSize().background(visuals.colors.bg)) {
                    AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
                    EsvButton(stringResource(R.string.flow_browserscreen_22), onClick = browser::hideFullscreen, modifier = Modifier.align(Alignment.TopEnd).padding(visuals.spacingMedium))
                }
            }
        }
    }
    if (confirmCookies) EsvDialog(onDismiss = { confirmCookies = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
            EsvText(stringResource(R.string.flow_browserscreen_23), style = visuals.titleTextStyle)
            EsvText(stringResource(R.string.flow_browserscreen_24))
            EsvButton(stringResource(R.string.flow_browserscreen_25), onClick = { confirmCookies = false; browser.clearCookies {} })
            EsvButton(stringResource(R.string.flow_browserscreen_26), onClick = { confirmCookies = false })
        }
    }
    filePrompt?.let { choice -> EsvDialog(onDismiss = onFileCancel) {
        Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
            EsvText(stringResource(R.string.flow_browserscreen_27), style = visuals.titleTextStyle)
            EsvText("来源：${choice.origin}")
            EsvText(stringResource(R.string.flow_browserscreen_29))
            EsvButton(stringResource(R.string.flow_browserscreen_30), onClick = { onFileConfirm(choice) })
            EsvButton(stringResource(R.string.flow_browserscreen_26), onClick = onFileCancel)
        }
    } }
}

@Composable private fun EsvDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val v = LocalEsvVisuals.current
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingLarge)) { content() }
    }
}
@Composable private fun SettingsPanel(settings: AppSettings, update: (AppSettings) -> Unit, browser: SystemBrowserHost,
    onClose: () -> Unit, clearCookies: () -> Unit, onManageProviders: () -> Unit, destinations: AndroidOrdinaryDestinationRuntime?) {
    val v = LocalEsvVisuals.current
    var noticesOpen by remember { mutableStateOf(false) }
    if (noticesOpen) {
        com.easysavevideo.app.licenses.ThirdPartyNotices { noticesOpen = false }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        EsvText(stringResource(R.string.flow_browserscreen_32), style = v.titleTextStyle)
        EsvButton(stringResource(R.string.flow_browserscreen_33), onClick = onClose)
        Toggle("JavaScript", settings.browser.javaScriptEnabled) { update(settings.copy(browser = settings.browser.copy(javaScriptEnabled = it))) }
        EsvText(stringResource(R.string.flow_browserscreen_34), style = v.captionTextStyle)
        Toggle("允许第三方 Cookie", settings.browser.acceptThirdPartyCookies) { update(settings.copy(browser = settings.browser.copy(acceptThirdPartyCookies = it))) }
        EsvText(stringResource(R.string.flow_browserscreen_35), style = v.captionTextStyle)
        Toggle("保存标签地址以供恢复", settings.browser.restoreTabs) { update(settings.copy(browser = settings.browser.copy(restoreTabs = it))) }
        EsvText(stringResource(R.string.flow_browserscreen_36), style = v.captionTextStyle)
        EsvText("活动网页上限：${settings.browser.maxActiveWebViews}")
        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvButton(stringResource(R.string.flow_browserscreen_38), onClick = { update(settings.copy(browser = settings.browser.copy(maxActiveWebViews = settings.browser.maxActiveWebViews - 1))) }, enabled = settings.browser.maxActiveWebViews > 1)
            EsvButton(stringResource(R.string.flow_browserscreen_39), onClick = { update(settings.copy(browser = settings.browser.copy(maxActiveWebViews = settings.browser.maxActiveWebViews + 1))) }, enabled = settings.browser.maxActiveWebViews < 4)
        }
        EsvText(stringResource(R.string.flow_browserscreen_40), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.flow_browserscreen_41), onClick = clearCookies)
        EsvButton(stringResource(R.string.flow_browserscreen_42), onClick = browser::clearCache)
        EsvText(stringResource(R.string.flow_browserscreen_43), style = v.captionTextStyle)
        destinations?.let { OrdinaryDestinationSettings(it) }
        EsvButton(stringResource(R.string.flow_browserscreen_44), onClick = onManageProviders)
        EsvText(stringResource(R.string.flow_browserscreen_45), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.flow_browserscreen_46), onClick = { noticesOpen = true })
        DevelopmentEntitlementPanel()
    }
}
@Composable private fun Toggle(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    EsvButton("$label：${if (enabled) "开" else "关"}", onClick = { onChange(!enabled) }, modifier = Modifier.fillMaxWidth())
}
private fun errorText(code: ErrorCode): String = when (code) {
    ErrorCode.TLS_REJECTED -> "网站安全证书无法验证，已拒绝连接"
    ErrorCode.RENDERER_GONE -> "网页渲染进程已停止"
    ErrorCode.DEADLINE_EXCEEDED, ErrorCode.NETWORK_TIMEOUT -> "网页加载超时"
    ErrorCode.NETWORK_UNAVAILABLE -> "网络连接不可用"
    ErrorCode.HTTP_FAILURE -> "网站返回了错误"
    else -> "网页暂时无法打开"
}
