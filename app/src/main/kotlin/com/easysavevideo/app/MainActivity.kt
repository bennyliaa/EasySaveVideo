package com.easysavevideo.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.easysavevideo.app.ui.RecordingSessionControls
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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.ui.RuntimeMessageCopy
import com.easysavevideo.app.ui.RuntimeMessageText
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.view.ViewGroup
import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.app.recording.PrivateRecordingBridge
import com.easysavevideo.app.recording.OwnedPrivateRecordingPresentationPort
import com.easysavevideo.app.ui.LocalPrivateAppDialogEnvironment
import com.easysavevideo.app.ui.LocalPrivatePresentation
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.ui.CapturedPrivateActions
import com.easysavevideo.app.ui.PrivateInputBoundary
import com.easysavevideo.app.ui.PrivateRecordingStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.browser.bookmarks.AndroidBookmarkRuntime
import com.easysavevideo.app.browser.history.AndroidHistoryRuntime
import com.easysavevideo.app.storage.*
import com.easysavevideo.app.diagnostics.*
import com.easysavevideo.app.theme.EsvThemeHost
import com.easysavevideo.app.ui.shell.*
import com.easysavevideo.app.ui.shell.navigation.RootNavigation
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.easysavevideo.app.ui.BrowserScreen
import com.easysavevideo.app.ui.DiscoveryScreen
import com.easysavevideo.app.ui.canClearBrowserDraft
import com.easysavevideo.app.ui.ProviderManagementScreen
import com.easysavevideo.app.save.saveRuntimeNoticeText
import com.easysavevideo.app.save.AndroidSaveRuntime
import com.easysavevideo.app.library.destination.OrdinaryDestinationDialog
import com.easysavevideo.app.library.destination.AndroidFolderPickerHost
import com.easysavevideo.app.save.SaveTaskScreen
import com.easysavevideo.app.save.SaveSelectionDialog
import com.easysavevideo.app.save.VerifiedMediaPreview
import com.easysavevideo.app.save.LibraryMediaPreview
import com.easysavevideo.app.library.export.dispatchLibraryBatchRouteExit
import com.easysavevideo.app.library.AndroidLibraryRuntime
import com.easysavevideo.app.editor.ui.EditorScreen
import com.easysavevideo.app.editor.ui.EditorDraftCheckpointCodec
import com.easysavevideo.app.editor.EditorBackRequest
import com.easysavevideo.app.library.LibraryScreen
import com.easysavevideo.app.library.LibraryUnavailableScreen
import com.easysavevideo.app.library.LibraryExportDialog
import com.easysavevideo.app.library.LibraryBatchSelection
import com.easysavevideo.app.library.LibraryBatchExportDialog
import com.easysavevideo.app.library.batchSelectionIsCurrent
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

@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var appComposeView: ComposeView
    private lateinit var privateWindowGuard: PrivateWindowGuard
    private lateinit var privatePresentationOwner: PrivateMainPresentationOwner
    private lateinit var privateRecording: PrivateRecordingBridge
    private var continuousRecordingSubscription: AutoCloseable? = null
    private lateinit var privateRecordingSurface: OwnedPrivateRecordingPresentationPort
    // Process-local route/override identity, never saved in an Activity or navigation checkpoint.
    private var privateRouteIdentity by mutableStateOf(Any())
    private var privateBackStackEntry: androidx.navigation.NavBackStackEntry? = null
    private fun actualBrowserPresentation() = activeDestination == EsvDestination.BROWSER &&
        previewReceipt == null && libraryPreviewReference == null && saves.segmentRecording?.previewActive != true
    private fun captureSourcesDrained() = ::privateRecording.isInitialized &&
        privateRecording.captureSourceDrained && saves.recordingSourceObligationsDrained()
    private fun captureAudioDisposition(view: android.webkit.WebView): PrivateBrowsingAudioDisposition = when {
        !::privateRecording.isInitialized -> PrivateBrowsingAudioDisposition.OWNED_OR_UNCERTAIN
        saves.continuousRecordingAuthorization != null -> PrivateBrowsingAudioDisposition.AUTHORIZED_CONTINUOUS_CAPTURE
        !privateRecording.hasSetupOwner && captureSourcesDrained() && !recordingConsent.requestInFlight ->
            PrivateBrowsingAudioDisposition.NO_CAPTURE_OWNER
        else -> PrivateBrowsingAudioDisposition.OWNED_OR_UNCERTAIN
    }
    private fun protectPrivateTransition(): Boolean {
        if (!::privatePresentationOwner.isInitialized || !::privateRecording.isInitialized) return false
        val reserved = privatePresentationOwner.reserveProtectedTransition()
        val protected = privateRecording.protectForPresentationTransition()
        return reserved && protected
    }
    private fun protectBeforePresentationMove(): Boolean {
        if (!::browser.isInitialized || !::privateRecording.isInitialized ||
            (!browser.isPrivateMode && !privateRecording.hasPresentationOwner)) return true
        return protectPrivateTransition()
    }
    private fun canRetainPrivateRoute(destination: EsvDestination) =
        destination in setOf(EsvDestination.BROWSER, EsvDestination.TASKS) &&
            previewReceipt == null && libraryPreviewReference == null && saves.segmentRecording?.previewActive != true
    private fun privateSelectionPending() = saves.scanning || saves.selection != null ||
        saves.preparingStream || saves.hlsPreparation != null || saves.destinations?.asking == true ||
        saves.destinations?.admitting == true || saves.destinations?.notice != null
    /**
     * Only Browser/Tasks have complete private dialog coverage. Other routes use an ordinary
     * shell after exact protected departure. Keep the private session and active Save sources;
     * an outstanding selection stays in its protected dialog rather than being cancelled here.
     */
    private fun prepareRouteMode(destination: EsvDestination, previewOverride: Boolean = false): Boolean {
        if (!::browser.isInitialized || !browser.isPrivateMode ||
            (!previewOverride && canRetainPrivateRoute(destination))) return true
        if (privateSelectionPending()) return false
        return browser.returnToOrdinaryMode()
    }
    private fun protectForPrivateModal(): Boolean {
        val reserved = privatePresentationOwner.reserveProtectedTransition()
        // Unsubmitted configuration may remain behind its destination picker. Submitted
        // continuous capture has independent runtime authorization and survives normal modals.
        val configureOnly = privateRecording.presentation?.stage == PrivateRecordingStage.CONFIGURE &&
            captureSourcesDrained() && privateRecording.captureExceptionInactive && !recordingConsent.requestInFlight
        val protected = if (configureOnly) {
            // Protection failures must not skip the independent audio stop attempt.
            val barrier = try { privateRecordingSurface.installProtectionBarrier() } catch (_: Exception) { false }
            val muted = try { browser.mutePrivateCaptureAudio() } catch (_: Exception) { false }
            barrier && muted
        } else privateRecording.protectForPresentationTransition()
        return reserved && protected
    }
    private var navController: androidx.navigation.NavHostController? = null
    private var initialDestination = EsvDestination.DISCOVER
    private var activeDestination by mutableStateOf(EsvDestination.DISCOVER)
    private var newTabInputGeneration by mutableLongStateOf(0L)
    private fun navigate(destination: EsvDestination) {
        val controller = navController ?: return
        if (controller.currentDestination?.route != destination.route && !protectBeforePresentationMove()) return
        if (!prepareRouteMode(destination)) return
        dispatchEsvNavigation(controller.currentDestination?.route, destination, onLeaving = {
            newTabInputGeneration++
            if (::browser.isInitialized) { browser.setJsDialogUiVisible(false); browser.setVideoPresenceUiVisible(false) }
            libraryBatchExportSelection=null
            discovery.setVisibility(isForeground, false)
            providerManagement.setActive(false)
            if (destination != EsvDestination.LIBRARY) libraryExportReference = null
        }, onNavigate = { target ->
            if (target in EsvDestination.roots) {
                RootNavigation.navigate(target.route, { controller.currentDestination?.route }, { controller.popBackStack() }) { preserve ->
                    controller.navigate(target.route) {
                        launchSingleTop = true
                        if (preserve) popUpTo(EsvDestination.DISCOVER.route) { saveState = true }
                        else popUpTo(controller.graph.id) { saveState = false }
                        restoreState = true
                    }
                }
            } else {
                controller.navigate(target.route) { launchSingleTop = true }
            }
        })
    }
    private fun backTo(destination: EsvDestination = EsvDestination.DISCOVER) {
        if (!protectBeforePresentationMove()) return
        if (navController?.popBackStack() != true) navigate(destination)
    }
    private lateinit var browser: SystemBrowserHost
    private lateinit var bookmarks: AndroidBookmarkRuntime
    private lateinit var ordinaryHistory: AndroidHistoryRuntime
    private lateinit var persistence: BrowserPersistence
    private lateinit var diagnostics: AndroidDiagnosticExportRuntime
    private var diagnosticState by mutableStateOf(DiagnosticExportState())
    private lateinit var errorSink: AndroidErrorSink
    private lateinit var saves: AndroidSaveRuntime
    private lateinit var folderPickerHost: AndroidFolderPickerHost
    private var discoveryPrioritySubscription: AutoCloseable? = null
    private val showingSaveTasks get() = activeDestination == EsvDestination.TASKS
    private val showingLibrary get() = activeDestination == EsvDestination.LIBRARY || activeDestination == EsvDestination.EDITOR
    private val showingEditor get() = activeDestination == EsvDestination.EDITOR
    private var libraryPreviewState by mutableStateOf<MediaReadRef?>(null)
    private var libraryPreviewReference: MediaReadRef?
        get() = libraryPreviewState
        set(value) {
            if (libraryPreviewState === value || !protectBeforePresentationMove()) return
            if (value != null && !prepareRouteMode(activeDestination, previewOverride = true)) return
            privateRouteIdentity = Any(); libraryPreviewState = value
        }
    private var libraryExportReference by mutableStateOf<MediaReadRef?>(null)
    private var libraryBatchExportSelection by mutableStateOf<LibraryBatchSelection?>(null)
    private var pendingLibraryDocumentResult: Pair<Int, Intent?>? = null
    private var pendingEditorCheckpoint by mutableStateOf<String?>(null)
    private val showingRecording get() = activeDestination == EsvDestination.RECORD
    private var recordingSelectionMode by mutableStateOf(RecordingSelectionMode.BASIC)
    private var previewReceiptState by mutableStateOf<MediaCommitReceipt?>(null)
    private var previewReceipt: MediaCommitReceipt?
        get() = previewReceiptState
        set(value) {
            if (previewReceiptState === value || !protectBeforePresentationMove()) return
            if (value != null && !prepareRouteMode(activeDestination, previewOverride = true)) return
            privateRouteIdentity = Any(); previewReceiptState = value
        }
    private lateinit var recordingConsent: RecordingConsentController
    private lateinit var discovery: AndroidDiscoveryController
    private val showingDiscovery get() = activeDestination == EsvDestination.DISCOVER
    private val showingProviderManagement get() = activeDestination == EsvDestination.SOURCES
    private lateinit var providerManagement: ProviderManagementController
    private var providerManagementState by mutableStateOf(ProviderManagementState())
    private var isForeground by mutableStateOf(false)
    private var libraryResumeSequence by mutableLongStateOf(0L)
    private var hasFocusedWindow by mutableStateOf(false)
    private var settings by mutableStateOf(AppSettings())
    // Callbacks resolve the latest settings/configuration, not a composition-era Resources snapshot.
    private val runtimeText = RuntimeMessageText { key, arguments ->
        esvLocalizedContext(this, resources.configuration, settings.appearance.language)
            .getString(key.resource, *arguments)
    }
    private var filePrompt by mutableStateOf<SystemBrowserHost.FileChoice?>(null)
    private var choosing: SystemBrowserHost.FileChoice? = null
    private var settingsChanged = false
    private var settingsReady by mutableStateOf(false)
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val choice = choosing ?: return@registerForActivityResult
        choosing = null
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK) buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip -> repeat(clip.itemCount.coerceAtMost(8)) { add(clip.getItemAt(it).uri) } }
        }.distinct() else null
        // File callbacks can execute the current page; re-cover before returning to it.
        if (!protectBeforePresentationMove()) { browser.finishFiles(choice, null); return@registerForActivityResult }
        browser.finishFiles(choice, uris)
    }
    private var discoverySettingsChanged = false
    private var settingsReadFailed by mutableStateOf(false)
    private var settingsWriteFailed by mutableStateOf(false)
    private val libraryDocumentPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val exports = if (::saves.isInitialized) saves.libraryExports else null
        if (exports == null) pendingLibraryDocumentResult = result.resultCode to result.data
        else exports.onCreateDocumentResult(result.resultCode, result.data)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialDestination = EsvDestination.fromRoute(savedInstanceState?.getString("esv_destination"))
        activeDestination = initialDestination
        pendingEditorCheckpoint = runCatching { savedInstanceState?.getString("esv_editor_draft_v1") }
            .getOrElse { "!" }?.let { if (it.length <= EditorDraftCheckpointCodec.MAX_ENCODED) it else "!" }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        errorSink = AndroidErrorSink(applicationContext)
        diagnostics = AndroidDiagnosticExportRuntime(this) { diagnosticState = it }
        bookmarks = AndroidBookmarkRuntime.get(applicationContext)
        ordinaryHistory = AndroidHistoryRuntime.get(applicationContext)
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
        if (savedInstanceState == null && saves.currentRecordingId != null) {
            initialDestination = EsvDestination.RECORD; activeDestination = initialDestination
        }
        recordingConsent = RecordingConsentController(this,
            onGrant = { id, resultCode, data ->
                saves.receiveProjectionGrant(id, resultCode, data)
                if (::privateRecording.isInitialized) privateRecording.grantOffered(id)
            }, onDenied = { id ->
                saves.abandonRecordingRequest(id)
                if (::privateRecording.isInitialized) privateRecording.requestAbandoned(id)
            }, isRequestAllowed = saves::isRecordingRequestAllowed,
            onAudioDenied = { id ->
                saves.recordingAudioPermissionDenied(id)
                if (::privateRecording.isInitialized) privateRecording.requestAbandoned(id)
            }, requiresDefaultDisplay = saves::requiresDefaultDisplayRecording)
        persistence = BrowserPersistence(applicationContext, errorSink) {
            settingsWriteFailed = true
            if (::browser.isInitialized) browser.showNotice(runtimeText.get(RuntimeMessageCopy.MAIN_PERSISTENCE_INCOMPLETE))
        }
        val entitlements = EntitlementManager(BuildEntitlements.source)
        val gate = FeatureGate()
        browser = SystemBrowserHost(this, errorSink,
            browserCommandAllowed = { gate.evaluate(FeatureRequest(Feature.BROWSER), entitlements.snapshot(),
                PlatformCapabilities(setOf(Feature.BROWSER)), BuildFeaturePolicy(), System.currentTimeMillis()).allowed },
            browserSurfaceAllowed = ::actualBrowserPresentation,
            onCheckpoint = { checkpoint -> if (::browser.isInitialized && browser.hasUserInteracted) persistence.checkpoint(checkpoint, settings.browser.restoreTabs) },
            onChooseFiles = { choice ->
                if (protectBeforePresentationMove()) filePrompt = choice else browser.finishFiles(choice, null)
            },
            onDiscoverySaveCompleted = discovery::completeOriginSave, ordinaryHistory = ordinaryHistory)
        val privateContentRoot = PrivateContentRoot(this)
        appComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        privateContentRoot.addView(appComposeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // ComponentActivity installs the existing ViewTree owners. ComposeView keeps its normal
        // window recomposer and insets handling; there is no second navigation/composition owner.
        setContentView(privateContentRoot)
        privateWindowGuard = PrivateWindowGuard(this, privateContentRoot, ordinaryContentCaptureOptIn = true) {
            newTabInputGeneration++
            if (::privatePresentationOwner.isInitialized) privatePresentationOwner.inputRevoked()
        }
        // Wiring alone cannot clear platform acceptance. API31/32 secure browsing does not use
        // recording's separately authorized continuous projection capability.
        privateWindowGuard.setPendingGates(setOf(
            PrivateWindowPendingGate.RETAINED_INPUT_CONNECTION,
            PrivateWindowPendingGate.NATIVE_PRESENTATIONS,
            PrivateWindowPendingGate.ANDROID_DEVICE_VALIDATION,
        ))
        privateRecordingSurface = OwnedPrivateRecordingPresentationPort(
            this, privateContentRoot, privateWindowGuard, browser,
            browserRouteCurrent = ::actualBrowserPresentation,
            routeIdentity = { privateRouteIdentity },
            readOwner = { target, presentationId, identity ->
                if (::privateRecording.isInitialized)
                    privateRecording.readCaptureWindowOwner(target, presentationId, identity) else null
            }, invalidateOwner = { identity ->
                if (::privateRecording.isInitialized) privateRecording.invalidateCaptureWindowOwner(identity)
            }, isContinuousAuthorized = saves::isContinuousRecordingAuthorized)
        privateRecording = PrivateRecordingBridge(this, browser, saves, recordingConsent,
            privateRecordingSurface, ::actualBrowserPresentation)
        privatePresentationOwner = PrivateMainPresentationOwner(this, privateContentRoot, privateWindowGuard, browser,
            routeIdentity = { privateRouteIdentity }, isBrowserRoute = ::actualBrowserPresentation,
            foreground = { isForeground }, captureDrained = ::captureSourcesDrained,
            captureExceptionInactive = { !privateRecordingSurface.ownsCapturePresentation },
            protectForPresentationTransition = ::protectForPrivateModal, audioDisposition = ::captureAudioDisposition,
            continuousCaptureAuthorized = { saves.continuousRecordingAuthorization != null },
            independentRecordingOwner = { saves.hasIndependentSubmittedRecordingOwner })
        privateWindowGuard.onOwnedHierarchyChanged = privatePresentationOwner::hierarchyChanged
        browser.installPrivatePresentationBinding(privatePresentationOwner)
        browser.installPrivateIntegration(object : PrivateBrowserIntegration {
            override fun ready() = saves.ready && privatePresentationOwner.ready()
            override fun canEnterPrivateMode() = saves.ready && !recordingConsent.requestInFlight &&
                !privateRecording.hasSetupOwner &&
                (saves.hasIndependentSubmittedRecordingOwner || captureSourcesDrained())
            override fun protectForPresentationTransition() = protectPrivateTransition()
            override fun protectForLifecycle(reason: PrivateBrowserLifecycleTransition): Boolean {
                val reserved = privatePresentationOwner.reserveProtectedTransition()
                val protected = privateRecording.protectForLifecycle()
                return reserved && protected
            }
            override fun allowVisibleAudio(view: android.webkit.WebView): Boolean = when (captureAudioDisposition(view)) {
                PrivateBrowsingAudioDisposition.NO_CAPTURE_OWNER -> isForeground && hasFocusedWindow && actualBrowserPresentation()
                PrivateBrowsingAudioDisposition.AUTHORIZED_CONTINUOUS_CAPTURE ->
                    isForeground && hasFocusedWindow && actualBrowserPresentation()
                PrivateBrowsingAudioDisposition.OWNED_OR_UNCERTAIN -> false
            }
        })
        continuousRecordingSubscription = saves.addContinuousRecordingListener {
            val authorization = saves.continuousRecordingAuthorization
            if (authorization != null) privateRecordingSurface.attachContinuous(authorization)
            else privateRecordingSurface.detachContinuous()
            browser.refreshPrivateCaptureAudio()
            if (!privateRecordingSurface.ownsCapturePresentation) privatePresentationOwner.retryPresentation()
        }
        appComposeView.setContent {
            // These Host reads subscribe this owning composition to tab/document/fullscreen changes.
            val privateMode = browser.state.selectedTab?.incognito == true || browser.isPrivateMode
            val presentedFullscreen = browser.fullScreenView
            // An asynchronous opening failure may only publish Runtime status, not BrowserState.
            // Observe it here so recovery receives a real fresh owning-composition SideEffect.
            val privateStatus = browser.privateStatus
            val recordingUi = privateRecording.presentation
            val presentation = privatePresentationOwner.prepareComposition()
            val childApplyTicket = privatePresentationOwner.captureChildApply(presentation)
            val inputEpoch = privatePresentationOwner.inputEpoch
            val dialogs = privatePresentationOwner.dialogEnvironment(presentation)
            val routeContentReady = if (privateMode) canRetainPrivateRoute(activeDestination)
                else privatePresentationOwner.canComposeOrdinaryContent(presentation)
            // A system pop has already changed the NavController. Until a pending selection
            // settles, compose only the protected shell/global dialogs, not an unwrapped route.
            LaunchedEffect(activeDestination, previewReceipt, libraryPreviewReference,
                saves.segmentRecording?.previewActive, privateMode, saves.scanning, saves.selection,
                saves.preparingStream, saves.hlsPreparation, saves.destinations?.asking,
                saves.destinations?.admitting, saves.destinations?.notice) {
                prepareRouteMode(activeDestination)
            }
            CompositionLocalProvider(LocalPrivatePresentation provides privateMode,
                LocalPrivateAppDialogEnvironment provides dialogs) {
            PrivateInputBoundary(privateMode, privatePresentationOwner.inputLifetime, inputEpoch) {
            // Capture this root publication and exact route occurrence, never the next reveal.
            val rootInputAdmission = LocalPrivateInputAdmission.current
            val actionRouteIdentity = privateRouteIdentity
            val chromeActions = remember(privateMode, rootInputAdmission, actionRouteIdentity, inputEpoch) {
                CapturedPrivateActions(privateMode, rootInputAdmission) {
                    privateRouteIdentity === actionRouteIdentity && privatePresentationOwner.inputEpoch == inputEpoch &&
                        privateMode == (browser.state.selectedTab?.incognito == true || browser.isPrivateMode)
                }
            }
            DisposableEffect(chromeActions) { onDispose { chromeActions.close() } }
            SideEffect {
                // Host tab/document cancellation may retire a prompt without a new chooser.
                // Retire Main's shell as well; never let stale dismissal target a replacement.
                if (filePrompt != null && !browser.hasPendingFileChoice) filePrompt = null
            }
            LaunchedEffect(saves.mediaTaskActivity, recordingUi, presentedFullscreen, privateStatus) {
                if (!privateRecordingSurface.ownsCapturePresentation) privatePresentationOwner.retryPresentation()
            }
            EsvLocaleHost(settings.appearance.language) {
            EsvThemeHost(darkTheme = settings.appearance.themeMode.useDark(isSystemInDarkTheme())) {
                val controller = rememberNavController()
                val windowSize = calculateWindowSizeClass(this@MainActivity)
                DisposableEffect(controller) {
                    navController = controller
                    val listener = androidx.navigation.NavController.OnDestinationChangedListener { navigation, destination, _ ->
                        val exactEntry = navigation.currentBackStackEntry
                        if (privateBackStackEntry !== exactEntry) {
                            // System back/pop does not pass navigate(). Cover before publishing the
                            // route which lets NavHost replace the old sensitive hierarchy.
                            protectBeforePresentationMove()
                            prepareRouteMode(EsvDestination.fromRoute(destination.route))
                            privateBackStackEntry = exactEntry
                            privateRouteIdentity = Any()
                        }
                        val nextDestination = EsvDestination.fromRoute(destination.route)
                        dispatchLibraryBatchRouteExit(activeDestination, nextDestination) {
                            if (::saves.isInitialized) saves.libraryExports?.cancelBatchForLeavingLibrary()
                        }
                        activeDestination = nextDestination
                        if (activeDestination != EsvDestination.BROWSER) { browser.setJsDialogUiVisible(false); browser.setVideoPresenceUiVisible(false) }
                    }
                    controller.addOnDestinationChangedListener(listener)
                    onDispose { controller.removeOnDestinationChangedListener(listener); navController = null; privateBackStackEntry = null }
                }
                val destinations = saves.destinations
                DisposableEffect(destinations) {
                    destinations?.attachFolderPicker(folderPickerHost)
                    saves.libraryExports?.attachBatchFolderPicker(folderPickerHost)
                    onDispose { destinations?.detachFolderPicker(folderPickerHost);saves.libraryExports?.detachBatchFolderPicker(folderPickerHost) }
                }
                val dark = settings.appearance.themeMode.useDark(isSystemInDarkTheme())
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
                LaunchedEffect(editor, pendingEditorCheckpoint) {
                    val checkpoint = pendingEditorCheckpoint
                    if (editor != null && checkpoint != null) {
                        pendingEditorCheckpoint = null
                        editor.restoreDraftCheckpoint(checkpoint)
                    }
                }
                val previewRuntime = saves.segmentRecording
                SideEffect { previewRuntime?.setPreviewHostFocused(hasFocusedWindow) }
                val previewHost = previewRuntime?.previewActive == true
                val authorizedPreviewFrame = previewRuntime?.previewFrame
                LaunchedEffect(previewRuntime?.previewReturnToControls) {
                    if (previewRuntime?.previewReturnToControls == true) {
                        navigate(EsvDestination.RECORD)
                        previewRuntime.acknowledgePreviewReturn()
                    }
                }
                RecordingPreviewHostDraw(window.decorView, previewRuntime?.previewHostRequest,
                    enabled = routeContentReady && !privateMode && previewHost && isForeground && hasFocusedWindow && authorizedPreviewFrame == null,
                    onDrawn = { request ->
                        if (routeContentReady && !browser.isPrivateMode && previewRuntime != null && isForeground && hasFocusedWindow && previewRuntime.previewActive && previewRuntime.previewFrame == null) {
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
                        recordedEditorNotice = runtimeText.get(RuntimeMessageCopy.MAIN_RECORDED_RESULT_CHANGED)
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
                        recordedEditorNotice = runtimeText.get(RuntimeMessageCopy.MAIN_RECORDED_RESULT_CHANGED)
                        return@LaunchedEffect
                    }
                    if (item == null || item.lifecycle != MediaLifecycle.READY || item.readRef() != reference) {
                        recordedEditorNotice = runtimeText.get(RuntimeMessageCopy.MAIN_RECORDED_VERSION_UNAVAILABLE)
                    } else if (editor.state.busy || editor.state.phase == com.easysavevideo.app.editor.ui.EditorPhase.BLOCKED) {
                        recordedEditorNotice = runtimeText.get(RuntimeMessageCopy.MAIN_EDITOR_BUSY)
                    } else {
                        val available = listOf(item) + (library?.state?.items ?: emptyList()).filter { it.itemId != item.itemId }
                        editor.open(item, available, settings.editor)
                        if (editor.state.draft.segments.none { it.reference == reference }) {
                            recordedEditorNotice = runtimeText.get(RuntimeMessageCopy.MAIN_EDITOR_DRAFT_REJECTED)
                            return@LaunchedEffect
                        }
                        recordedEditorNotice = null
                        libraryExportReference = null
                        navigate(EsvDestination.EDITOR)
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
                // Resume identity covers a coalesced pause; result identity covers defensive late delivery.
                LaunchedEffect(library, isForeground, libraryResumeSequence, exports?.state?.directoryConfirmation,
                    showingLibrary, showingEditor, libraryPreviewReference) {
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
                Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                EsvAppShell(controller, initialDestination, windowSize,
                    chromeVisible = !previewHost && receipt == null && libraryReference == null && !fullscreen,
                    navigationEnabled = editor?.state?.busy != true && filePrompt == null &&
                        routeContentReady &&
                        saves.selection == null && saves.hlsPreparation == null && !saves.preparingStream &&
                        libraryExportReference == null && destinations?.asking != true && destinations?.admitting != true,
                    onNavigate = { destination -> chromeActions.run { navigate(destination) } }) { route, routeEntry ->
                // NavHost can retain an outgoing subcomposition. Its route text alone is not
                // proof of the current owner, including same-route Back/forward replacement.
                val routeOwnsPresentation = { routeEntry === privateBackStackEntry &&
                    controller.currentBackStackEntry === routeEntry && route == activeDestination }
                val childApplied = {
                    if (routeOwnsPresentation()) {
                        if (privateRecordingSurface.ownsCapturePresentation)
                            privateRecordingSurface.compositionApplied(recordingUi)
                        else privatePresentationOwner.compositionApplied(presentation, childApplyTicket)
                    }
                }
                val childLaidOut = {
                    if (routeOwnsPresentation() && !privateRecordingSurface.ownsCapturePresentation) privatePresentationOwner.childLaidOut(presentation, childApplyTicket)
                }
                val browserChild = routeContentReady && (previewHost ||
                    (receipt == null && libraryReference == null && route == EsvDestination.BROWSER))
                if (!routeContentReady || (privateMode && !canRetainPrivateRoute(route))) {
                    // Empty, non-sensitive applied shell. The owner still waits for Host detach,
                    // registered-dialog teardown, capture drain and this root's actual draw.
                }
                else if (previewHost) BrowserScreen(browser, settings,
                    onSettings = { /* The bounded preview transition keeps the current browser host. */ },
                    filePrompt = null, onFileCancel = {}, onFileConfirm = {}, openExternal = {},
                    onDiscovery = {}, onManageProviders = {}, onSave = {}, onSaveTasks = {}, onLibrary = {},
                    saveScanning = false, saveNotice = null, onDismissSaveNotice = {}, interactiveJsDialogs = false,
                    onPresentationApplied = childApplied, onPresentationLayout = childLaidOut)
                else if (receipt != null) VerifiedMediaPreview(receipt, isForeground, saves::openPublishedMedia,
                    onBack = { previewReceipt = null }, onReleaseUnconfirmed = saves::previewReleaseUnconfirmed)
                else if (libraryReference != null) LibraryMediaPreview(libraryReference, isForeground, saves::openLibraryMedia,
                    onBack = { libraryPreviewReference = null }, onReleaseUnconfirmed = saves::previewReleaseUnconfirmed)
                else if (route == EsvDestination.EDITOR && editor != null) {
                    // Capture this composition's exact entry, not a route string that can be reused.
                    val editorBackEntry = remember(editor, controller) { controller.currentBackStackEntry }
                    val editorBackGeneration = newTabInputGeneration
                    val editorBackPage = remember(editor, editorBackEntry, editorBackGeneration, isForeground) { editor.openBackPage() }
                    DisposableEffect(editor, editorBackPage) { onDispose { editor.closeBackPage(editorBackPage) } }
                    fun editorBackHostCurrent(): Boolean = isForeground && !isDestroyed && !isFinishing &&
                        libraryPreviewReference == null && saves.editor === editor && navController === controller &&
                        editorBackEntry != null && controller.currentBackStackEntry === editorBackEntry &&
                        editorBackEntry.destination.route == EsvDestination.EDITOR.route &&
                        newTabInputGeneration == editorBackGeneration && editor.isBackPageCurrent(editorBackPage)
                    EditorScreen(editor.state, backPage = editorBackPage,
                        onDraftChange = editor::changeDraft, onSubmit = editor::submit,
                        onCancel = { editor.cancelDraftRestoration(); editor.cancel() },
                        onRequestBack = { if (editorBackHostCurrent()) editor.requestBack(editorBackPage) else EditorBackRequest.Ignored },
                        onDismissBack = { editor.dismissBack(editorBackPage, it) },
                        onConfirmBackStop = { if (editorBackHostCurrent()) editor.confirmBackStop(editorBackPage, it) },
                        onBack = {
                            if (editorBackHostCurrent() && editor.consumeLegacyBack(editorBackPage)) {
                                editor.cancelDraftRestoration(); editor.cancel(); backTo(EsvDestination.LIBRARY); library?.refresh()
                            }
                        },
                        openPreviewMedia = saves::openLibraryMedia, onPreviewReleaseUnconfirmed = saves::previewReleaseUnconfirmed)
                }
                else if (route == EsvDestination.LIBRARY && library != null) {
                    val thumbnails = remember(library) { AndroidLibraryThumbnails(saves::openLibraryMedia, saves::retainLibraryNativeReader) }
                    DisposableEffect(thumbnails) { onDispose { thumbnails.close() } }
                    LibraryScreen(library,
                        onBack = { libraryExportReference = null; libraryBatchExportSelection=null; backTo() },
                        onPreview = { libraryPreviewReference = it },
                        onEdit = if (editor == null || !settingsReady) null else { item ->
                            editor.open(item, library.state.items, settings.editor); navigate(EsvDestination.EDITOR); libraryExportReference = null
                        },
                        onExport = if (exports == null) null else { ref -> libraryExportReference = ref },
                        onBatchExport = if(exports==null)null else { selection -> libraryBatchExportSelection=selection },
                        exports = exports, thumbnails = thumbnails, editorInitialization = saves.editorInitializationStatus,
                        exportRecoveryIsCurrent = { expected ->
                            isForeground && activeDestination == EsvDestination.LIBRARY &&
                                libraryPreviewReference == null && previewReceipt == null &&
                                saves.segmentRecording?.previewActive != true &&
                                saves.libraryExports === expected && !isDestroyed && !isFinishing
                        },
                        editorDiagnostic = saves.editorInitializationDiagnostic)
                    val batchSelection=libraryBatchExportSelection
                    if(exports!=null && batchSelection!=null && isForeground) {
                        LibraryBatchExportDialog(batchSelection,batchSelectionIsCurrent(batchSelection,library.state),!exports.state.busy,
                            onChoose={ target ->
                                if(libraryBatchExportSelection===batchSelection && !exports.state.busy && batchSelectionIsCurrent(batchSelection,library.state)) {
                                    libraryBatchExportSelection=null
                                    when(target) {
                                        LibraryExportDestination.GALLERY -> exports.exportBatchToMediaStore(batchSelection.items)
                                        LibraryExportDestination.SHARE -> exports.shareBatch(batchSelection.items,isCurrent={
                                            isForeground && showingLibrary && libraryPreviewReference==null && !isDestroyed && !isFinishing &&
                                                batchSelectionIsCurrent(batchSelection,library.state)
                                        }) { intent -> startActivity(intent) }
                                        LibraryExportDestination.FILES -> exports.chooseBatchFolder(batchSelection,library,isCurrent={
                                            isForeground && showingLibrary && libraryPreviewReference==null && !isDestroyed && !isFinishing &&
                                                batchSelectionIsCurrent(batchSelection,library.state)
                                        })
                                    }
                                }
                            },onDismiss={libraryBatchExportSelection=null})
                    }
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
                else if (route == EsvDestination.LIBRARY) LibraryUnavailableScreen(onBack = { backTo() })
                else if (route == EsvDestination.RECORD) RecordingScreen(saves.recordingSnapshot,
                    initialSelectionMode = recordingSelectionMode,
                    onSelectionModeChange = { recordingSelectionMode = it },
                    onCreationSelectionChanged = { saves.destinations?.cancelSelection() },
                    effectiveDurationUs = saves.recordingSnapshot?.effectiveDurationUs,
                    consent = recordingConsent, canStart = !privateMode && saves.ready && saves.canSave(TaskMethod.RECORD) && saves.currentRecordingId == null,
                    canPauseResume = saves.canPauseRecording(),
                    onStart = { choice ->
                        recordedEditorNotice = null
                        val bounds = windowManager.maximumWindowMetrics.bounds
                        val density = resources.configuration.densityDpi
                        saves.destinations?.select(isCurrent = { !browser.isPrivateMode && isForeground && showingRecording && !isDestroyed && !isFinishing &&
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
                        navigate(EsvDestination.TASKS)
                    }, notice = recordedEditorNotice ?: saves.notice?.let { saveRuntimeNoticeText(it) },
                    regionSetup = RecordingRegionSetup(regionGeometry, DefaultDisplayCaptureGeometry.scopeEvidence(),
                        canStart = !privateMode && saves.ready && saves.segmentRecording?.ready == true && saves.canRegionRecording() && saves.currentRecordingId == null,
                        unavailableReason = if (regionGeometry == null) stringResource(RuntimeMessageCopy.MAIN_DISPLAY_GEOMETRY_UNAVAILABLE.resource)
                            else if (saves.segmentRecording?.ready != true) stringResource(RuntimeMessageCopy.MAIN_RECORDING_RECOVERY_NOT_READY.resource) else null),
                    onStartRegion = { choice, recipe ->
                        recordedEditorNotice = null
                        saves.destinations?.select(isCurrent = { !browser.isPrivateMode && isForeground && showingRecording && !isDestroyed && !isFinishing &&
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
                        saves.destinations?.select(isCurrent = { !browser.isPrivateMode && isForeground && showingRecording && !isDestroyed && !isFinishing &&
                            recordingSelectionMode == RecordingSelectionMode.AUTHORIZED_FRAME && saves.currentRecordingId == null &&
                            DefaultDisplayCaptureGeometry.read(this@MainActivity) == geometry && browser.state.selectedTab == tab &&
                            browser.saveInspectionRevision == pageRevision }) { admission ->
                            saves.preparePreviewRecording(choice, admission)?.also {
                            navigate(EsvDestination.BROWSER)
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
                    onOpenVerifiedEditor = if (!settingsReady || recordedOutputReference == null || editor == null || repository == null) null else {
                        {
                            if (pendingRecordedEditorReference == null && isForeground && showingRecording) {
                                recordedEditorNotice = null
                                pendingRecordedEditorReference = recordedOutputReference
                            }
                        }
                    })
                else if (route == EsvDestination.TASKS) SaveTaskScreen(saves, browser,
                    canAct = remember(chromeActions, routeEntry) {
                        { chromeActions.isCurrent() && routeOwnsPresentation() }
                    },
                    recoveryIsCurrent = { expected ->
                        isForeground && hasFocusedWindow && activeDestination == EsvDestination.TASKS &&
                            navController?.currentDestination?.route == EsvDestination.TASKS.route && saves === expected &&
                            previewReceipt == null && libraryPreviewReference == null && saves.segmentRecording?.previewActive != true &&
                            saves.selection == null && !saves.scanning && !saves.preparingStream && saves.hlsPreparation == null &&
                            saves.destinations?.asking != true && saves.destinations?.notice == null &&
                            !browser.pickerInFlight && !browser.hasPendingFileChoice && browser.jsDialog == null && browser.fullScreenView == null &&
                            !isDestroyed && !isFinishing
                    },
                    canOpenBrowserForAuthentication = {
                        isForeground && hasFocusedWindow && !isDestroyed && !isFinishing &&
                            navController?.currentDestination?.route == EsvDestination.TASKS.route &&
                            saves.selection == null && !saves.scanning && !saves.preparingStream && saves.hlsPreparation == null &&
                            saves.destinations?.asking != true && saves.destinations?.notice == null &&
                            saves.segmentRecording?.previewActive != true && previewReceipt == null && libraryPreviewReference == null &&
                            !browser.pickerInFlight && !browser.hasPendingFileChoice && browser.jsDialog == null && browser.fullScreenView == null
                    },
                    onBrowser = { navigate(EsvDestination.BROWSER) },
                    onBack = { backTo() },
                    onRecord = { navigate(EsvDestination.RECORD) },
                    onLibrary = { navigate(EsvDestination.LIBRARY); discovery.setVisibility(false, false) },
                    onPreview = { record -> previewReceipt = record.receipt })
                else if (route == EsvDestination.SOURCES) ProviderManagementScreen(providerManagementState,
                    CommonsSourceRegistration.managementConfiguration, discovery.state.commonsEnabled,
                    canToggleCommons = discovery.state.ready && !discovery.state.storageUnavailable,
                    onEnableCommons = discovery::enableCommons, onRefresh = providerManagement::inspect,
                    onAutoRefresh = providerManagement::refreshAutomatically,
                    onRejectCandidate = providerManagement::rejectCandidate, onRollback = providerManagement::rollback,
                    onBack = { providerManagement.setActive(false); backTo(EsvDestination.SETTINGS) })
                else if (route == EsvDestination.DISCOVER) {
                    // No browser text crosses into Discovery's unsaved address draft.
                    val addressTab = if (!browser.isPrivateMode) browser.state.selectedTab?.takeIf { !it.incognito } else null
                    val addressRevision = browser.saveInspectionRevision
                    val addressGeneration = newTabInputGeneration
                    val addressVisit = remember(chromeActions, routeEntry, addressGeneration, addressTab?.context) { Any() }
                    val addressAction = remember(addressVisit, addressTab?.epoch, addressRevision) { Any() }
                    val addressPresentationCurrent = {
                        chromeActions.isCurrent() && routeOwnsPresentation() && navController === controller &&
                            addressGeneration == newTabInputGeneration && isForeground && hasFocusedWindow && !isDestroyed && !isFinishing &&
                            activeDestination == EsvDestination.DISCOVER && !browser.isPrivateMode &&
                            addressTab != null && browser.state.selectedTab?.incognito == false &&
                            browser.state.selectedTab?.context == addressTab.context &&
                            filePrompt == null && !browser.pickerInFlight && !browser.hasPendingFileChoice &&
                            browser.jsDialog == null && browser.fullScreenView == null &&
                            !saves.scanning && saves.selection == null && !saves.preparingStream && saves.hlsPreparation == null &&
                            saves.destinations?.asking != true && saves.destinations?.admitting != true && saves.destinations?.notice == null &&
                            saves.segmentRecording?.previewActive != true && previewReceipt == null && libraryPreviewReference == null
                    }
                    DiscoveryScreen(discovery, settings.discovery, isForeground,
                    addressVisit = addressVisit, addressAction = addressAction, canOpenAddress = addressPresentationCurrent,
                    canEditAddress = { canClearBrowserDraft(addressTab, browser.state.selectedTab, addressRevision,
                        browser.saveInspectionRevision, addressPresentationCurrent()) },
                    onOpenAddress = { input, draftCurrent ->
                        if (!canClearBrowserDraft(addressTab, browser.state.selectedTab, addressRevision,
                                browser.saveInspectionRevision, addressPresentationCurrent() && draftCurrent())) false
                        else {
                            val sourceCurrent = { addressPresentationCurrent() && draftCurrent() }
                            val accepted = browser.openOrdinaryAddress(input, checkNotNull(addressTab).context,
                                addressTab.epoch, addressRevision, sourceCurrent)
                            if (accepted && sourceCurrent()) navigate(EsvDestination.BROWSER)
                            accepted
                        }
                    },
                    settingsEditable = settingsReady && persistence.settingsLoaded && isForeground,
                    canClearQuery = {
                        isForeground && activeDestination == EsvDestination.DISCOVER &&
                            !saves.scanning && saves.selection == null && !saves.preparingStream && saves.hlsPreparation == null &&
                            saves.destinations?.asking != true && saves.destinations?.notice == null &&
                            saves.segmentRecording?.previewActive != true && previewReceipt == null && libraryPreviewReference == null
                    },
                    onSettings = { edit ->
                        applyDiscoverySettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded, isForeground, !isDestroyed && !isFinishing,
                            activeDestination, EsvDestination.DISCOVER)?.let { value ->
                            discoverySettingsChanged = true
                            settingsChanged = true; settings = value
                            persistence.saveSettings(value); discovery.updateSettings(value.discovery)
                        }
                    }, onManageProviders = ::openProviderManagement,
                    onBrowser = { navigate(EsvDestination.BROWSER); discovery.setVisibility(isForeground, false) },
                    onOpen = { request ->
                        com.easysavevideo.app.discovery.dispatchDiscoveryCardOpen(request, { discovery.state },
                            { discovery.cardNavigationLifecycle },
                            interactive = { isForeground && activeDestination == EsvDestination.DISCOVER && !isDestroyed && !isFinishing &&
                                !saves.scanning && saves.selection == null && !saves.preparingStream && saves.hlsPreparation == null &&
                                saves.destinations?.asking != true && saves.destinations?.notice == null &&
                                saves.segmentRecording?.previewActive != true && previewReceipt == null && libraryPreviewReference == null },
                            controlsBusy = { discovery.filterControlsBusy },
                            open = browser::openDiscoveryResult,
                            showBrowser = { navigate(EsvDestination.BROWSER) },
                            recordOpened = { page, result -> discovery.recordSeen(page, result, SeenEvent.OPENED) })
                    }, onLibrary = { navigate(EsvDestination.LIBRARY); discovery.setVisibility(false, false) }, onLink = { url ->
                        discovery.setVisibility(isForeground, false); navigate(EsvDestination.BROWSER); browser.navigate(url)
                    })
                }
                else if (route == EsvDestination.SETTINGS) EsvSettingsScreen(settings,
                    onChange = { edit ->
                        val value = if (edit is SettingsEdit.DiscoveryEdit)
                            applyDiscoverySettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded, isForeground, !isDestroyed && !isFinishing,
                                activeDestination, EsvDestination.SETTINGS)
                            else if (edit is SettingsEdit.BrowserEdit)
                                applyBrowserSettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded, isForeground, !isDestroyed && !isFinishing,
                                    activeDestination, EsvDestination.SETTINGS)
                            else if (edit is SettingsEdit.DirectConcurrency)
                                applyDirectConcurrencySettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded,
                                    isForeground, !isDestroyed && !isFinishing, activeDestination)?.let { allowed ->
                                    saves.updateDirectConcurrency(allowed.save.maxConcurrentDirectTasks)?.let { accepted ->
                                        allowed.copy(save = accepted)
                                    }
                                }
                            else edit.applyTo(settings)
                        if (value != null) {
                            val changedNetwork = edit is SettingsEdit.WifiOnly
                            if (changedNetwork) saves.updateSaveSettings(value.save)
                            settingsChanged = true; settings = value
                            if (edit is SettingsEdit.Language) saves.updateRecordingNotificationLanguage(settings.appearance.language)
                            if (edit is SettingsEdit.BrowserEdit) browser.applySettings(value.browser)
                            persistence.saveSettings(value, confirmNetworkPreference = changedNetwork)
                            if (edit is SettingsEdit.DiscoveryEdit) {
                                discoverySettingsChanged = true
                                discovery.updateSettings(value.discovery)
                            }
                        }
                    },
                    saveConcurrencyEditable = settingsReady && persistence.settingsLoaded && isForeground &&
                        activeDestination == EsvDestination.SETTINGS && !isDestroyed && !isFinishing,
                    browserEditable = settingsReady && persistence.settingsLoaded && isForeground && activeDestination == EsvDestination.SETTINGS,
                    discoveryEditable = settingsReady && persistence.settingsLoaded && isForeground && activeDestination == EsvDestination.SETTINGS,
                    persistenceWarning = settingsWriteFailed, loadWarning = settingsReadFailed,
                    saveDestination = {
                        val generation = newTabInputGeneration
                        CentralSaveDestinationSettings(saves.destinations, generation,
                            canAct = { generation == newTabInputGeneration && isForeground &&
                                activeDestination == EsvDestination.SETTINGS && !isDestroyed && !isFinishing &&
                                !saves.scanning && saves.selection == null && !saves.preparingStream && saves.hlsPreparation == null &&
                                browser.jsDialog == null && browser.fullScreenView == null &&
                                !browser.pickerInFlight && !browser.hasPendingFileChoice })
                    },
                    entitlements = {
                        val generation = newTabInputGeneration
                        EntitlementSettingsDetail(generation, BuildConfig.DEBUG,
                            canRead = { generation == newTabInputGeneration && isForeground &&
                                activeDestination == EsvDestination.SETTINGS && !isDestroyed && !isFinishing },
                            readSnapshot = entitlements::snapshot)
                    },
                    privacy = {
                        val generation = newTabInputGeneration
                        val canUsePrivacy = { generation == newTabInputGeneration && isForeground &&
                            activeDestination == EsvDestination.SETTINGS && !isDestroyed && !isFinishing &&
                            !saves.scanning && saves.selection == null && !saves.preparingStream && saves.hlsPreparation == null &&
                            saves.destinations?.asking != true && browser.jsDialog == null && browser.fullScreenView == null &&
                            !browser.pickerInFlight && !browser.hasPendingFileChoice }
                        PrivacySettingsDetail(ordinaryHistory, generation, canAct = canUsePrivacy,
                            clearCache = browser::clearCacheWithResult)
                        DiscoveryPrivacySettingsDetail(discovery, generation, canAct = canUsePrivacy)
                    },
                    onSources = ::openProviderManagement, onBack = { backTo() },
                    diagnostics = { DiagnosticSettings(diagnosticState, diagnostics.controller::begin, diagnostics.controller::cancel) })
                else if (route == EsvDestination.EDITOR) LibraryUnavailableScreen(onBack = { backTo(EsvDestination.LIBRARY) })
                else BrowserScreen(browser, settings, onSettings = { edit ->
                    applyBrowserSettingsEdit(settings, edit, settingsReady && persistence.settingsLoaded, isForeground, !isDestroyed && !isFinishing,
                        activeDestination, EsvDestination.BROWSER)?.let { value ->
                        settingsChanged = true; settings = value
                        browser.applySettings(value.browser); persistence.saveSettings(value)
                    }
                }, settingsEditable = settingsReady && persistence.settingsLoaded && isForeground && activeDestination == EsvDestination.BROWSER,
                    filePrompt = filePrompt,
                    onFileCancel = { choice ->
                        if (filePrompt === choice) { filePrompt = null; browser.finishFiles(choice, null) }
                    },
                    onFileConfirm = ::chooseFile,
                    isFilePromptCurrent = { choice -> filePrompt === choice && isForeground && actualBrowserPresentation() &&
                        !browser.pickerInFlight && browser.hasPendingFileChoice },
                    openExternal = ::openSystemBrowser,
                    onDiscovery = { navigate(EsvDestination.DISCOVER); discovery.setVisibility(isForeground, true) },
                    newTabInputGeneration = newTabInputGeneration,
                    canSubmitNewTabInput = { generation -> generation == newTabInputGeneration && isForeground && activeDestination == EsvDestination.BROWSER },
                    onVideoSearchDraft = { query ->
                        if (isForeground && activeDestination == EsvDestination.BROWSER) {
                            navigate(EsvDestination.DISCOVER)
                            discovery.setVisibility(true, true)
                            discovery.offerBrowserQuery(query)
                        }
                    },
                    onManageProviders = ::openProviderManagement,
                    onSave = { saves.inspectFromUserClick(browser) },
                    onRecording = {
                        if (browser.isPrivateMode) privateRecording.configure() else navigate(EsvDestination.RECORD)
                    },
                    onSaveTasks = { navigate(EsvDestination.TASKS) },
                    onLibrary = { navigate(EsvDestination.LIBRARY); discovery.setVisibility(false, false) },
                    saveScanning = saves.scanning,
                    saveNotice = saves.notice?.let { saveRuntimeNoticeText(it) },
                    onDismissSaveNotice = saves::dismissNotice, destinations = saves.destinations, bookmarks = bookmarks, ordinaryHistory = ordinaryHistory, saveRuntime = saves,
                    onPresentationApplied = childApplied, onPresentationLayout = childLaidOut)
                // Shell/application/ordinary override apply belongs to this exact NavHost child.
                // Browser instead acknowledges from its deeper BoxWithConstraints composition.
                if (!browserChild) SideEffect { childApplied() }
                }
                } // Route viewport; controls stay reachable on every ordinary/private route.
                if (recordingUi != null) RecordingSessionControls(recordingUi)
                }
                BackHandler(enabled = previewHost) {
                    recordingConsent.cancel(); previewRuntime?.cancelPreview(); navigate(EsvDestination.RECORD)
                }
                if (!previewHost && (privateMode || routeContentReady)) {
                    if (activeDestination != EsvDestination.BROWSER) SaveSelectionDialog(saves, browser)
                    if (isForeground) saves.destinations?.let { OrdinaryDestinationDialog(it) }
                }
                if (authorizedPreviewFrame != null && isForeground && routeContentReady && !privateMode) {
                    AuthorizedRecordingRegionDialog(authorizedPreviewFrame, enabled = previewRuntime.previewActive,
                        onConfirm = previewRuntime::confirmPreview,
                        onCancel = { previewRuntime.cancelPreview(); navigate(EsvDestination.RECORD) },
                        onRetired = previewRuntime::previewUiRetired)
                }
            }
            }
            }
            }
        }
        persistence.start { loaded, checkpoint, failed ->
            if (!settingsChanged) { settings = loaded }
            settingsReadFailed = !persistence.settingsLoaded
            settings = mergeLoadedBrowserSettings(settings, loaded, persistence.settingsLoaded)
            browser.applySettings(settings.browser)
            // Appearance or language changes during startup must not discard loaded discovery preferences.
            settings = mergeLoadedDiscoverySettings(settings, loaded, discoverySettingsChanged, persistence.settingsLoaded)
            // Merge each save field with the application owner, including after early Wi-Fi edits or recreation.
            if (persistence.settingsLoaded) {
                settings = settings.copy(save = saves.initializeSaveSettings(loaded.save))
            }
            if (settingsChanged && persistence.settingsLoaded) persistence.saveSettings(settings)
            saves.updateRecordingNotificationLanguage(settings.appearance.language)
            settingsReady = true
            discovery.updateSettings(settings.discovery)
            if (settings.browser.restoreTabs) browser.restore(checkpoint)
            if (browser.hasUserInteracted) browser.persistCurrentSession()
            if (failed) browser.showNotice(if (persistence.sessionWritesPaused)
                runtimeText.get(RuntimeMessageCopy.MAIN_TABS_RECOVERY_INCOMPLETE)
                else runtimeText.get(RuntimeMessageCopy.MAIN_SETTINGS_RECOVERY_INCOMPLETE))
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
        navigate(EsvDestination.SOURCES)
    }
    private fun chooseFile(choice: SystemBrowserHost.FileChoice) {
        if (filePrompt !== choice || !isForeground || !actualBrowserPresentation() ||
            !protectBeforePresentationMove() || !browser.beginFilePicker(choice)) return
        filePrompt = null
        choosing = choice
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = choice.acceptTypes.singleOrNull() ?: "*/*"
            if (choice.acceptTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, choice.acceptTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, choice.multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { picker.launch(intent) } catch (_: ActivityNotFoundException) {
            choosing = null; browser.finishFiles(choice, null); browser.showNotice(runtimeText.get(RuntimeMessageCopy.MAIN_FILE_PICKER_UNAVAILABLE))
        }
    }
    private fun openSystemBrowser() {
        val address = browser.state.selectedTab?.address ?: return
        if (!protectBeforePresentationMove()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.value)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { browser.showNotice(runtimeText.get(RuntimeMessageCopy.MAIN_SYSTEM_BROWSER_UNAVAILABLE)) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("esv_destination", activeDestination.route)
        val editor = if (::saves.isInitialized) saves.editor else null
        val checkpoint = if (editor != null) editor.savedDraftCheckpoint() else pendingEditorCheckpoint
        checkpoint?.let { outState.putString("esv_editor_draft_v1", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasFocusedWindow = hasFocus
        if (::privateRecording.isInitialized && browser.isPrivateMode) {
            if (!hasFocus) {
                if (privateWindowGuard.hasRegisteredPrivateDialogs) {
                    privateWindowGuard.installProtectionBarrier(); browser.mutePrivateCaptureAudio()
                } else privateRecording.protectForLifecycle()
            }
            privateRecording.refreshPresentation()
            if (hasFocus && !privateRecordingSurface.ownsCapturePresentation) privatePresentationOwner.retryPresentation()
            browser.refreshPrivateCaptureAudio()
        }
        if (::saves.isInitialized) saves.segmentRecording?.setPreviewHostFocused(hasFocus)
    }
    override fun onResume() { super.onResume(); libraryResumeSequence++; isForeground = true; if (::saves.isInitialized) saves.setForeground(true); if (::browser.isInitialized) browser.resume(); if (::discovery.isInitialized) discovery.setVisibility(true, showingDiscovery && !showingProviderManagement && !showingLibrary && !showingSaveTasks && !showingRecording) }
    override fun onPostResume() {
        super.onPostResume()
        if (::diagnostics.isInitialized) diagnostics.onPostResume()
        if (::privateRecording.isInitialized) privateRecording.refreshPresentation()
        if (::privatePresentationOwner.isInitialized && !privateRecordingSurface.ownsCapturePresentation) privatePresentationOwner.retryPresentation()
    }
    override fun onPause() {
        // Synchronous protection precedes the Activity/Host foreground and route updates.
        if (::privateRecording.isInitialized && (browser.isPrivateMode || privateRecording.hasPresentationOwner))
            privateRecording.protectForLifecycle()
        filePrompt = null // Host.pause retires only non-picker choices; picker identity stays in choosing.
        newTabInputGeneration++; if (::diagnostics.isInitialized) diagnostics.onPause(); if (::providerManagement.isInitialized) providerManagement.setActive(false); if (::saves.isInitialized) { saves.editor?.setForeground(false); saves.libraryExports?.cancelBatchForBackground() }; libraryExportReference = null; libraryBatchExportSelection=null; isForeground = false; if (::saves.isInitialized) saves.setForeground(false); if (::discovery.isInitialized) discovery.setVisibility(false, showingDiscovery); if (::browser.isInitialized) browser.pause(); super.onPause() }
    override fun onDestroy() {
        continuousRecordingSubscription?.close(); continuousRecordingSubscription = null
        if (::privateRecording.isInitialized) privateRecording.close()
        if (::browser.isInitialized) browser.close()
        if (::privateRecordingSurface.isInitialized) privateRecordingSurface.close()
        if (::privatePresentationOwner.isInitialized) privatePresentationOwner.close()
        if (::privateWindowGuard.isInitialized) privateWindowGuard.close()
        if (::appComposeView.isInitialized) appComposeView.disposeComposition()
        if (::diagnostics.isInitialized) diagnostics.close()
        discoveryPrioritySubscription?.close(); discoveryPrioritySubscription = null
        if (::folderPickerHost.isInitialized) {
            if (::saves.isInitialized) saves.destinations?.detachFolderPicker(folderPickerHost)
            if (::saves.isInitialized) saves.libraryExports?.detachBatchFolderPicker(folderPickerHost)
            folderPickerHost.close()
        }
        if (::saves.isInitialized) saves.segmentRecording?.previewHostDestroyed()
        if (::recordingConsent.isInitialized) { if (isFinishing) recordingConsent.cancel(); recordingConsent.close() }
        if (::persistence.isInitialized) persistence.close()
        if (::providerManagement.isInitialized) providerManagement.close()
        if (::discovery.isInitialized) discovery.close()
        if (::errorSink.isInitialized) errorSink.close()
        super.onDestroy()
    }
}
