package com.easysavevideo.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.browser.bookmarks.AndroidBookmarkRuntime
import com.easysavevideo.app.storage.*
import com.easysavevideo.app.theme.EsvThemeHost
import com.easysavevideo.app.ui.BrowserScreen
import com.easysavevideo.app.ui.DiscoveryScreen
import com.easysavevideo.app.ui.ProviderManagementScreen
import com.easysavevideo.app.save.AndroidSaveRuntime
import com.easysavevideo.app.library.destination.OrdinaryDestinationDialog
import com.easysavevideo.app.library.destination.AndroidFolderPickerHost
import com.easysavevideo.app.save.SaveTaskScreen
import com.easysavevideo.app.save.SaveSelectionDialog
import com.easysavevideo.app.save.VerifiedMediaPreview
import com.easysavevideo.app.save.LibraryMediaPreview
import com.easysavevideo.app.library.AndroidLibraryRuntime
import com.easysavevideo.app.editor.ui.EditorScreen
import com.easysavevideo.app.library.LibraryScreen
import com.easysavevideo.app.library.LibraryUnavailableScreen
import com.easysavevideo.app.library.LibraryExportDialog
import com.easysavevideo.app.library.LibraryExportDestination
import com.easysavevideo.app.library.exportSelectionIsCurrent
import com.easysavevideo.app.library.thumbnail.AndroidLibraryThumbnails
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.MediaLifecycle
import com.easysavevideo.app.recording.RecordingConsentController
import com.easysavevideo.app.recording.RecordingScreen
import com.easysavevideo.app.recording.RecordingPreviewHostDraw
import com.easysavevideo.app.recording.ui.AuthorizedRecordingRegionDialog
import com.easysavevideo.app.recording.nativeregion.DefaultDisplayCaptureGeometry
import com.easysavevideo.app.recording.ui.RecordingRegionSetup
import com.easysavevideo.app.recording.ui.RecordingSelectionMode
import com.easysavevideo.save.MediaCommitReceipt
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.app.discovery.AndroidDiscoveryController
import com.easysavevideo.app.discovery.CommonsSourceRegistration
import com.easysavevideo.app.discovery.ProviderManagementController
import com.easysavevideo.app.discovery.ProviderManagementState
import com.easysavevideo.discovery.SeenEvent
import com.easysavevideo.core.model.*
import com.easysavevideo.core.domain.EntitlementManager
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.app.entitlements.BuildEntitlements

class MainActivity : ComponentActivity() {
    private lateinit var browser: SystemBrowserHost
    private lateinit var bookmarks: AndroidBookmarkRuntime
    private lateinit var persistence: BrowserPersistence
    private lateinit var errorSink: AndroidErrorSink
    private lateinit var saves: AndroidSaveRuntime
    private lateinit var folderPickerHost: AndroidFolderPickerHost
    private var discoveryPrioritySubscription: AutoCloseable? = null
    private var showingSaveTasks by mutableStateOf(false)
    private var showingLibrary by mutableStateOf(false)
    private var showingEditor by mutableStateOf(false)
    private var libraryPreviewReference by mutableStateOf<MediaReadRef?>(null)
    private var libraryExportReference by mutableStateOf<MediaReadRef?>(null)
    private var pendingLibraryDocumentResult: Pair<Int, Intent?>? = null
    private var showingRecording by mutableStateOf(false)
    private var recordingSelectionMode by mutableStateOf(RecordingSelectionMode.BASIC)
    private var previewReceipt by mutableStateOf<MediaCommitReceipt?>(null)
    private lateinit var recordingConsent: RecordingConsentController
    private lateinit var discovery: AndroidDiscoveryController
    private var showingDiscovery by mutableStateOf(true)
    private var showingProviderManagement by mutableStateOf(false)
    private lateinit var providerManagement: ProviderManagementController
    private var providerManagementState by mutableStateOf(ProviderManagementState())
    private var isForeground by mutableStateOf(false)
    private var hasFocusedWindow by mutableStateOf(false)
    private var settings by mutableStateOf(AppSettings())
    private var filePrompt by mutableStateOf<SystemBrowserHost.FileChoice?>(null)
    private var choosing: SystemBrowserHost.FileChoice? = null
    private var settingsChanged = false
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val choice = choosing ?: return@registerForActivityResult
        choosing = null
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK) buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip -> repeat(clip.itemCount.coerceAtMost(8)) { add(clip.getItemAt(it).uri) } }
        }.distinct() else null
        browser.finishFiles(choice, uris)
    }
    private val libraryDocumentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val exports = if (::saves.isInitialized) saves.libraryExports else null
        if (exports == null) pendingLibraryDocumentResult = result.resultCode to result.data
        else exports.onCreateDocumentResult(result.resultCode, result.data)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showingDiscovery = savedInstanceState?.getBoolean("showing_discovery", true) ?: true
        showingProviderManagement = savedInstanceState?.getBoolean("showing_provider_management", false) ?: false
        showingLibrary = savedInstanceState?.getBoolean("showing_library", false) ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        errorSink = AndroidErrorSink(applicationContext)
        bookmarks = AndroidBookmarkRuntime.get(applicationContext)
        discovery = AndroidDiscoveryController(applicationContext, errorSink)
        val managementMain = Handler(Looper.getMainLooper())
        providerManagement = ProviderManagementController(CommonsSourceRegistration.managementBackend(applicationContext),
            CommonsSourceRegistration.managementWorker, { work -> managementMain.post { work() } },
            { providerManagementState = it })
        saves = AndroidSaveRuntime.get(applicationContext)
        folderPickerHost = AndroidFolderPickerHost(this)
        discoveryPrioritySubscription = saves.addMediaTaskActivityListener { activity ->
            discovery.setTaskPriority(activity.saveActive, activity.recordingActive)
        }
        showingRecording = savedInstanceState?.getBoolean("showing_recording", false) ?: (saves.currentRecordingId != null)
        recordingConsent = RecordingConsentController(this, saves::receiveProjectionGrant, saves::abandonRecordingRequest,
            saves::isRecordingRequestAllowed, saves::recordingAudioPermissionDenied, saves::requiresDefaultDisplayRecording)
        persistence = BrowserPersistence(applicationContext, errorSink) {
            if (::browser.isInitialized) browser.showNotice("设置或标签地址保存未完成，下次启动可能仍为旧状态。请检查存储空间。")
        }
        val entitlements = EntitlementManager(BuildEntitlements.source)
        val gate = FeatureGate()
        browser = SystemBrowserHost(this, errorSink,
            browserCommandAllowed = { gate.evaluate(FeatureRequest(Feature.BROWSER), entitlements.snapshot(),
                PlatformCapabilities(setOf(Feature.BROWSER)), BuildFeaturePolicy(), System.currentTimeMillis()).allowed },
            onCheckpoint = { checkpoint -> if (::browser.isInitialized && browser.hasUserInteracted) persistence.checkpoint(checkpoint, settings.browser.restoreTabs) },
            onChooseFiles = { choice -> filePrompt = choice },
            onDiscoverySaveCompleted = discovery::completeOriginSave)
        setContent {
            EsvThemeHost {
                val destinations = saves.destinations
                DisposableEffect(destinations) {
                    destinations?.attachFolderPicker(folderPickerHost)
                    onDispose { destinations?.detachFolderPicker(folderPickerHost) }
                }
                val dark = isSystemInDarkTheme()
                SideEffect {
                    val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightBars, lightBars)
                }
                val fullscreen = browser.fullScreenView != null
                DisposableEffect(fullscreen) {
                    if (fullscreen) {
                        window.insetsController?.hide(WindowInsets.Type.systemBars())
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.insetsController?.show(WindowInsets.Type.systemBars())
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose { window.insetsController?.show(WindowInsets.Type.systemBars()); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                val repository = saves.libraryRepository
                val library = remember(repository) { repository?.let(::AndroidLibraryRuntime) }
                val exports = saves.libraryExports
                val editor = saves.editor
                val previewRuntime = saves.segmentRecording
                SideEffect { previewRuntime?.setPreviewHostFocused(hasFocusedWindow) }
                val previewHost = previewRuntime?.previewActive == true
                val authorizedPreviewFrame = previewRuntime?.previewFrame
                LaunchedEffect(previewRuntime?.previewReturnToControls) {
                    if (previewRuntime?.previewReturnToControls == true) {
                        showingRecording = true; showingSaveTasks = false
                        previewRuntime.acknowledgePreviewReturn()
                    }
                }
                RecordingPreviewHostDraw(window.decorView, previewRuntime?.previewHostRequest,
                    enabled = previewHost && isForeground && hasFocusedWindow && authorizedPreviewFrame == null,
                    onDrawn = { request ->
                        if (previewRuntime != null && isForeground && hasFocusedWindow && previewRuntime.previewActive && previewRuntime.previewFrame == null) {
                            previewRuntime.previewHostDrawn(request)?.let { choice ->
                                recordingConsent.begin(request.sessionId, choice == com.easysavevideo.recording.RecordingAudioChoice.INTERNAL)
                            }
                        }
                    }, onUnavailable = { previewRuntime?.cancelPreview() })
                val recordingConfiguration = LocalConfiguration.current
                val regionGeometry = remember(recordingConfiguration, isForeground, showingRecording) {
                    if (showingRecording && isForeground) DefaultDisplayCaptureGeometry.read(this@MainActivity) else null
                }
                var pendingRecordedEditorReference by remember { mutableStateOf<MediaReadRef?>(null) }
                var recordedEditorNotice by remember { mutableStateOf<String?>(null) }
                val activeRecordingId = saves.currentRecordingId
                val segmentPresentation = if (activeRecordingId == null || saves.segmentRecording?.owns(activeRecordingId) == true)
                    saves.segmentRecording?.presentation else null
                val recordedOutputReference = saves.segmentRecording?.completedReference
                LaunchedEffect(pendingRecordedEditorReference, recordedOutputReference, repository, editor, isForeground, showingRecording) {
                    val reference = pendingRecordedEditorReference ?: return@LaunchedEffect
                    if (!isForeground || !showingRecording || repository == null || editor == null) {
                        pendingRecordedEditorReference = null
                        return@LaunchedEffect
                    }
                    if (recordedOutputReference != reference) {
                        pendingRecordedEditorReference = null
                        recordedEditorNotice = "录制结果已变化，请重新选择已保存视频。"
                        return@LaunchedEffect
                    }
                    // Resolve the published identity off the UI thread. Never manufacture a catalog item
                    // from a closed private segment or silently switch to a newer media revision.
                    val item = withContext(Dispatchers.IO) {
                        try { repository.item(reference.itemId) } catch (_: RuntimeException) { null }
                    }
                    if (pendingRecordedEditorReference != reference || !isForeground || !showingRecording) return@LaunchedEffect
                    pendingRecordedEditorReference = null
                    if (saves.segmentRecording?.completedReference != reference) {
                        recordedEditorNotice = "录制结果已变化，请重新选择已保存视频。"
                        return@LaunchedEffect
                    }
                    if (item == null || item.lifecycle != MediaLifecycle.READY || item.readRef() != reference) {
                        recordedEditorNotice = "已保存视频的准确版本暂不可用于编辑，请在媒体库重新选择。"
                    } else if (editor.state.busy || editor.state.phase == com.easysavevideo.app.editor.ui.EditorPhase.BLOCKED) {
                        recordedEditorNotice = "编辑器仍在处理或等待恢复，请稍后从媒体库打开已保存视频。"
                    } else {
                        val available = listOf(item) + (library?.state?.items ?: emptyList()).filter { it.itemId != item.itemId }
                        editor.open(item, available)
                        if (editor.state.draft.segments.none { it.reference == reference }) {
                            recordedEditorNotice = "编辑器草稿未能添加此视频，请先在媒体库打开编辑器调整现有片段。"
                            return@LaunchedEffect
                        }
                        recordedEditorNotice = null
                        libraryExportReference = null
                        showingRecording = false
                        showingSaveTasks = false
                        showingLibrary = true
                        showingEditor = true
                    }
                }
                LaunchedEffect(editor, isForeground, showingEditor, libraryPreviewReference) {
                    editor?.setForeground(isForeground && showingEditor && libraryPreviewReference == null)
                }
                LaunchedEffect(exports) {
                    if (exports != null) pendingLibraryDocumentResult?.let { result ->
                        pendingLibraryDocumentResult = null
                        exports.onCreateDocumentResult(result.first, result.second)
                    }
                }
                DisposableEffect(library) { onDispose { library?.close() } }
                LaunchedEffect(library, isForeground, showingLibrary, showingEditor, libraryPreviewReference) {
                    library?.setForeground(isForeground && showingLibrary && !showingEditor && libraryPreviewReference == null)
                }
                LaunchedEffect(saves.ready, showingSaveTasks, showingLibrary) {
                    if (showingSaveTasks && !showingLibrary) saves.refreshLibraryDisposition()
                }
                LaunchedEffect(isForeground, showingDiscovery, showingProviderManagement, showingLibrary, showingSaveTasks, showingRecording, showingEditor, previewReceipt, libraryPreviewReference) {
                    discovery.setVisibility(isForeground, showingDiscovery && !showingProviderManagement && !showingLibrary && !showingSaveTasks &&
                        !showingRecording && !showingEditor && previewReceipt == null && libraryPreviewReference == null)
                }
                LaunchedEffect(isForeground, showingProviderManagement, showingLibrary, showingSaveTasks, showingRecording,
                    showingEditor, previewReceipt, libraryPreviewReference, previewHost) {
                    providerManagement.setActive(isForeground && showingProviderManagement && !showingLibrary &&
                        !showingSaveTasks && !showingRecording && !showingEditor && previewReceipt == null &&
                        libraryPreviewReference == null && !previewHost)
                }
                val libraryReference = libraryPreviewReference
                val receipt = previewReceipt
                if (previewHost) BrowserScreen(browser, settings,
                    onSettings = { /* The bounded preview transition keeps the current browser host. */ },
                    filePrompt = null, onFileCancel = {}, onFileConfirm = {}, openExternal = {},
                    onDiscovery = {}, onManageProviders = {}, onSave = {}, onSaveTasks = {}, onLibrary = {},
                    saveScanning = false, saveNotice = null, onDismissSaveNotice = {})
                else if (receipt != null) VerifiedMediaPreview(receipt, isForeground, saves::openPublishedMedia,
                    onBack = { previewReceipt = null }, onReleaseUnconfirmed = saves::previewReleaseUnconfirmed)
                else if (libraryReference != null) LibraryMediaPreview(libraryReference, isForeground, saves::openLibraryMedia,
                    onBack = { libraryPreviewReference = null }, onReleaseUnconfirmed = saves::previewReleaseUnconfirmed)
                else if (showingEditor && editor != null) EditorScreen(editor.state,
                    onDraftChange = editor::changeDraft, onSubmit = editor::submit, onCancel = editor::cancel,
                    onBack = { editor.cancel(); showingEditor = false; library?.refresh() },
                    onPreview = { libraryPreviewReference = it })
                else if (showingLibrary && library != null) {
                    val thumbnails = remember(library) { AndroidLibraryThumbnails(saves::openLibraryMedia, saves::retainLibraryNativeReader) }
                    DisposableEffect(thumbnails) { onDispose { thumbnails.close() } }
                    LibraryScreen(library,
                        onBack = { libraryExportReference = null; showingLibrary = false },
                        onPreview = { libraryPreviewReference = it },
                        onEdit = if (editor == null) null else { item ->
                            editor.open(item, library.state.items); showingEditor = true; libraryExportReference = null
                        },
                        onExport = if (exports == null) null else { ref -> libraryExportReference = ref },
                        exports = exports, thumbnails = thumbnails)
                    val exportReference = libraryExportReference
                    val exportItem = library.state.items.firstOrNull { it.lifecycle == com.easysavevideo.library.MediaLifecycle.READY && it.readRef() == exportReference }
                    if (exports != null && exportReference != null && exportItem != null && isForeground) {
                        LibraryExportDialog(exportItem, enabled = !exports.state.busy && library.state.ready && !library.state.busy && !library.state.loading &&
                            exportSelectionIsCurrent(exportReference, library.state.items, library.state.foreground),
                            onChoose = { destination ->
                                if (libraryExportReference == exportReference && !exports.state.busy && library.state.ready && !library.state.busy && !library.state.loading &&
                                    exportSelectionIsCurrent(exportReference, library.state.items, library.state.foreground)) {
                                    libraryExportReference = null
                                    when (destination) {
                                        LibraryExportDestination.GALLERY -> exports.exportToMediaStore(exportReference)
                                        LibraryExportDestination.FILES -> exports.createDocument(exportReference) { intent ->
                                            check(isForeground && showingLibrary && libraryPreviewReference == null && !isDestroyed && !isFinishing) { "EXPORT_UI_UNAVAILABLE" }
                                            libraryDocumentPicker.launch(intent)
                                        }
                                        LibraryExportDestination.SHARE -> exports.share(exportReference) { intent ->
                                            check(isForeground && showingLibrary && libraryPreviewReference == null && !isDestroyed && !isFinishing) { "EXPORT_UI_UNAVAILABLE" }
                                            startActivity(intent)
                                        }
                                    }
                                }
                            }, onDismiss = { libraryExportReference = null })
                    }
                }
                else if (showingLibrary) LibraryUnavailableScreen(onBack = { showingLibrary = false })
                else if (showingRecording) RecordingScreen(saves.recordingSnapshot,
                    initialSelectionMode = recordingSelectionMode,
                    onSelectionModeChange = { recordingSelectionMode = it },
                    onCreationSelectionChanged = { saves.destinations?.cancelSelection() },
                    effectiveDurationUs = saves.recordingSnapshot?.effectiveDurationUs,
                    consent = recordingConsent, canStart = saves.ready && saves.canSave(TaskMethod.RECORD) && saves.currentRecordingId == null,
                    canPauseResume = saves.canPauseRecording(),
                    onStart = { choice ->
                        recordedEditorNotice = null
                        val bounds = windowManager.maximumWindowMetrics.bounds
                        val density = resources.configuration.densityDpi
                        saves.destinations?.select(isCurrent = { isForeground && showingRecording && !isDestroyed && !isFinishing &&
                            recordingSelectionMode == RecordingSelectionMode.BASIC && saves.currentRecordingId == null &&
                            windowManager.maximumWindowMetrics.bounds == bounds && resources.configuration.densityDpi == density }) { admission ->
                            saves.prepareRecording(choice, bounds.width(), bounds.height(), density, admission)?.let {
                                recordingConsent.begin(it, choice == com.easysavevideo.recording.RecordingAudioChoice.INTERNAL)
                            }
                        }
                        null
                    },
                    onPause = { saves.currentRecordingId?.let(saves::pauseRecording) },
                    onResume = { saves.currentRecordingId?.let(saves::resumeRecording) },
                    onStopAndSave = { saves.currentRecordingId?.let(saves::stopRecording) },
                    onDiscard = { recordingConsent.cancel(); saves.currentRecordingId?.let(saves::cancel) },
                    onBack = {
                        pendingRecordedEditorReference = null; recordedEditorNotice = null
                        showingRecording = false; showingSaveTasks = true
                    }, notice = recordedEditorNotice ?: saves.notice,
                    regionSetup = RecordingRegionSetup(regionGeometry, DefaultDisplayCaptureGeometry.scopeEvidence(),
                        canStart = saves.ready && saves.segmentRecording?.ready == true && saves.canRegionRecording() && saves.currentRecordingId == null,
                        unavailableReason = if (regionGeometry == null) "当前默认显示屏几何不可用，请返回前台后重新选择。"
                            else if (saves.segmentRecording?.ready != true) "高级录制存储与恢复检查尚未就绪。" else null),
                    onStartRegion = { choice, recipe ->
                        recordedEditorNotice = null
                        saves.destinations?.select(isCurrent = { isForeground && showingRecording && !isDestroyed && !isFinishing &&
                            recordingSelectionMode == RecordingSelectionMode.PROPORTIONAL && saves.currentRecordingId == null &&
                            DefaultDisplayCaptureGeometry.read(this@MainActivity) == recipe.source &&
                            DefaultDisplayCaptureGeometry.scopeEvidence() == recipe.scopeEvidence }) { admission ->
                            saves.prepareRegionRecording(choice, recipe, admission)?.let {
                                recordingConsent.begin(it, choice == com.easysavevideo.recording.RecordingAudioChoice.INTERNAL)
                            }
                        }
                        null
                    },
                    onStartAuthorizedPreview = { choice ->
                        recordedEditorNotice = null
                        val geometry = DefaultDisplayCaptureGeometry.read(this@MainActivity)
                        val tab = browser.state.selectedTab
                        val pageRevision = browser.saveInspectionRevision
                        saves.destinations?.select(isCurrent = { isForeground && showingRecording && !isDestroyed && !isFinishing &&
                            recordingSelectionMode == RecordingSelectionMode.AUTHORIZED_FRAME && saves.currentRecordingId == null &&
                            DefaultDisplayCaptureGeometry.read(this@MainActivity) == geometry && browser.state.selectedTab == tab &&
                            browser.saveInspectionRevision == pageRevision }) { admission ->
                            saves.preparePreviewRecording(choice, admission)?.also {
                            showingRecording = false; showingSaveTasks = false; showingLibrary = false
                            showingEditor = false; showingDiscovery = false
                            libraryPreviewReference = null; previewReceipt = null; libraryExportReference = null
                            filePrompt = null; browser.cancelFiles()
                            }
                        }
                        null
                    },
                    segments = segmentPresentation,
                    retainedSessionCount = saves.segmentRecording?.retainedSessionCount ?: 0,
                    onNextSegments = saves::nextRecordedSegments,
                    onRetrySegments = saves::retryRecordedSegments,
                    onDiscardSegments = saves::discardRecordedSegments,
                    onOpenVerifiedEditor = if (recordedOutputReference == null || editor == null || repository == null) null else {
                        {
                            if (pendingRecordedEditorReference == null && isForeground && showingRecording) {
                                recordedEditorNotice = null
                                pendingRecordedEditorReference = recordedOutputReference
                            }
                        }
                    })
                else if (showingSaveTasks) SaveTaskScreen(saves, browser,
                    onBack = { showingSaveTasks = false },
                    onRecord = { showingRecording = true },
                    onLibrary = { showingLibrary = true; discovery.setVisibility(false, false) },
                    onPreview = { record -> previewReceipt = record.receipt })
                else if (showingProviderManagement) ProviderManagementScreen(providerManagementState,
                    CommonsSourceRegistration.managementConfiguration, discovery.state.commonsEnabled,
                    canToggleCommons = discovery.state.ready && !discovery.state.storageUnavailable,
                    onEnableCommons = discovery::enableCommons, onRefresh = providerManagement::inspect,
                    onAutoRefresh = providerManagement::refreshAutomatically,
                    onRejectCandidate = providerManagement::rejectCandidate, onRollback = providerManagement::rollback,
                    onBack = { providerManagement.setActive(false); showingProviderManagement = false })
                else if (showingDiscovery) DiscoveryScreen(discovery, settings.discovery, isForeground,
                    onSettings = { value ->
                        settingsChanged = true; settings = settings.copy(discovery = value)
                        persistence.saveSettings(settings); discovery.updateSettings(value)
                    }, onManageProviders = ::openProviderManagement,
                    onBrowser = { showingDiscovery = false; discovery.setVisibility(isForeground, false) },
                    onOpen = { page, result ->
                        val originTarget = discovery.captureOriginTarget(page, result)
                        discovery.setVisibility(isForeground, false); showingDiscovery = false
                        if (browser.openDiscoveryResult(originTarget, page, result)) discovery.recordSeen(page, result, SeenEvent.OPENED)
                    }, onLibrary = { showingLibrary = true; discovery.setVisibility(false, false) }, onLink = { url ->
                        discovery.setVisibility(isForeground, false); showingDiscovery = false; browser.navigate(url)
                    })
                else BrowserScreen(browser, settings, onSettings = {
                    settingsChanged = true; settings = it; browser.applySettings(it.browser); persistence.saveSettings(it)
                }, filePrompt = filePrompt,
                    onFileCancel = { filePrompt = null; browser.cancelFiles() },
                    onFileConfirm = { choice -> filePrompt = null; chooseFile(choice) },
                    openExternal = ::openSystemBrowser,
                    onDiscovery = { showingDiscovery = true; discovery.setVisibility(isForeground, true) },
                    onManageProviders = ::openProviderManagement,
                    onSave = { saves.inspectFromUserClick(browser) },
                    onSaveTasks = { showingSaveTasks = true },
                    onLibrary = { showingLibrary = true; discovery.setVisibility(false, false) },
                    saveScanning = saves.scanning,
                    saveNotice = saves.notice,
                    onDismissSaveNotice = saves::dismissNotice, destinations = saves.destinations, bookmarks = bookmarks)
                BackHandler(enabled = previewHost) {
                    recordingConsent.cancel(); previewRuntime?.cancelPreview(); showingRecording = true
                }
                if (!previewHost) {
                    SaveSelectionDialog(saves, browser)
                    if (isForeground) saves.destinations?.let { OrdinaryDestinationDialog(it) }
                }
                if (authorizedPreviewFrame != null && isForeground) {
                    AuthorizedRecordingRegionDialog(authorizedPreviewFrame, enabled = previewRuntime.previewActive,
                        onConfirm = previewRuntime::confirmPreview,
                        onCancel = { previewRuntime.cancelPreview(); showingRecording = true },
                        onRetired = previewRuntime::previewUiRetired)
                }
            }
        }
        persistence.start { loaded, checkpoint, failed ->
            if (!settingsChanged) { settings = loaded; browser.applySettings(loaded.browser) }
            discovery.updateSettings(settings.discovery)
            if (settings.browser.restoreTabs) browser.restore(checkpoint)
            if (browser.hasUserInteracted) browser.persistCurrentSession()
            if (failed) browser.showNotice(if (persistence.sessionWritesPaused)
                "标签恢复未完成，已暂停标签地址写入并保留原始数据。仍可浏览；在设置关闭保存标签地址可明确重置。"
                else "部分设置恢复未完成，地址栏可继续使用。")
        }
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (RecordingConsentController.dispatchActivityResult(requestCode, resultCode, data)) return
        if (AndroidFolderPickerHost.dispatchActivityResult(this, requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (RecordingConsentController.dispatchPermissionResult(requestCode, permissions, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    private fun openProviderManagement() {
        discovery.setVisibility(isForeground, false)
        showingProviderManagement = true
    }
    private fun chooseFile(choice: SystemBrowserHost.FileChoice) {
        if (!browser.beginFilePicker(choice)) return
        choosing = choice
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = choice.acceptTypes.singleOrNull() ?: "*/*"
            if (choice.acceptTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, choice.acceptTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, choice.multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { picker.launch(intent) } catch (_: ActivityNotFoundException) {
            choosing = null; browser.finishFiles(choice, null); browser.showNotice("系统文件选择器不可用。")
        }
    }
    private fun openSystemBrowser() {
        val address = browser.state.selectedTab?.address ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.value)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { browser.showNotice("未找到可打开该网页的系统浏览器。") }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showing_discovery", showingDiscovery)
        outState.putBoolean("showing_provider_management", showingProviderManagement)
        outState.putBoolean("showing_recording", showingRecording)
        outState.putBoolean("showing_library", showingLibrary)
        super.onSaveInstanceState(outState)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasFocusedWindow = hasFocus
        if (::saves.isInitialized) saves.segmentRecording?.setPreviewHostFocused(hasFocus)
    }
    override fun onResume() { super.onResume(); isForeground = true; if (::saves.isInitialized) saves.setForeground(true); if (::browser.isInitialized) browser.resume(); if (::discovery.isInitialized) discovery.setVisibility(true, showingDiscovery && !showingProviderManagement && !showingLibrary && !showingSaveTasks && !showingRecording) }
    override fun onPause() { if (::providerManagement.isInitialized) providerManagement.setActive(false); if (::saves.isInitialized) saves.editor?.setForeground(false); libraryExportReference = null; isForeground = false; if (::saves.isInitialized) saves.setForeground(false); if (::discovery.isInitialized) discovery.setVisibility(false, showingDiscovery); if (::browser.isInitialized) browser.pause(); super.onPause() }
    override fun onDestroy() {
        discoveryPrioritySubscription?.close(); discoveryPrioritySubscription = null
        if (::folderPickerHost.isInitialized) {
            if (::saves.isInitialized) saves.destinations?.detachFolderPicker(folderPickerHost)
            folderPickerHost.close()
        }
        if (::saves.isInitialized) saves.segmentRecording?.previewHostDestroyed()
        if (::recordingConsent.isInitialized) { if (isFinishing) recordingConsent.cancel(); recordingConsent.close() }
        if (::browser.isInitialized) browser.close()
        if (::persistence.isInitialized) persistence.close()
        if (::providerManagement.isInitialized) providerManagement.close()
        if (::discovery.isInitialized) discovery.close()
        if (::errorSink.isInitialized) errorSink.close()
        super.onDestroy()
    }
}
