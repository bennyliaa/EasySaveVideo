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
import com.easysavevideo.core.model.*

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
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val wide = maxWidth >= 840.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(visuals.spacingMedium), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvThemeIcon(contentDescription = null, modifier = Modifier.size(visuals.iconSize))
                    EsvText("EasySaveVideo", style = visuals.titleTextStyle, modifier = Modifier.weight(1f))
                    EsvButton("发现", onClick = onDiscovery)
                    EsvButton("设置", onClick = { bookmarksOpen = false; settingsOpen = !settingsOpen; focus.clearFocus() })
                }
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    items(state.tabs, key = { it.context.tabId }) { tab ->
                        Row(Modifier.border(visuals.borderWidth,
                            if (tab.context.tabId == state.selectedTabId) visuals.colors.accent else visuals.colors.border,
                            RoundedCornerShape(visuals.corner)).padding(visuals.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                            EsvButton(tab.title?.take(28) ?: tab.address?.value?.take(28) ?: "新标签",
                                onClick = { editing = false; browser.selectTab(tab.context.tabId) })
                            EsvButton("关闭", onClick = { browser.closeTab(tab.context.tabId) },
                                modifier = Modifier.semantics { contentDescription = "关闭此标签" })
                        }
                    }
                    item { EsvButton("＋ 标签", onClick = { editing = false; browser.newTab() }) }
                }
                Row(Modifier.fillMaxWidth().padding(visuals.spacingMedium), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    BasicTextField(value = address, onValueChange = { editing = true; address = it.take(8192) },
                        modifier = Modifier.weight(1f).border(visuals.borderWidth, visuals.colors.border,
                            RoundedCornerShape(visuals.corner)).background(visuals.colors.surface, RoundedCornerShape(visuals.corner))
                            .padding(visuals.spacingMedium).semantics { contentDescription = "网页地址" },
                        textStyle = visuals.bodyTextStyle.copy(color = visuals.colors.text),
                        cursorBrush = SolidColor(visuals.colors.accent), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        decorationBox = { input -> Box { if (address.isEmpty()) EsvText("输入网址", color = visuals.colors.muted); input() } })
                    EsvButton("前往", onClick = { go() }, enabled = address.isNotBlank())
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvButton(if (saveScanning) "分析中…" else "保存", onClick = onSave, enabled = !saveScanning && selected?.loadState == BrowserLoadState.READY)
                    EsvButton("保存任务", onClick = onSaveTasks)
                    EsvButton("媒体库", onClick = onLibrary)
                    bookmarks?.let { runtime ->
                        EsvButton("收藏此页", onClick = {
                            editing = false; focus.clearFocus()
                            val page = browser.acceptBookmarkPage(bookmarkPage)
                            if (page == null) runtime.stalePage() else runtime.add(page)
                        }, enabled = runtime.state.ready && !runtime.state.busy && bookmarkPage != null)
                        EsvButton("收藏列表", onClick = { editing = false; focus.clearFocus(); settingsOpen = false; bookmarksOpen = !bookmarksOpen })
                    }
                    EsvButton("后退", onClick = { browser.back() }, enabled = selected?.canGoBack == true && browser.selectedView() != null)
                    EsvButton("前进", onClick = browser::forward, enabled = selected?.canGoForward == true && browser.selectedView() != null)
                    if (selected?.loadState == BrowserLoadState.LOADING) EsvButton("停止", onClick = browser::stopSelected)
                    else EsvButton("重新加载", onClick = browser::reload, enabled = selected?.address != null)
                    EsvText(if (selected?.loadState == BrowserLoadState.LOADING) "${selected.progressPercent}%" else "零 Provider · 普通标签",
                        style = visuals.captionTextStyle, modifier = Modifier.align(Alignment.CenterVertically))
                }
                bookmarks?.state?.notice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(bookmarkNoticeText(message), modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton("关闭提示", onClick = bookmarks::dismissNotice)
                    }
                }
                saveNotice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(message, modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton("关闭提示", onClick = onDismissSaveNotice)
                    }
                }
                browser.notice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(message, modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton("关闭提示", onClick = browser::dismissNotice)
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
                            EsvText(if (!browser.platformAvailable) "系统网页内核不可用" else if (selected?.loadState == BrowserLoadState.CANCELLED) "加载已停止，重新加载可继续" else if (selected?.error != null) errorText(selected.error!!.code) else "浏览网页",
                                style = visuals.titleTextStyle)
                            Spacer(Modifier.height(visuals.spacingMedium))
                            EsvText("输入 http 或 https 地址即可浏览。无需搜索来源或登录应用账号。", color = visuals.colors.muted)
                            Spacer(Modifier.height(visuals.spacingMedium))
                            if (selected?.address != null) EsvButton("打开 / 重试此地址", onClick = browser::reload)
                            if (!browser.platformAvailable && selected?.address != null) EsvButton("用系统浏览器打开", onClick = openExternal)
                            Spacer(Modifier.height(visuals.spacingLarge))
                            EsvText("当前为开发版本。可免费保存 HTTPS 兼容 MP4；受限静态 HLS 和高级录制 / 编辑按现有权益检查。请先打开网页。真实录制、GPU 与设备验收尚未执行。", style = visuals.captionTextStyle, color = visuals.colors.muted)
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
                    EsvButton("退出全屏", onClick = browser::hideFullscreen, modifier = Modifier.align(Alignment.TopEnd).padding(visuals.spacingMedium))
                }
            }
        }
    }
    if (confirmCookies) EsvDialog(onDismiss = { confirmCookies = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
            EsvText("清除全部 Cookie？", style = visuals.titleTextStyle)
            EsvText("普通标签共享系统 WebView Cookie。清除会让所有网站登录状态失效，已保存的网页地址不变。")
            EsvButton("清除", onClick = { confirmCookies = false; browser.clearCookies {} })
            EsvButton("取消", onClick = { confirmCookies = false })
        }
    }
    filePrompt?.let { choice -> EsvDialog(onDismiss = onFileCancel) {
        Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
            EsvText("允许选择文件？", style = visuals.titleTextStyle)
            EsvText("来源：${choice.origin}")
            EsvText("只会把你在系统选择器中选中的文件交给此网页。不会拍照、录音或授予文件夹访问。导航变化后会取消。")
            EsvButton("选择文件", onClick = { onFileConfirm(choice) })
            EsvButton("取消", onClick = onFileCancel)
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
        EsvText("浏览器设置", style = v.titleTextStyle)
        EsvButton("完成", onClick = onClose)
        Toggle("JavaScript", settings.browser.javaScriptEnabled) { update(settings.copy(browser = settings.browser.copy(javaScriptEnabled = it))) }
        EsvText("关闭可能影响视频与登录页面；不会向网页注入原生桥接。", style = v.captionTextStyle)
        Toggle("允许第三方 Cookie", settings.browser.acceptThirdPartyCookies) { update(settings.copy(browser = settings.browser.copy(acceptThirdPartyCookies = it))) }
        EsvText("默认关闭。普通标签共享 Cookie，没有无痕或逐标签隔离承诺。", style = v.captionTextStyle)
        Toggle("保存标签地址以供恢复", settings.browser.restoreTabs) { update(settings.copy(browser = settings.browser.copy(restoreTabs = it))) }
        EsvText("只保存地址。关闭会清除本机保存的标签地址；不会清除网站 Cookie。重启后需手动打开恢复的页面。", style = v.captionTextStyle)
        EsvText("活动网页上限：${settings.browser.maxActiveWebViews}")
        Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvButton("减少", onClick = { update(settings.copy(browser = settings.browser.copy(maxActiveWebViews = settings.browser.maxActiveWebViews - 1))) }, enabled = settings.browser.maxActiveWebViews > 1)
            EsvButton("增加", onClick = { update(settings.copy(browser = settings.browser.copy(maxActiveWebViews = settings.browser.maxActiveWebViews + 1))) }, enabled = settings.browser.maxActiveWebViews < 4)
        }
        EsvText("最多 12 个标签；超出活动上限会释放较旧网页，切回时恢复有限的内存历史。", style = v.captionTextStyle)
        EsvButton("清除全部 Cookie", onClick = clearCookies)
        EsvButton("清除网页缓存", onClick = browser::clearCache)
        EsvText("相机、麦克风与位置请求会按来源明确拒绝。文件上传需逐次确认并选择文件。", style = v.captionTextStyle)
        destinations?.let { OrdinaryDestinationSettings(it) }
        EsvButton("Provider 管理与恢复", onClick = onManageProviders)
        EsvText("来源配置和本地维护状态可在 Provider 管理中查看。真实支付未接入，免费浏览不依赖来源或权益。", style = v.captionTextStyle)
        EsvButton("第三方软件许可（离线）", onClick = { noticesOpen = true })
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
