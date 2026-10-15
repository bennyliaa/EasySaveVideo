package com.easysavevideo.app.ui

import com.easysavevideo.app.ui.shell.BrowserSettingsDetail
import com.easysavevideo.app.ui.shell.SettingsEdit
import com.easysavevideo.app.library.destination.AndroidOrdinaryDestinationRuntime
import com.easysavevideo.app.library.destination.OrdinaryDestinationSettings

import android.view.View
import android.view.ViewGroup
import com.easysavevideo.app.browser.privacy.PrivateBrowsingPhase
import com.easysavevideo.app.browser.privacy.privateProfileImeFlags
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.window.DialogProperties
import kotlin.reflect.KProperty
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.easysavevideo.app.browser.bookmarks.*
import com.easysavevideo.app.browser.history.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import com.easysavevideo.app.save.AndroidSaveRuntime
import com.easysavevideo.app.save.SaveSelectionPanel
import com.easysavevideo.app.save.SaveSelectionPresentation
import com.easysavevideo.app.save.SaveSelectionDialog
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.easysavevideo.app.browser.NewTabSubmissionGate
import com.easysavevideo.app.browser.NewTabInputPolicy
import com.easysavevideo.app.browser.NewTabInputMode
import com.easysavevideo.app.browser.NewTabInput
import com.easysavevideo.app.browser.BrowserVideoPresence
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
    onSettings: (SettingsEdit.BrowserEdit) -> Unit,
    filePrompt: SystemBrowserHost.FileChoice?,
    onFileCancel: (SystemBrowserHost.FileChoice) -> Unit,
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
    ordinaryHistory: AndroidHistoryRuntime? = null,
    saveRuntime: AndroidSaveRuntime? = null,
    interactiveJsDialogs: Boolean = true,
    onVideoSearchDraft: ((String) -> Unit)? = null,
    onRecording: (() -> Unit)? = null,
    newTabInputGeneration: Long = 0,
    canSubmitNewTabInput: (Long) -> Boolean = { false },
    settingsEditable: Boolean = false,
    isFilePromptCurrent: (SystemBrowserHost.FileChoice) -> Boolean = { false },
    onPresentationApplied: () -> Unit = {},
    onPresentationLayout: () -> Unit = {},
) {
    val visuals = LocalEsvVisuals.current
    val addressDescription = stringResource(R.string.flow_address_description)
    val closeTabDescription = stringResource(R.string.flow_close_tab_description)
    var moreControls by rememberSaveable { mutableStateOf(false) }
    val state = browser.state
    val selected = state.selectedTab
    val privateMode = selected?.incognito == true
    val privatePhase = browser.privateStatus.phase
    val privateReady = browser.privateEntryAvailable
    // This is the exact root-generation admission published by Main, never a latest-value bridge.
    val inputAdmission = LocalPrivateInputAdmission.current
    val dialogEnvironment = LocalPrivateAppDialogEnvironment.current
    fun inputAllowed(): Boolean = (!privateMode || dialogEnvironment != null) && inputAdmission()
    val modalOwnerIdentity = remember(browser, selected?.context, selected?.epoch, browser.saveInspectionRevision) { Any() }
    val privateExitRequest = remember(modalOwnerIdentity) { BrowserDialogRequestState() }
    var confirmPrivateExit by privateExitRequest
    val privateQueryNotice = stringResource(R.string.private_browser_query_unavailable)
    val settingsRequest = remember(modalOwnerIdentity) { BrowserDialogRequestState() }
    var settingsOpen by settingsRequest
    val bookmarksRequest = rememberSaveable(modalOwnerIdentity, saver = BrowserDialogRequestState.Saver) { BrowserDialogRequestState() }
    var bookmarksOpen by bookmarksRequest
    val bookmarkListState = rememberLazyListState()
    val cookiesRequest = remember(modalOwnerIdentity) { BrowserDialogRequestState() }
    var confirmCookies by cookiesRequest
    val historyRequest = rememberSaveable(modalOwnerIdentity, saver = BrowserDialogRequestState.Saver) { BrowserDialogRequestState() }
    var historyOpen by historyRequest
    val historyListState = rememberLazyListState()
    val historyOwner = remember(ordinaryHistory, newTabInputGeneration, selected?.context, selected?.epoch) { HistoryPanelActionOwner() }
    val historyClaim = historyOwner.token
    DisposableEffect(historyOwner) { onDispose { historyOwner.retire() } }
    fun closeHistory() { historyOwner.invalidate(); historyOpen = false }
    fun historyCanAct(): Boolean = historyOwner.current(historyClaim) && historyOpen &&
        interactiveJsDialogs && canSubmitNewTabInput(newTabInputGeneration) &&
        selected != null && selected.context == browser.state.selectedTab?.context &&
        selected.epoch == browser.state.selectedTab?.epoch && browser.state.selectedTab?.incognito == false &&
        !settingsOpen && !bookmarksOpen && !confirmCookies && !confirmPrivateExit && filePrompt == null &&
        !saveScanning && saveRuntime?.scanning != true && saveRuntime?.selection == null &&
        saveRuntime?.preparingStream != true && saveRuntime?.hlsPreparation == null &&
        destinations?.asking != true && destinations?.notice == null && browser.jsDialog == null &&
        browser.fullScreenView == null && !browser.pickerInFlight && !browser.hasPendingFileChoice

    // Read this observable revision even when a same-document navigation leaves tab state unchanged.
    val bookmarkRevision = browser.bookmarkNavigationRevision
    val bookmarkPage = remember(state, bookmarkRevision, browser.viewRevision) { browser.bookmarkPage() }
    var address by remember(selected?.context?.tabId) { mutableStateOf(selected?.address?.value.orEmpty()) }
    var editing by remember(selected?.context?.tabId) { mutableStateOf(false) }
    val videoSubmission = remember(selected?.context, selected?.epoch, newTabInputGeneration) { NewTabSubmissionGate() }
    DisposableEffect(videoSubmission) { onDispose { videoSubmission.retire() } }
    var videoInput by remember(selected?.context?.tabId) { mutableStateOf("") }
    var videoMode by remember(selected?.context?.tabId) { mutableStateOf(NewTabInputMode.AUTO) }
    var videoInputError by remember(selected?.context?.tabId) { mutableStateOf(false) }
    val addressClearGate = remember(selected?.context, selected?.epoch, newTabInputGeneration) { InputClearGate() }
    val videoClearGate = remember(videoSubmission) { InputClearGate() }
    // Observable tokens invalidate old callbacks even when edits return to the same string.
    var addressClearToken by remember(addressClearGate) { mutableStateOf(addressClearGate.token) }
    var videoClearToken by remember(videoClearGate) { mutableStateOf(videoClearGate.token) }
    val addressClearClaim = addressClearToken
    val videoClearClaim = videoClearToken
    val clearInspectionRevision = browser.saveInspectionRevision
    DisposableEffect(addressClearGate, videoClearGate) {
        onDispose { addressClearGate.retire(); videoClearGate.retire() }
    }
    val currentClearEnvironment = {
        interactiveJsDialogs && canSubmitNewTabInput(newTabInputGeneration) &&
            !settingsOpen && !bookmarksOpen && !historyOpen && !confirmCookies && !confirmPrivateExit && filePrompt == null && !saveScanning && saveRuntime?.scanning != true &&
            saveRuntime?.selection == null && saveRuntime?.preparingStream != true && saveRuntime?.hlsPreparation == null &&
            destinations?.asking != true && destinations?.notice == null &&
            browser.jsDialog == null && browser.fullScreenView == null && !browser.pickerInFlight && !browser.hasPendingFileChoice
    }
    fun clearOwnerCurrent(): Boolean = inputAllowed() && canClearBrowserDraft(selected, browser.state.selectedTab,
        clearInspectionRevision, browser.saveInspectionRevision, currentClearEnvironment())
    fun clearAddressDraft() {
        if (!addressClearGate.accept(addressClearClaim, clearOwnerCurrent(), address.isNotEmpty())) return
        addressClearToken = addressClearGate.token
        editing = true
        address = ""
    }
    fun clearVideoDraft() {
        val allowed = clearOwnerCurrent() && NewTabInputPolicy.canSubmit(selected, browser.state.selectedTab, true)
        if (!videoClearGate.accept(videoClearClaim, allowed, videoInput.isNotEmpty())) return
        videoClearToken = videoClearGate.token
        videoInput = ""
        videoInputError = false
    }
    DisposableEffect(browser, interactiveJsDialogs) {
        browser.setJsDialogUiVisible(interactiveJsDialogs)
        onDispose { browser.setJsDialogUiVisible(false) }
    }
    SideEffect {
        browser.setJsDialogUiBlocked(settingsOpen || bookmarksOpen || historyOpen || confirmCookies || confirmPrivateExit || filePrompt != null ||
            saveRuntime?.selection != null || saveRuntime?.preparingStream == true || saveRuntime?.hlsPreparation != null)
    }
    DisposableEffect(browser) { onDispose { browser.setVideoPresenceUiVisible(false) } }
    SideEffect {
        browser.setVideoPresenceUiVisible(interactiveJsDialogs && !settingsOpen && !bookmarksOpen && !historyOpen &&
            !confirmCookies && !confirmPrivateExit && filePrompt == null && !saveScanning && saveRuntime?.selection == null &&
            saveRuntime?.preparingStream != true && saveRuntime?.hlsPreparation == null)
    }
    val focus = LocalFocusManager.current
    fun submitVideoInput() {
        if (!inputAllowed()) return
        // A queued IME/click from a closed or replaced tab cannot hand off a different tab.
        if (!NewTabInputPolicy.canSubmit(selected, browser.state.selectedTab, canSubmitNewTabInput(newTabInputGeneration)) || onVideoSearchDraft == null ||
            settingsOpen || bookmarksOpen || historyOpen || confirmCookies || confirmPrivateExit || filePrompt != null ||
            saveRuntime?.selection != null || saveRuntime?.preparingStream == true || saveRuntime?.hlsPreparation != null) return
        val input = NewTabInputPolicy.interpret(videoInput, videoMode)
        // Private queries must not enter the ordinary, persistent Discovery controller.
        if (privateMode && input is NewTabInput.Query) { browser.showNotice(privateQueryNotice); return }
        if (input == NewTabInput.Invalid) { videoInputError = true; return }
        if (!videoSubmission.claim(selected, browser.state.selectedTab, canSubmitNewTabInput(newTabInputGeneration))) return
        videoClearGate.retire()
        videoClearToken = videoClearGate.token
        when (input) {
            is NewTabInput.Address -> { videoInputError = false; focus.clearFocus(); browser.navigate(input.value) }
            is NewTabInput.Query -> { videoInputError = false; focus.clearFocus(); onVideoSearchDraft(input.value) }
            NewTabInput.Invalid -> videoInputError = true
        }
    }
    LaunchedEffect(selected?.address?.value) {
        if (!editing) {
            address = selected?.address?.value.orEmpty()
            addressClearToken = addressClearGate.invalidate()
        }
    }
    fun go() {
        if (!inputAllowed() || !canNavigateBrowserAddress(selected, browser.state.selectedTab, clearInspectionRevision,
                browser.saveInspectionRevision, currentClearEnvironment())) return
        editing = false; focus.clearFocus(); browser.navigate(address)
    }
    fun modalOwnerCurrent(): Boolean = interactiveJsDialogs &&
        selected?.context == browser.state.selectedTab?.context && selected?.epoch == browser.state.selectedTab?.epoch &&
        clearInspectionRevision == browser.saveInspectionRevision && privateMode == browser.isPrivateMode
    fun privateModeActionEnvironment(checkMainGeneration: Boolean = true): Boolean = interactiveJsDialogs &&
        (!checkMainGeneration || canSubmitNewTabInput(newTabInputGeneration)) &&
        !saveScanning && saveRuntime?.scanning != true && saveRuntime?.selection == null &&
        saveRuntime?.preparingStream != true && saveRuntime?.hlsPreparation == null &&
        destinations?.asking != true && destinations?.notice == null && filePrompt == null &&
        browser.jsDialog == null && browser.fullScreenView == null && !browser.pickerInFlight && !browser.hasPendingFileChoice
    fun rootActionAllowed(): Boolean = inputAllowed() && modalOwnerCurrent() &&
        canSubmitNewTabInput(newTabInputGeneration)
    fun privateModeActionAllowed(): Boolean = inputAllowed() && modalOwnerCurrent() && privateModeActionEnvironment()
    fun prepareModeChange() {
        focus.clearFocus(); editing = false; address = ""; videoInput = ""; videoInputError = false
        closeHistory(); bookmarksOpen = false; settingsOpen = false; confirmCookies = false; moreControls = false
        addressClearToken = addressClearGate.invalidate(); videoClearToken = videoClearGate.invalidate()
    }
    val backSelection = saveRuntime?.currentSelectionPresentationIdentity
    BackHandler(enabled = (saveRuntime?.selection != null || historyOpen || bookmarksOpen || settingsOpen || browser.fullScreenView != null || browser.selectedView()?.canGoBack() == true)) {
        if (!rootActionAllowed()) return@BackHandler
        if (saveRuntime?.selection != null) {
            if (saveRuntime.currentSelectionPresentationIdentity === backSelection) saveRuntime.dismissSelection()
        }
        else if (historyOpen) { closeHistory(); focus.clearFocus() }
        else if (bookmarksOpen) { bookmarksOpen = false; focus.clearFocus() } else if (settingsOpen) settingsOpen = false else browser.back()
    }
    fun openHistory(token: HistoryRowToken) {
        ordinaryHistory?.let { runtime ->
            openHistoryRow(token, ::historyCanAct, runtime::addressForOpen, browser::openOrdinaryHistory,
                stillOwnsPanel = { historyOwner.current(historyClaim) && historyOpen &&
                    canSubmitNewTabInput(newTabInputGeneration) }) {
                closeHistory(); editing = false; focus.clearFocus()
            }
        }
    }
    val inspectorContent: @Composable (Modifier, SaveSelectionPresentation) -> Unit = { modifier, presentation ->
        saveRuntime?.let { SaveSelectionPanel(it, browser, modifier, presentation) }
    }
    val latestInspectorContent = rememberUpdatedState(inspectorContent)
    val inspectorPanel = remember { movableContentOf<Modifier, SaveSelectionPresentation> { modifier, presentation -> latestInspectorContent.value(modifier, presentation) } }
    CompositionLocalProvider(LocalPrivatePresentation provides (privateMode || LocalPrivatePresentation.current)) {
    EsvBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().onGloballyPositioned { onPresentationLayout() }) {
            val pane = responsivePaneLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, visuals.spacingMedium.value)
            val wide = pane.split
            val inspector = browserInspectorLayout(pane, saveRuntime?.selection != null,
                saveRuntime?.preparingStream == true || saveRuntime?.hlsPreparation != null, settingsOpen || bookmarksOpen || historyOpen)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = visuals.spacingMedium), verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvText(stringResource(if (privateMode) R.string.private_browser_title else R.string.ui_browser), style = visuals.titleTextStyle)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvButton(stringResource(if (privateMode) R.string.private_browser_return_ordinary else R.string.private_browser_enter),
                            enabled = privateModeActionAllowed() && (privateMode || privateReady), onClick = {
                                if (!privateModeActionAllowed()) return@EsvButton
                                if (privateMode) { focus.clearFocus(); if (browser.returnToOrdinaryMode()) prepareModeChange() }
                                else if (privateReady) { focus.clearFocus(); if (browser.enterPrivateMode()) prepareModeChange() }
                            })
                        if (privatePhase == PrivateBrowsingPhase.ACTIVE) EsvButton(stringResource(R.string.private_browser_exit),
                            enabled = privateModeActionAllowed(), onClick = { if (privateModeActionAllowed()) confirmPrivateExit = true })
                    }
                    if (privateMode) EsvText(stringResource(R.string.private_browser_session_note), style = visuals.captionTextStyle)
                    else if (!privateReady) EsvText(stringResource(when (privatePhase) {
                        PrivateBrowsingPhase.COLD, PrivateBrowsingPhase.OPENING, PrivateBrowsingPhase.RECOVERING, PrivateBrowsingPhase.DRAINING, PrivateBrowsingPhase.CLEARING -> R.string.private_browser_cleaning
                        PrivateBrowsingPhase.UNAVAILABLE -> R.string.private_browser_unsupported
                        PrivateBrowsingPhase.QUARANTINED, PrivateBrowsingPhase.RETIREMENT_REQUIRED -> R.string.private_browser_blocked
                        else -> if (browser.privateIntegrationReady) R.string.private_browser_busy else R.string.private_browser_not_ready
                    }), style = visuals.captionTextStyle)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvButton(stringResource(R.string.flow_browserscreen_1), onClick = { if (rootActionAllowed()) onDiscovery() })
                        EsvButton(stringResource(R.string.flow_browserscreen_2), onClick = { if (rootActionAllowed()) { closeHistory(); bookmarksOpen = false; settingsOpen = !settingsOpen; focus.clearFocus() } })
                    }
                }
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    items(state.tabs, key = { it.context.tabId }) { tab ->
                        Row(Modifier.border(visuals.borderWidth,
                            if (tab.context.tabId == state.selectedTabId) visuals.colors.accent else visuals.colors.border,
                            RoundedCornerShape(visuals.corner)).padding(visuals.spacingSmall), verticalAlignment = Alignment.CenterVertically) {
                            EsvButton(if (tab.incognito) stringResource(R.string.private_browser_tab) else tab.title?.take(28) ?: tab.address?.value?.take(28) ?: stringResource(R.string.flow_new_tab),
                                onClick = { if (rootActionAllowed()) { editing = false; browser.selectTab(tab.context.tabId) } },
                                modifier = Modifier.semantics { this.selected = tab.context.tabId == state.selectedTabId })
                            EsvButton(stringResource(R.string.flow_browserscreen_3), onClick = { if (rootActionAllowed()) browser.closeTab(tab.context.tabId) },
                                modifier = Modifier.semantics { contentDescription = closeTabDescription })
                        }
                    }
                    item { EsvButton(stringResource(R.string.flow_browserscreen_4), onClick = { if (rootActionAllowed()) { editing = false; browser.newTab() } }) }
                }
                Row(Modifier.fillMaxWidth().padding(visuals.spacingMedium), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    OutlinedTextField(value = address, onValueChange = { if (clearOwnerCurrent()) { editing = true; address = it.take(8192); addressClearToken = addressClearGate.invalidate() } },
                        modifier = Modifier.weight(1f).semantics { contentDescription = addressDescription },
                        textStyle = visuals.bodyTextStyle, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = if (privateMode) false else null),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                        trailingIcon = { InputClearButton(R.string.input_clear_address,
                            enabled = address.isNotEmpty() && addressClearGate.active && clearOwnerCurrent(),
                            onClear = ::clearAddressDraft) },
                        placeholder = { Text(stringResource(R.string.flow_browserscreen_5)) })
                    EsvButton(stringResource(R.string.flow_browserscreen_6), onClick = { go() }, enabled = address.isNotBlank())
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    if (browser.videoPresence == BrowserVideoPresence.PRESENT || saveScanning ||
                        saveRuntime?.selection != null || saveRuntime?.preparingStream == true || saveRuntime?.hlsPreparation != null) {
                        EsvButton(stringResource(if (saveScanning) R.string.flow_inspecting else R.string.flow_inspect), onClick = { if (rootActionAllowed()) onSave() },
                            enabled = browserInspectEnabled(selected?.loadState == BrowserLoadState.READY, saveScanning,
                                saveRuntime?.selection != null, saveRuntime?.preparingStream == true || saveRuntime?.hlsPreparation != null))
                    }
                    if (browser.videoPresence == BrowserVideoPresence.PRESENT && onRecording != null) {
                        EsvButton(stringResource(R.string.video_presence_record), onClick = { if (rootActionAllowed()) onRecording() })
                    }
                    EsvButton(stringResource(R.string.flow_browserscreen_7), onClick = { if (rootActionAllowed()) onSaveTasks() })
                    EsvButton(stringResource(R.string.flow_browserscreen_8), onClick = { if (rootActionAllowed()) onLibrary() })
                    EsvButton(stringResource(if (moreControls) R.string.flow_less else R.string.flow_more),
                        onClick = { if (rootActionAllowed()) moreControls = !moreControls })
                }
                if (browser.videoPresence == BrowserVideoPresence.PRESENT) {
                    EsvText(stringResource(R.string.video_presence_found), style = visuals.captionTextStyle,
                        modifier = Modifier.padding(horizontal = visuals.spacingMedium))
                }
                if (moreControls) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = visuals.spacingMedium),
                    horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvButton(stringResource(if (saveScanning) R.string.flow_inspecting else R.string.video_presence_manual),
                        onClick = { if (rootActionAllowed()) onSave() }, enabled = browserInspectEnabled(selected?.loadState == BrowserLoadState.READY,
                            saveScanning, saveRuntime?.selection != null,
                            saveRuntime?.preparingStream == true || saveRuntime?.hlsPreparation != null))
                    bookmarks?.let { runtime ->
                        EsvButton(stringResource(R.string.flow_browserscreen_9), onClick = {
                            if (!rootActionAllowed()) return@EsvButton
                            editing = false; focus.clearFocus()
                            val page = browser.acceptBookmarkPage(bookmarkPage)
                            if (page == null) runtime.stalePage() else runtime.add(page)
                        }, enabled = runtime.state.ready && !runtime.state.busy && bookmarkPage != null)
                        EsvButton(stringResource(R.string.flow_browserscreen_10), onClick = { if (rootActionAllowed()) { editing = false; focus.clearFocus(); closeHistory(); settingsOpen = false; bookmarksOpen = !bookmarksOpen } })
                    }
                    ordinaryHistory?.let { runtime ->
                        EsvButton(stringResource(R.string.history_open_panel), onClick = {
                            if (rootActionAllowed() && canSubmitNewTabInput(newTabInputGeneration) && browser.state.selectedTab?.incognito == false) {
                                historyOwner.invalidate()
                                editing = false; focus.clearFocus(); settingsOpen = false; bookmarksOpen = false
                                historyOpen = !historyOpen
                                if (historyOpen) runtime.load()
                            }
                        }, enabled = selected?.incognito == false)
                    }
                    EsvButton(stringResource(R.string.flow_browserscreen_11), onClick = { if (rootActionAllowed()) browser.back() }, enabled = selected?.canGoBack == true && browser.selectedView() != null)
                    EsvButton(stringResource(R.string.flow_browserscreen_12), onClick = { if (rootActionAllowed()) browser.forward() }, enabled = selected?.canGoForward == true && browser.selectedView() != null)
                    if (selected?.loadState == BrowserLoadState.LOADING) EsvButton(stringResource(R.string.flow_browserscreen_13), onClick = { if (rootActionAllowed()) browser.stopSelected() })
                    else EsvButton(stringResource(R.string.flow_browserscreen_14), onClick = { if (rootActionAllowed()) browser.reload() }, enabled = selected?.address != null)
                    EsvText(if (selected?.loadState == BrowserLoadState.LOADING) "${selected.progressPercent}%" else stringResource(if (privateMode) R.string.private_browser_tab else R.string.flow_ordinary_tab),
                        style = visuals.captionTextStyle, modifier = Modifier.align(Alignment.CenterVertically))
                }
                if (!bookmarksOpen) bookmarks?.state?.notice?.let { message ->
                    BookmarkNoticeRow(message, bookmarks::dismissNotice,
                        Modifier.background(visuals.colors.surface).padding(visuals.spacingMedium), canAct = ::rootActionAllowed)
                }
                saveRuntime?.let { com.easysavevideo.app.save.SaveFailureBrowserReminder(it) }
                saveNotice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(message, modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton(stringResource(R.string.flow_browserscreen_15), onClick = { if (rootActionAllowed()) onDismissSaveNotice() })
                    }
                }
                browser.notice?.let { message ->
                    Row(Modifier.fillMaxWidth().background(visuals.colors.surface).padding(visuals.spacingMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                        EsvText(browserNoticeText(message), modifier = Modifier.weight(1f), style = visuals.captionTextStyle)
                        EsvButton(stringResource(R.string.flow_browserscreen_15), onClick = { if (rootActionAllowed()) browser.dismissNotice() })
                    }
                }
                // Fixed sibling slot: the existing AndroidView below is never moved to another branch.
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = visuals.spacingSmall)) {
                        val revision = browser.viewRevision
                        val view = browser.selectedView()
                        val blockedPage = selected?.loadState in setOf(BrowserLoadState.FAILED, BrowserLoadState.CANCELLED, BrowserLoadState.RENDERER_UNAVAILABLE)
                        if (view != null && !blockedPage) key(selected?.context?.tabId, revision, view) {
                            AndroidView(factory = { context ->
                                (view.parent as? ViewGroup)?.removeView(view)
                                // Detach revokes the fixed View binding. Rebind the existing exact
                                // covered claim before attachment; UI never captures a new claim.
                                if (!privateMode || browser.beforePrivateViewAttachment(view)) view else View(context)
                            }, modifier = Modifier.fillMaxSize())
                        }
                        val showEmpty = view == null || blockedPage
                        if (showEmpty) Column(Modifier.fillMaxSize().background(visuals.colors.bg).padding(visuals.spacingLarge)
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            EsvText(if (!browser.platformAvailable) stringResource(R.string.flow_web_unavailable) else if (selected?.loadState == BrowserLoadState.CANCELLED) stringResource(R.string.flow_web_stopped) else if (selected?.error != null) stringResource(browserErrorResource(selected.error!!.code)) else stringResource(R.string.flow_browse_empty),
                                style = visuals.titleTextStyle)
                            Spacer(Modifier.height(visuals.spacingMedium))
                            if (selected?.address == null && onVideoSearchDraft != null) {
                                OutlinedTextField(value = videoInput, onValueChange = { if (clearOwnerCurrent() && NewTabInputPolicy.canSubmit(selected, browser.state.selectedTab, true)) { videoInput = it.take(8193); videoInputError = false; videoClearToken = videoClearGate.invalidate() } },
                                    modifier = Modifier.fillMaxWidth(), textStyle = visuals.bodyTextStyle, singleLine = true,
                                    label = { Text(stringResource(R.string.new_tab_video_input)) }, isError = videoInputError,
                                    trailingIcon = { InputClearButton(R.string.input_clear_new_tab,
                                        enabled = videoInput.isNotEmpty() && videoClearGate.active && clearOwnerCurrent() &&
                                            NewTabInputPolicy.canSubmit(selected, browser.state.selectedTab, true),
                                        onClear = ::clearVideoDraft) },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = if (privateMode) false else null),
                                    keyboardActions = KeyboardActions(onGo = { submitVideoInput() }))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                                    NewTabInputMode.entries.filter { !privateMode || it != NewTabInputMode.VIDEO }.forEach { mode ->
                                        EsvButton(stringResource(when (mode) {
                                            NewTabInputMode.AUTO -> R.string.new_tab_auto
                                            NewTabInputMode.ADDRESS -> R.string.new_tab_address
                                            NewTabInputMode.VIDEO -> R.string.new_tab_video
                                        }), onClick = { if (clearOwnerCurrent()) { videoMode = mode; videoInputError = false; videoClearToken = videoClearGate.invalidate() } },
                                            modifier = Modifier.semantics { this.selected = videoMode == mode }
                                                .then(if (videoMode == mode) Modifier.border(2.dp, visuals.colors.accent,
                                                    RoundedCornerShape(visuals.buttonCorner)) else Modifier))
                                    }
                                }
                                EsvText(stringResource(R.string.new_tab_draft_help), style = visuals.captionTextStyle)
                                if (videoInputError) EsvText(stringResource(R.string.new_tab_invalid), color = visuals.colors.error)
                                EsvButton(stringResource(if (NewTabInputPolicy.interpret(videoInput, videoMode) is NewTabInput.Address)
                                    R.string.new_tab_open_address else R.string.new_tab_continue),
                                    onClick = { submitVideoInput() }, enabled = videoInput.isNotBlank())
                                Spacer(Modifier.height(visuals.spacingMedium))
                            }
                            EsvText(stringResource(R.string.flow_browserscreen_18), color = visuals.colors.muted)
                            Spacer(Modifier.height(visuals.spacingMedium))
                            if (selected?.address != null) EsvButton(stringResource(R.string.flow_browserscreen_19), onClick = { if (rootActionAllowed()) browser.reload() })
                            if (!browser.platformAvailable && selected?.address != null) EsvButton(stringResource(R.string.flow_browserscreen_20), onClick = { if (rootActionAllowed()) openExternal() })
                            Spacer(Modifier.height(visuals.spacingLarge))
                            EsvText(stringResource(R.string.flow_browserscreen_21), style = visuals.captionTextStyle, color = visuals.colors.muted)
                        }
                    }
                    if (inspector.sidePane) inspectorPanel(Modifier.width(pane.sideWidthDp.dp).fillMaxHeight(), SaveSelectionPresentation.INLINE)
                    if (bookmarksOpen && wide && bookmarks != null) Box(Modifier.width(360.dp).fillMaxHeight().background(visuals.colors.surface)) {
                        BookmarkPanel(bookmarks, bookmarkListState, onOpen = { item ->
                            if (rootActionAllowed()) bookmarks.addressForOpen(item)?.let { target ->
                                bookmarksOpen = false; editing = false; focus.clearFocus(); browser.navigate(target)
                            }
                        }, onClose = { if (rootActionAllowed()) { bookmarksOpen = false; focus.clearFocus() } }, canAct = ::rootActionAllowed)
                    }
                    if (historyOpen && wide && ordinaryHistory != null) Box(Modifier.width(360.dp).fillMaxHeight().background(visuals.colors.surface)) {
                        HistoryPanel(ordinaryHistory, historyListState, onOpen = ::openHistory,
                            onClose = { closeHistory(); focus.clearFocus() }, canAct = ::historyCanAct)
                    }
                    if (settingsOpen && wide) Box(Modifier.width(360.dp).fillMaxHeight().background(visuals.colors.surface)) {
                        SettingsPanel(settings, onSettings, browser, onClose = { settingsOpen = false }, clearCookies = { confirmCookies = true },
                            onManageProviders = { settingsOpen = false; onManageProviders() }, destinations = destinations, editable = settingsEditable)
                    }
                }
            }
            if (bookmarksOpen && !wide && bookmarks != null) {
                val request = bookmarksRequest.identity!!
                EsvDialog(request, { bookmarksRequest.identity === request && modalOwnerCurrent() },
                    onDismiss = { bookmarksOpen = false; focus.clearFocus() }) {
                    val admission = LocalPrivateInputAdmission.current
                    BookmarkPanel(bookmarks, bookmarkListState, onOpen = { item ->
                        if (admission()) bookmarks.addressForOpen(item)?.let { target ->
                            bookmarksOpen = false; editing = false; focus.clearFocus(); browser.navigate(target)
                        }
                    }, onClose = { if (admission()) { bookmarksOpen = false; focus.clearFocus() } }, canAct = admission)
                }
            }
            if (historyOpen && !wide && ordinaryHistory != null) {
                val request = historyRequest.identity!!
                EsvDialog(request, { historyRequest.identity === request && modalOwnerCurrent() },
                    onDismiss = { closeHistory(); focus.clearFocus() }) {
                    val admission = LocalPrivateInputAdmission.current
                    HistoryPanel(ordinaryHistory, historyListState, onOpen = { if (admission()) openHistory(it) },
                        onClose = { if (admission()) { closeHistory(); focus.clearFocus() } },
                        canAct = { admission() && historyCanAct() })
                }
            }
            if (settingsOpen && !wide) {
                val request = settingsRequest.identity!!
                EsvDialog(request, { settingsRequest.identity === request && modalOwnerCurrent() },
                    onDismiss = { settingsOpen = false }) {
                    SettingsPanel(settings, onSettings, browser, onClose = { settingsOpen = false }, clearCookies = { confirmCookies = true },
                        onManageProviders = { settingsOpen = false; onManageProviders() }, destinations = destinations, editable = settingsEditable)
                }
            }
            if (inspector.modal) saveRuntime?.let { runtime ->
                if (runtime.preparingStream || runtime.hlsPreparation != null) SaveSelectionDialog(runtime, browser)
                else runtime.currentSelectionPresentationIdentity?.let { request ->
                    EsvAppDialog(request, { runtime.currentSelectionPresentationIdentity === request }, runtime::dismissSelection) {
                        inspectorPanel(Modifier.heightIn(max = maxHeight.coerceAtMost(680.dp)), SaveSelectionPresentation.FLOATING)
                    }
                }
            }
            browser.fullScreenView?.let { view ->
                Box(Modifier.fillMaxSize().background(visuals.colors.bg)) {
                    AndroidView(factory = { context ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        if (!privateMode || browser.beforePrivateViewAttachment(view)) view else View(context)
                    }, modifier = Modifier.fillMaxSize())
                    EsvButton(stringResource(R.string.flow_browserscreen_22), onClick = { if (rootActionAllowed() && browser.fullScreenView === view) browser.hideFullscreen() }, modifier = Modifier.align(Alignment.TopEnd).padding(visuals.spacingMedium))
                }
            }
            // This is the actual AndroidView-containing subcomposition, not Main's outer apply.
            // Its callback carries the exact route entry, presentation owner and apply ticket.
            SideEffect { onPresentationApplied() }
        }
    }
    if (interactiveJsDialogs) browser.jsDialog?.let { request ->
        BrowserJsDialog(request, browser::finishJsDialog, isRequestCurrent = { browser.jsDialog === request && modalOwnerCurrent() })
    }
    if (confirmCookies) {
        val request = cookiesRequest.identity!!
        EsvDialog(request, { cookiesRequest.identity === request && modalOwnerCurrent() }, onDismiss = { confirmCookies = false }) {
            val admission = LocalPrivateInputAdmission.current
            Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
                EsvText(stringResource(R.string.flow_browserscreen_23), style = visuals.titleTextStyle)
                EsvText(stringResource(R.string.flow_browserscreen_24))
                EsvButton(stringResource(R.string.flow_browserscreen_25), onClick = { if (admission()) { confirmCookies = false; browser.clearCookies(feedbackCurrent = ::rootActionAllowed) {} } })
                EsvButton(stringResource(R.string.flow_browserscreen_26), onClick = { if (admission()) confirmCookies = false })
            }
        }
    }
    filePrompt?.let { choice ->
        EsvDialog(choice, { isFilePromptCurrent(choice) && modalOwnerCurrent() }, onDismiss = { onFileCancel(choice) }) {
            val admission = LocalPrivateInputAdmission.current
            Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
                EsvText(stringResource(R.string.flow_browserscreen_27), style = visuals.titleTextStyle)
                EsvText(stringResource(R.string.browser_copy_origin, choice.origin))
                EsvText(stringResource(R.string.flow_browserscreen_29))
                EsvButton(stringResource(R.string.flow_browserscreen_30), onClick = { if (admission()) onFileConfirm(choice) })
                EsvButton(stringResource(R.string.flow_browserscreen_26), onClick = { if (admission()) onFileCancel(choice) })
            }
        }
    }
    if (confirmPrivateExit) {
        val request = privateExitRequest.identity!!
        EsvDialog(request, { privateExitRequest.identity === request && modalOwnerCurrent() }, onDismiss = { confirmPrivateExit = false }) {
            val admission = LocalPrivateInputAdmission.current
            // The dialog has its own generation; opening it correctly revoked the main editor.
            fun canExit() = admission() && privateModeActionEnvironment(checkMainGeneration = false)
            Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingMedium)) {
                EsvText(stringResource(R.string.private_browser_exit_title), style = visuals.titleTextStyle)
                EsvText(stringResource(R.string.private_browser_exit_body))
                EsvButton(stringResource(R.string.private_browser_exit), onClick = {
                    if (canExit()) { confirmPrivateExit = false; prepareModeChange(); browser.exitPrivateSession() }
                }, enabled = canExit())
                EsvButton(stringResource(R.string.flow_browserscreen_26), onClick = { if (admission()) confirmPrivateExit = false })
            }
        }
    }
    }
}

internal fun canNavigateBrowserAddress(expected: BrowserTab?, current: BrowserTab?,
    expectedRevision: Long, currentRevision: Long, interactive: Boolean): Boolean =
    canClearBrowserDraft(expected, current, expectedRevision, currentRevision, interactive) &&
        expected?.incognito == current?.incognito

/** A request to the installed keyboard, not a guarantee that third-party keyboards obey it. */
internal fun privateBrowserImeFlags(existing: Int, privateMode: Boolean): Int =
    if (privateMode) privateProfileImeFlags(existing) else existing

/** A fresh process-local request even if a Boolean closes/reopens before recomposition. */
internal class BrowserDialogRequestState(initiallyOpen: Boolean = false) {
    var identity: Any? by mutableStateOf(if (initiallyOpen) Any() else null)
        private set
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = identity != null
    operator fun setValue(thisRef: Any?, property: KProperty<*>, open: Boolean) {
        identity = if (open) Any() else null
    }
    companion object {
        val Saver = Saver<BrowserDialogRequestState, Boolean>(
            save = { it.identity != null }, restore = { BrowserDialogRequestState(it) })
    }
}

/** Exact UI callback owner; disposal is monotonic even if the underlying request remains pending. */
internal class EsvDialogRequestOwner(private val stillCurrent: () -> Boolean) {
    private var active = true
    fun isCurrent(): Boolean = active && stillCurrent()
    fun close() { active = false }
}
private class EsvDialogReferenceKey(private val reference: Any) {
    override fun equals(other: Any?): Boolean = other is EsvDialogReferenceKey && reference === other.reference
    override fun hashCode(): Int = System.identityHashCode(reference)
}

/** One branch point for the finite app-owned dialogs. Ordinary Compose Dialog stays unchanged. */
@Composable
internal fun EsvAppDialog(
    requestIdentity: Any,
    isRequestCurrent: () -> Boolean,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val privateMode = LocalPrivatePresentation.current
    val owner = remember(privateMode, EsvDialogReferenceKey(requestIdentity)) { EsvDialogRequestOwner(isRequestCurrent) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val guardedContent: @Composable () -> Unit = {
        // Read inside the actual dialog composition: the private host installs its own root here.
        val inputAdmission = LocalPrivateInputAdmission.current
        val admission = { owner.isCurrent() && inputAdmission() }
        CompositionLocalProvider(LocalPrivateInputAdmission provides admission, content = content)
    }
    val dismiss = { if (owner.isCurrent()) onDismissRequest() }
    if (privateMode) {
        // Missing private environment fails closed in PrivateAppDialog; never fall back to raw.
        PrivateAppDialog(requestIdentity, dismiss, properties, owner::isCurrent, guardedContent)
    } else {
        Dialog(onDismissRequest = dismiss, properties = properties, content = guardedContent)
    }
}

@Composable private fun EsvDialog(requestIdentity: Any, isRequestCurrent: () -> Boolean,
    onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val v = LocalEsvVisuals.current
    EsvAppDialog(requestIdentity, isRequestCurrent, onDismiss) {
        Box(Modifier.fillMaxWidth().heightIn(max = 680.dp).background(v.colors.elevated, RoundedCornerShape(v.sheetCorner)).padding(v.spacingLarge)) { content() }
    }
}
@Composable private fun SettingsPanel(settings: AppSettings, update: (SettingsEdit.BrowserEdit) -> Unit, browser: SystemBrowserHost,
    onClose: () -> Unit, clearCookies: () -> Unit, onManageProviders: () -> Unit, destinations: AndroidOrdinaryDestinationRuntime?, editable: Boolean) {
    val v = LocalEsvVisuals.current
    val admission = LocalPrivateInputAdmission.current
    var noticesOpen by remember { mutableStateOf(false) }
    if (noticesOpen) {
        com.easysavevideo.app.licenses.ThirdPartyNotices { if (admission()) noticesOpen = false }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(v.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(v.spacingMedium)) {
        EsvText(stringResource(R.string.flow_browserscreen_32), style = v.titleTextStyle)
        EsvButton(stringResource(R.string.flow_browserscreen_33), onClick = { if (admission()) onClose() })
        key(admission) {
            // Its internal latest-value adapter must not retarget an old generation's actions.
            BrowserSettingsDetail(settings.browser, editable && admission()) { if (admission()) update(it) }
        }
        EsvButton(stringResource(R.string.flow_browserscreen_41), onClick = { if (admission()) clearCookies() })
        EsvButton(stringResource(R.string.flow_browserscreen_42), onClick = { if (admission()) browser.clearCache() })
        EsvText(stringResource(R.string.flow_browserscreen_43), style = v.captionTextStyle)
        destinations?.let { OrdinaryDestinationSettings(it, canAct = admission) }
        EsvButton(stringResource(R.string.flow_browserscreen_44), onClick = { if (admission()) onManageProviders() })
        EsvText(stringResource(R.string.flow_browserscreen_45), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.flow_browserscreen_46), onClick = { if (admission()) noticesOpen = true })
        DevelopmentEntitlementPanel(canAct = admission)
    }
}
@Composable private fun Toggle(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    EsvButton(stringResource(R.string.browser_copy_toggle, label,
        stringResource(if (enabled) R.string.browser_copy_on else R.string.browser_copy_off)), onClick = { onChange(!enabled) }, modifier = Modifier.fillMaxWidth())
}
internal fun browserErrorResource(code: ErrorCode): Int = when (code) {
    ErrorCode.TLS_REJECTED -> R.string.browser_copy_error_tls
    ErrorCode.RENDERER_GONE -> R.string.browser_copy_error_renderer
    ErrorCode.DEADLINE_EXCEEDED, ErrorCode.NETWORK_TIMEOUT -> R.string.browser_copy_error_timeout
    ErrorCode.NETWORK_UNAVAILABLE -> R.string.browser_copy_error_network
    ErrorCode.HTTP_FAILURE -> R.string.browser_copy_error_http
    else -> R.string.browser_copy_error_unavailable
}

