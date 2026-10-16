package com.easysavevideo.app.save

import com.easysavevideo.app.browser.privacy.PrivateCaptureDisposition

import android.Manifest
import com.easysavevideo.app.diagnostics.*
import android.app.Activity
import android.content.Context
import com.easysavevideo.app.ui.androidRuntimeMessageText
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.easysavevideo.app.recording.*
import com.easysavevideo.recording.*
import java.io.FileInputStream
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.browser.privacy.BrowserSavePartition
import com.easysavevideo.app.discovery.DiscoverySaveOriginHandle
import com.easysavevideo.app.discovery.DiscoveryOriginMediaOutcome
import com.easysavevideo.app.discovery.DiscoveryOriginSaveOutcome
import com.easysavevideo.save.network.RequestContextLeaseStore
import com.easysavevideo.save.network.SystemHttpsDirectTransfer
import com.easysavevideo.save.network.NetworkStagingAccess
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.app.save.stream.AndroidHlsStreamPort
import com.easysavevideo.app.save.stream.HlsPreparation
import com.easysavevideo.app.save.stream.Media3LocalHlsEngine
import com.easysavevideo.core.domain.EntitlementManager
import com.easysavevideo.core.domain.FeatureGate
import com.easysavevideo.app.entitlements.BuildEntitlements
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import com.easysavevideo.app.library.export.AndroidExportRuntime
import com.easysavevideo.app.library.destination.AndroidOrdinaryDestinationRuntime
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.app.editor.AndroidEditRuntime
import com.easysavevideo.app.editor.EditorInitializationStatus
import com.easysavevideo.app.editor.initializeEditor
import com.easysavevideo.library.LibraryRepository
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.MediaLifecycle
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Application-scoped owner: Activity recreation and tab closure cannot orphan or duplicate a task. */
class AndroidSaveRuntime private constructor(context: Context) {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val detector = PageSaveDetector()
    private val networkPolicy = SaveWifiNetworkPolicy(AndroidSaveNetworkBackend(application))
    private val controls = executor("esv-save-control", 16)
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { job -> Thread(job, "esv-save-transfer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val clock = EpochClock(System::currentTimeMillis)
    val limits = SaveLimits(maxArtifactBytes = 2L * 1024 * 1024 * 1024, maxTotalTransferBytes = 4L * 1024 * 1024 * 1024)
    private val gate = FeatureGate()
    private val entitlements = EntitlementManager(BuildEntitlements.source)
    private val leases = RequestContextLeaseStore()
    private lateinit var storage: AndroidSaveStorage
    private lateinit var coordinator: SaveCoordinator
    private lateinit var recorder: AndroidRecordSavePort
    @Volatile private var pendingRecording: CaptureDescriptor? = null
        set(value) { field = value; publishMediaTaskActivity() }
    private var privateRecordingBinding: PrivateRecordingNativeBinding? = null
    private var privateRecordingReady: ((PrivateRecordingNativeBinding, Any) -> Unit)? = null
    private var privateRecordingSubmitted = false
    @Volatile private var continuousRecordingOwner: ContinuousRecordingOwner? = null
    private val continuousRecordingListeners = linkedSetOf<(ContinuousRecordingSnapshot?) -> Unit>()
    internal var continuousRecordingSnapshot by mutableStateOf<ContinuousRecordingSnapshot?>(null); private set
    internal val hasIndependentSubmittedRecordingOwner: Boolean get() =
        continuousRecordingOwner?.isIndependentSubmitted(continuousRecordingSnapshot) == true
    /** Pixel exposure only. Output controls intentionally outlive this authorization. */
    internal val continuousRecordingAuthorization: ContinuousRecordingAuthorization? get() =
        continuousRecordingOwner?.authorization?.takeIf(::isContinuousRecordingAuthorized)
    internal fun isContinuousRecordingAuthorized(value: ContinuousRecordingAuthorization): Boolean {
        val owner = continuousRecordingOwner ?: return false
        return currentRecordingId == value.sessionId && owner.authorizes(value)
    }
    internal fun addContinuousRecordingListener(listener: (ContinuousRecordingSnapshot?) -> Unit): AutoCloseable {
        requireMain(); continuousRecordingListeners += listener
        listener(continuousRecordingSnapshot)
        return AutoCloseable { requireMain(); continuousRecordingListeners -= listener }
    }
    internal fun dismissContinuousRecording(value: ContinuousRecordingAuthorization) {
        requireMain()
        val owner = continuousRecordingOwner ?: return
        if (owner.authorization !== value || owner.terminal == null || owner.releaseUnconfirmed || !owner.nativeDrained) return
        continuousRecordingOwner = null; publishContinuousRecording()
    }
    /** These native observers belong only to this application runtime, never an Activity policy. */
    internal fun createPrivateRecordingBinding(descriptor: CaptureDescriptor, identity: Any): PrivateRecordingNativeBinding =
        PrivateRecordingNativeBinding(descriptor, identity,
            onNativeStarted = { main.post {
                continuousRecordingOwner?.takeIf { it.descriptor == descriptor }?.let { it.nativeStarted = true }
                publishContinuousRecording()
            } }, onActualDrain = { confirmed -> main.post {
                continuousRecordingOwner?.takeIf { it.descriptor == descriptor }?.let {
                    it.nativeDrained = confirmed; it.releaseUnconfirmed = !confirmed
                }
                publishContinuousRecording()
            } })
    private val continuousPoll = object : Runnable {
        override fun run() {
            publishContinuousRecording()
            val owner = continuousRecordingOwner ?: return
            if (!owner.submitted || owner.terminal != null) return
            if (owner.discardRequested) cancel(owner.descriptor.sessionId)
            main.postDelayed(this, 250)
        }
    }
    private fun publishContinuousRecording() {
        requireMain()
        val owner = continuousRecordingOwner
        val next = if (owner == null || !owner.submitted) null else {
            val id = owner.descriptor.sessionId
            val task = if (::coordinator.isInitialized) coordinator.snapshot(id) else null
            val native = recordingSnapshot?.takeIf { it.request?.descriptor == owner.descriptor }
            native?.let(owner::observeNativeSnapshot)
            if (native?.state in setOf(RecordingState.CAPTURE_FINISHED, RecordingState.CANCELLED, RecordingState.FAILED) &&
                ::recorder.isInitialized && !recorder.nativeOccupied && !recorder.isDraining)
                owner.nativeDrained = true
            val terminal = when (task?.state) {
                TaskState.SUCCEEDED -> ContinuousRecordingPhase.SUCCEEDED
                TaskState.FAILED -> ContinuousRecordingPhase.FAILED
                TaskState.CANCELLED -> ContinuousRecordingPhase.CANCELLED
                else -> owner.terminal
            }
            if (terminal != null) owner.terminal = terminal
            val outputPhase = when {
                terminal != null -> terminal
                task?.state == TaskState.WAITING_USER -> ContinuousRecordingPhase.BLOCKED
                ::coordinator.isInitialized && coordinator.phaseSnapshot(id) == SavePhase.COMMITTING -> ContinuousRecordingPhase.COMMITTING
                ::coordinator.isInitialized && coordinator.phaseSnapshot(id) == SavePhase.VERIFYING -> ContinuousRecordingPhase.VERIFYING
                else -> null
            }
            owner.snapshot(outputPhase)
        }
        if (continuousRecordingSnapshot == next) return
        continuousRecordingSnapshot = next
        continuousRecordingListeners.toList().forEach { listener -> try { listener(next) } catch (_: Exception) { } }
    }
    private fun acceptContinuousSubmission(descriptor: CaptureDescriptor) {
        val owner = continuousRecordingOwner?.takeIf { it.descriptor == descriptor } ?: return
        owner.submitted = true
        // Accepted-submission watchdog carries only the process-local runtime owner. It replaces
        // the retired Activity policy's ARMING timer, including delayed first-native callbacks.
        main.postDelayed({
            if (continuousRecordingOwner === owner && owner.submitted && !owner.nativeStarted &&
                !owner.nativeDrained && owner.terminal == null && !owner.stopRequested)
                safetyStopRecording(descriptor.sessionId, RecordingStopReason.DEADLINE_EXCEEDED)
        }, 10_000)
        main.removeCallbacks(continuousPoll); main.post(continuousPoll)
    }
    private var recordingGeneration = 0L
    private var recordingService: RecordingForegroundService? = null
        set(value) { field = value; publishMediaTaskActivity() }
    private val notificationLanguage = RecordingNotificationLanguage()
    internal val recordingNotificationLanguage: AppLanguage get() = notificationLanguage.current
    internal fun updateRecordingNotificationLanguage(language: AppLanguage) {
        requireMain()
        if (notificationLanguage.update(language)) recordingService?.refreshNotificationText()
    }
    var recordingSnapshot by activityStateOf<RecordingSnapshot?>(null); private set
    var currentRecordingId by activityStateOf<String?>(null); private set
    private var previewUnconfirmed = false
    private lateinit var transport: FixedDirectTransferPool
    private lateinit var stream: AndroidHlsStreamPort
    private val privateSources = PrivateNetworkRuntimeSources<HlsPreparation>(leases,
        clearReleasedPreparation = { stream.detachReleased(it) },
        signalSourceExit = { taskId, binding -> coordinator.signalPrivateSourceExit(taskId, binding); Unit })
    private var streamSelectionContext: PageSaveSelection? = null
    private var streamDiscoveryOrigin: DiscoverySaveOriginHandle? = null
    private val authenticationOrigins = AuthenticationTaskOrigins()
    private var streamAuthenticationOrigin: AuthenticationOriginCapture? = null
    var discoverySaveOutcomes by mutableStateOf<Map<String, DiscoveryOriginSaveOutcome>>(emptyMap()); private set
    private val discoverySaveLinks = MediaTaskActivitySaveLinks<DiscoverySaveOriginHandle>(
        bind = { origin, taskId -> origin.bindTask(taskId) },
        complete = { origin, taskId, committed ->
            origin.complete(taskId, if (committed) DiscoveryOriginMediaOutcome.COMMITTED else DiscoveryOriginMediaOutcome.NOT_COMMITTED) { outcome ->
                if (outcome.media == DiscoveryOriginMediaOutcome.COMMITTED) main.post {
                    discoverySaveOutcomes = (discoverySaveOutcomes + (taskId to outcome)).entries
                        .toList().takeLast(100).associate { it.key to it.value }
                }
            }
        },
        abandon = DiscoverySaveOriginHandle::abandon,
    )
    private var preparationCancellation: CancellationSource? = null
    private var preparationExecution: SaveExecutionAdmission.Claim? = null
    private var preparationWorker: SaveExecutionAdmission.Claim? = null
    private var preparationGeneration = 0L
    var hlsPreparation by activityStateOf<HlsPreparation?>(null); private set
    var preparingStream by activityStateOf(false); private set
    private val activityTracker = MediaTaskActivityTracker()
    private val initializationActivity = MediaTaskActivityHandoff(::publishMediaTaskActivity)
    private val expiryActivity = MediaTaskActivityExpiry(::publishMediaTaskActivity)
    private val pendingMediaCommands = AtomicInteger()
    var mediaTaskActivity by mutableStateOf(MediaTaskActivitySnapshot()); private set
    private val saveSettingsOwner = SaveSettingsOwner()
    @Volatile private var directBatchOwner: Any? = null
    // Actual native/SQLite acceptance remains a separate gate, not a user preference.
    private val movAdmissionEnabled = false
    private val directLocalGate = DirectLocalProcessingGate(clock, ::executionReleaseUnconfirmed)
    @Volatile private var controlledDirectStorage: ControlledDirectStorage? = null
    private val exclusiveOwner = java.util.concurrent.atomic.AtomicReference<SaveExecutionAdmission.Claim?>(null)
    private val exclusiveExecution: SaveExecutionAdmission.Claim? get() = exclusiveOwner.get()
    private val executionAdmission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes,
        movCapable = movAdmissionEnabled,
        availableBytes = { if (::storage.isInitialized) storage.availableBytesForSaveAdmission() else null },
        resourcesUnreleased = ::executionReleaseUnconfirmed,
        onFirstDirect = ::claimDirectBatch,
        onLastDirectReleased = { directBatchOwner?.let(OrdinaryMediaNativeResources::released); directBatchOwner = null })
    private val executionRetirement = SaveExecutionRetirement(
        owns = executionAdmission::owns,
        releaseUnconfirmed = { executionReleaseUnconfirmed() || (::stream.isInitialized && stream.isDraining) },
        release = executionAdmission::release,
        retired = { claim ->
            exclusiveOwner.compareAndSet(claim, null)
            privateSources.executionRetired(claim) { entry ->
                val binding = entry.binding
                // Actual retirement is already proven by this callback. Only real Core WAITING_USER
                // plus its retained exact live Direct binding may keep the source owner dormant.
                binding != null && coordinator.snapshot(binding.taskId)?.let {
                    it.state == TaskState.WAITING_USER && it.method == TaskMethod.DIRECT
                } == true && coordinator.sourceForTask(binding.taskId)?.privateSource === binding
            }
        })
    private val refreshing = AtomicBoolean(false)
    @Volatile private var lastExpiryCheckMillis = 0L
    @Volatile private var foreground = false
    @Volatile private var executionForegroundGeneration = 0L
    var ready by mutableStateOf(false); private set
    var libraryExports by mutableStateOf<AndroidExportRuntime?>(null); private set
    var destinations by mutableStateOf<AndroidOrdinaryDestinationRuntime?>(null); private set
    var editor by mutableStateOf<AndroidEditRuntime?>(null); private set
    var editorInitializationDiagnostic by mutableStateOf<InitializationDiagnostic?>(null); private set
    var editorInitializationStatus by mutableStateOf(EditorInitializationStatus.INITIALIZING); private set
    var segmentRecording by activityStateOf<AndroidSegmentRecordingRuntime?>(null); private set
    var scanning by mutableStateOf(false); private set
    var selection by mutableStateOf<PageSaveSelection?>(null); private set
    var showFailureBrowserReminder by mutableStateOf(false); private set
    private val alternativeSelection = SaveAlternativeSelection()
    private var streamAlternative: StreamAlternativeProposal? = null
    private var streamAlternativeGeneration: Long? = null
    private var streamAlternativeRequired = false
    private var selectionAlternativeExpected = false
    internal var alternativeNeedsSource by mutableStateOf(false); private set
    internal var alternativePrepared by mutableStateOf(false); private set
    internal var alternativeFeedback by mutableStateOf<SaveAlternativeFeedback?>(null); private set
    // Result verification identity only; never execution, cancellation or replay authority.
    internal var unknownAlternativeTaskId: String? = null; private set
    internal var authenticationNavigationGeneration by mutableLongStateOf(0L); private set
    var records by mutableStateOf<List<SaveRecord>>(emptyList()); private set
    var activeTaskCount by mutableStateOf(0); private set
    var mediaDispositions by mutableStateOf<Map<String, MediaLifecycle>>(emptyMap()); private set
    internal var notice by mutableStateOf<SaveRuntimeNotice?>(null); private set
    var draining by activityStateOf(false); private set
    var networkDraining by activityStateOf(false); private set
    private val taskCommands = SaveTaskCommandGate()
    internal var pendingSaveRecoveries by mutableStateOf<List<SaveResumeHandoff.Ticket>>(emptyList()); private set
    internal var recoveryCancelTaskIds by mutableStateOf<Set<String>>(emptySet()); private set
    private val resumeHandoff: SaveResumeHandoff by lazy {
        SaveResumeHandoff(coordinator, taskCommands,
            enqueue = { action -> controls.execute { action() } },
            post = { action -> main.post { action() } },
            publishRecords = { publishRecords(scheduleExpiry = false) },
            onChanged = {
                pendingSaveRecoveries = resumeHandoff.pending()
                recoveryCancelTaskIds = resumeHandoff.pendingCancellations()
                pendingCommands = taskCommands.pendingTaskIds()
            },
            isRuntimeCurrent = { instance === this },
        )
    }
    internal fun recoveryRetryAction(isPageCurrent: () -> Boolean) = SaveRecoveryRetryAction(
        isCurrent = { ready && foreground && instance === this && isPageCurrent() },
        retry = { ticket, pageCurrent ->
            requireMain()
            resumeHandoff.retry(ticket, pageCurrent)
            pendingCommands = taskCommands.pendingTaskIds()
        },
    )
    var pendingCommands by activityStateOf<Set<String>>(emptySet()); private set
    private val poll = object : Runnable {
        override fun run() {
            if (foreground) { refresh(); main.postDelayed(this, 1_000) }
        }
    }
    init {
        val application = context.applicationContext
        command(mediaWork = true, onRejected = initializationActivity::preparationFailed) {
            try {
                storage = AndroidSaveStorage(application)
                val staging = object : NetworkStagingAccess {
                    override fun length(artifact: StagingArtifact) = storage.stagingLength(artifact)
                    override fun availableBytes(artifact: StagingArtifact) = storage.availableBytes(artifact)
                    override fun open(artifact: StagingArtifact, append: Boolean) = storage.openStaging(artifact, append)
                    override fun rewind(artifact: StagingArtifact, length: Long) = storage.truncateStaging(artifact, length)
                    override fun sync(artifact: StagingArtifact) = storage.syncStaging(artifact)
                }
                transport = FixedDirectTransferPool(
                    SystemHttpsDirectTransfer(staging, leases, networkPolicy = networkPolicy, movAdmissionEnabled = movAdmissionEnabled),
                    SystemHttpsDirectTransfer(staging, leases, networkPolicy = networkPolicy, movAdmissionEnabled = movAdmissionEnabled))
                stream = AndroidHlsStreamPort(application, storage, leases, limits, clock, networkPolicy)
                recorder = AndroidRecordSavePort(application, storage, { canSave(TaskMethod.RECORD) }, ::canPauseRecording) { next ->
                    main.post {
                        if (recordingSnapshot?.revision?.let { it > next.revision } != true) recordingSnapshot = next
                        publishContinuousRecording()
                        val label = recordingNotificationStatus(next.state)
                        next.request?.requestId?.let { id ->
                            recordingService?.showStatus(id, label)
                            if (!executionBusy()) finishRecordingOwnerIfReleased(id)
                        }
                    }
                }
                val executionBridge = AndroidDirectExecutionBridge(executionAdmission, directLocalGate) {
                    directBatchOwner?.let(OrdinaryMediaNativeResources::ownership)
                }
                val direct = storage.controlledDirectComponents(directLocalGate, executionBridge, clock, limits)
                controlledDirectStorage = direct.storage
                val actualVerifier = AndroidLocalMp4Verifier(storage::verificationFile,
                    limits.verificationTimeoutMillis, direct.normalizedOwner, direct.storage::onLegacyReadIssued)
                coordinator = SaveCoordinator(storage, transport,
                    SerialArtifactVerifier(actualVerifier, clock),
                    storage, clock, limits, stream = stream, recording = recorder,
                    networkGate = SaveNetworkGate { networkPolicy.checkAdmission() }, admission = executionAdmission,
                    controlledDirect = ControlledDirectPipeline(directLocalGate, direct.storage, movAdmissionEnabled, conversionExecutionEnabled = false),
                    privateSourceDetachmentObserver = PrivateSourceDetachmentObserver(privateSources::sourceDetached))
                coordinator.recover(operation(2_000))
                coordinator.sweepTerminalCleanup(operation(2_000))
                val (initial, initialActiveCount) = readTaskSnapshot()
                val dispositions = readMediaDispositions(initial)
                // Export initialization is independent of original-media publication. Unavailable export
                // support never turns an existing ordinary save into a false failure or removes it.
                val exports = try { AndroidExportRuntime(application, storage) } catch (_: Exception) { null }
                val destinationRuntime = AndroidOrdinaryDestinationRuntime(application, storage, exports)
                // Display-only adapter reads the existing main-thread language mirror on each notice.
                val runtimeText = androidRuntimeMessageText(application) { notificationLanguage.current }
                val segmentRuntime = try { AndroidSegmentRecordingRuntime(application, storage, ::recordingFeatureAllowed,
                    otherMediaIdle = { ready && foreground && !executionBusy() && currentRecordingId == null && recordingService == null && !resourcesDraining() },
                    released = { id ->
                        if (currentRecordingId == id) { currentRecordingId = null; pendingRecording = null; setExecuting(false) }
                        command { publishRecords() }
                    }, published = { command { publishRecords() } }, runtimeText = runtimeText, activityChanged = ::publishMediaTaskActivity) } catch (_: Exception) { null }
                val editorProbe = InitializationProbe()
                val editInitialization = initializeEditor(editorProbe, storageProbe = { storage.initializeEditStorage(editorProbe); Unit }) {
                    AndroidEditRuntime(application, storage, destinationRuntime, runtimeText) { ready && !executionBusy() &&
                        pendingRecording == null && currentRecordingId == null && !resourcesDraining() }
                }
                initializationActivity.enqueue({ install -> main.post(install) }) {
                    editor = editInitialization.value; editorInitializationStatus = editInitialization.status; editorInitializationDiagnostic = editInitialization.diagnostic; segmentRecording = segmentRuntime; segmentRuntime?.setForeground(foreground)
                    records = initial; activeTaskCount = initialActiveCount; mediaDispositions = dispositions
                    libraryExports = exports; destinations = destinationRuntime; ready = true
                }
            } finally {
                // Failure before posting has no continuing owner. A posted installation owns
                // priority until its main-thread finally transfers to the installed segment owner.
                initializationActivity.preparationFailed()
            }
        }
    }
    fun setForeground(value: Boolean) {
        requireMain()
        if (foreground != value) { authenticationNavigationGeneration++; executionForegroundGeneration++ }
        foreground = value; segmentRecording?.setForeground(value); main.removeCallbacks(poll)
        if (value) { main.post(poll) }
        else {
            clearAlternativeSelection()
            taskCommands.retireResumesForBackground()
            authenticationOrigins.clear(); streamAuthenticationOrigin = null
            destinations?.cancelSelection()
            val ownedPreparation = hlsPreparation
            discoverySaveLinks.discard(streamDiscoveryOrigin); streamDiscoveryOrigin = null
            detector.cancel(); scanning = false; selection = null
            preparationGeneration++; preparationCancellation?.cancel()
            // Keep activity owned until idle prepared state has actually been discarded.
            if (::stream.isInitialized) ownedPreparation?.let(stream::discard)
            hlsPreparation = null; streamSelectionContext = null; preparingStream = false
            preparationExecution?.let { claim ->
                if (preparationWorker !== claim) { preparationExecution = null; releaseExecution(claim) }
            }
            // Accepted tasks pause through the coordinator's token so backgrounding is WAITING_USER,
            // not a user cancellation through the original preparation token.
            if (::coordinator.isInitialized) coordinator.signalBackgroundPauseAll()
            command(mediaWork = true) {
                if (::coordinator.isInitialized) storage.listUnfinished(100).forEach { coordinator.pauseForBackground(it.task.taskId) }
                leases.clear(); publishRecords()
            }
        }
    }
    internal fun authenticationBrowserAvailable(): Boolean = ready && foreground && allowed() &&
        !executionBusy() && !resourcesDraining() && !AndroidLocalMp4Verifier.requiresRestart && !Media3LocalHlsEngine.requiresRestart &&
        pendingCommands.isEmpty() && pendingMediaCommands.get() == 0 &&
        !expiryActivity.active && !initializationActivity.active && !scanning && selection == null &&
        currentRecordingId == null && pendingRecording == null && recordingService == null &&
        !preparingStream && hlsPreparation == null && destinations?.asking != true && destinations?.notice == null &&
        segmentRecording?.let { it.busy || it.recoveryActive || it.nativeOccupied || it.previewActive || it.requiresRecovery } != true

    internal fun authenticationOriginAvailable(taskId: String, browser: SystemBrowserHost): Boolean {
        requireMain()
        val token = authenticationOrigins.token(taskId) ?: return false
        return browser.canSelectAuthenticationOrigin(token)
    }
    internal fun returnToOriginForAuthentication(command: AuthenticationBrowserCommand,
        browser: SystemBrowserHost, expectedBrowserRevision: Long): AuthenticationNavigationResult {
        requireMain()
        if (!authenticationOriginInteractionCurrent(expectedBrowserRevision, browser.saveInspectionRevision))
            return AuthenticationNavigationResult.STALE
        val token = authenticationOrigins.token(command.taskId) ?: return AuthenticationNavigationResult.UNAVAILABLE
        if (!browser.canSelectAuthenticationOrigin(token)) return AuthenticationNavigationResult.UNAVAILABLE
        val accepted = returnToBrowserForAuthentication(command)
        if (accepted != AuthenticationNavigationResult.ACCEPTED) return accepted
        return if (browser.selectAuthenticationOrigin(token, expectedBrowserRevision)) AuthenticationNavigationResult.ACCEPTED
            else AuthenticationNavigationResult.UNAVAILABLE
    }
    private fun captureAuthenticationOrigin(selection: PageSaveSelection, browser: SystemBrowserHost,
        source: String): AuthenticationOriginCapture? = try {
        browser.captureAuthenticationOrigin(selection, source)?.let(authenticationOrigins::capture)
    } catch (_: Exception) { null } // Optional UI provenance must never fail a Save.
    private fun bindAuthenticationOrigin(taskId: String, capture: AuthenticationOriginCapture?,
        existing: Boolean, source: String) {
        if (capture != null) try { authenticationOrigins.accepted(taskId, capture, existing, source) }
        catch (_: Exception) { authenticationOrigins.discard(capture) }
    }
    /** Navigation authority only. No source lookup, credential lease, task command or selection mutation. */
    internal fun returnToBrowserForAuthentication(command: AuthenticationBrowserCommand): AuthenticationNavigationResult {
        requireMain()
        val current = records.filter { it.task.taskId == command.taskId }.singleOrNull()
            ?.takeIf { ::coordinator.isInitialized && authenticationSnapshotCompatible(it.task, coordinator.snapshot(command.taskId)) }
            ?.let(AuthenticationTaskIdentity::from)
        return command.accept(current, authenticationNavigationGeneration, authenticationBrowserAvailable())
    }
    internal fun returnToBrowserAfterFailure(command: FailureBrowserCommand): FailureNavigationResult {
        requireMain()
        val current = records.firstOrNull { it.task.taskId == command.taskId }
            // Old terminal rows may not be present in the coordinator memory cache after restart.
            ?.takeIf { record -> ::coordinator.isInitialized && coordinator.snapshot(command.taskId)?.let { it == record.task } != false }
            ?.let(FailedTaskIdentity::from)
        val result = command.accept(current, ready && foreground && allowed() && !executionBusy() &&
            !resourcesDraining() && pendingCommands.isEmpty() && currentRecordingId == null &&
            pendingRecording == null && recordingService == null && !preparingStream && hlsPreparation == null)
        if (result == FailureNavigationResult.ACCEPTED) {
            // Only retire UI inspection. Never invoke cancel/resume/submit on the failed or active task.
            dismissSelection()
            current?.let { failed ->
                val identity = SaveFailedIdentity(failed.taskId, failed.revision, failed.state, failed.method, failed.error)
                if (SaveMethodAlternatives.review(identity) == SaveAlternativeReview.NEEDS_FRESH_STREAM_SOURCE) {
                    alternativeSelection.arm(identity, clock.nowMillis() + 60_000)
                    alternativeNeedsSource = true
                }
            }
            showFailureBrowserReminder = true
        }
        return result
    }
    fun dismissFailureBrowserReminder() {
        showFailureBrowserReminder = false
        if (hlsPreparation != null || preparingStream) dismissSelection() else clearAlternativeSelection()
    }
    internal fun dismissAlternativeFeedback() { alternativeFeedback = null }
    private fun clearAlternativeSelection() {
        alternativeSelection.clear(); selectionAlternativeExpected = false
        streamAlternative?.revoke(); streamAlternative = null; streamAlternativeGeneration = null
        alternativeNeedsSource = false; alternativePrepared = false
    }
    private fun inspectionPage(browser: SystemBrowserHost): SaveInspectionPage? = browser.saveInspectionPage()
    fun inspectFromUserClick(browser: SystemBrowserHost) {
        requireMain()
        showFailureBrowserReminder = false
        if (!ready || !foreground || !allowed()) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SAVE_UNAVAILABLE); return }
        if (scanning) return
        streamAlternative?.revoke(); streamAlternative = null; streamAlternativeGeneration = null
        val expectedAlternativeInspection = alternativeNeedsSource
        alternativePrepared = false; alternativeNeedsSource = false; selectionAlternativeExpected = false
        val alternativeInspection = alternativeSelection.beginInspection(clock.nowMillis())
        if (expectedAlternativeInspection && alternativeInspection == null) {
            alternativeFeedback = SaveAlternativeFeedback.UNAVAILABLE; return
        }
        val captured = inspectionPage(browser)
        val view = browser.selectedView()
        if (captured == null || !captured.isUsable() || view == null) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.OPEN_LOADED_PAGE); return
        }
        val pageUrl = captured.pageUrl ?: return
        fun isCurrent(): Boolean = captured.matches(inspectionPage(browser), foreground)
        scanning = true; selection = null; notice = null
        detector.inspectFromUserClick(view, captured.context, captured.epoch, pageUrl, ::isCurrent) { result ->
            scanning = false
            when (result) {
                is PageSaveDetection.Found -> {
                    when (resolveAlternativeInspection(alternativeSelection, alternativeInspection,
                        result.selection, clock.nowMillis(), result.selection.isCurrent())) {
                        SaveAlternativeInspectionDelivery.REJECTED -> {
                            // A carried but stale intent cannot silently become an ordinary save.
                            // Leave any newer selection/owner untouched by this late delivery.
                            alternativeFeedback = SaveAlternativeFeedback.UNAVAILABLE
                        }
                        SaveAlternativeInspectionDelivery.ALTERNATIVE -> {
                            selectionAlternativeExpected = true; selection = result.selection
                        }
                        SaveAlternativeInspectionDelivery.ORDINARY -> {
                            selectionAlternativeExpected = false; selection = result.selection
                        }
                    }
                }
                is PageSaveDetection.Unavailable -> notice = SaveRuntimeNotice.DetectionUnavailable(result.reason)
            }
        }
    }
    fun dismissSelection() {
        requireMain()
        clearAlternativeSelection()
        val ownedPreparation = hlsPreparation
        val ownedCancellation = preparationCancellation
        discoverySaveLinks.discard(streamDiscoveryOrigin); streamDiscoveryOrigin = null
        authenticationOrigins.discard(streamAuthenticationOrigin); streamAuthenticationOrigin = null
        detector.cancel(); scanning = false; selection = null
        preparationCancellation = null; preparationGeneration++
        ownedCancellation?.cancel()
        // Old dialog callbacks lose ownership at variant handoff. They cannot cancel an accepted task.
        if (::stream.isInitialized) ownedPreparation?.let(stream::discard)
        hlsPreparation = null; streamSelectionContext = null; preparingStream = false
        preparationExecution?.let { claim ->
            if (preparationWorker !== claim) { preparationExecution = null; releaseExecution(claim) }
        }
    }
    fun dismissNotice() { notice = null }
    fun initializeSaveSettings(settings: SaveSettings): SaveSettings {
        requireMain()
        val merged = saveSettingsOwner.initialize(settings)
        networkPolicy.update(merged)
        executionAdmission.updateMaxDirectTasks(merged.maxConcurrentDirectTasks)
        return merged
    }
    fun updateSaveSettings(settings: SaveSettings) {
        requireMain(); networkPolicy.update(saveSettingsOwner.updateWifiOnly(settings.wifiOnly))
        // Active requests observe this immediately through their per-request binding/watchdog.
        // Do not cancel the shared operation: recording, remux, verify and commit are local work.
    }
    fun updateDirectConcurrency(value: Int): SaveSettings? {
        requireMain()
        val updated = saveSettingsOwner.updateConcurrency(value) ?: return null
        executionAdmission.updateMaxDirectTasks(updated.maxConcurrentDirectTasks)
        publishMediaTaskActivity()
        return updated
    }
    private fun networkSaveAllowed(showNotice: Boolean = true): Boolean = try {
        networkPolicy.checkAdmission(); true
    } catch (_: SaveFailure) {
        if (showNotice) main.post { notice = SaveRuntimeNotice.Fixed(if (networkPolicy.initialized)
            SaveRuntimeFixedNotice.WIFI_REQUIRED else SaveRuntimeFixedNotice.WIFI_SETTINGS_UNAVAILABLE) }
        false
    }
    fun choose(candidate: PageSaveCandidate, browser: SystemBrowserHost) {
        requireMain()
        val captured = selection ?: return
        if (!captured.isCurrent() || captured.candidates.none { it === candidate }) { selection = null; notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.CANDIDATE_EXPIRED); return }
        if (candidate.protected || !candidate.supported) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.CANDIDATE_PROTECTED_OR_UNSUPPORTED); return }
        if (!canSave(candidate.method)) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_ENTITLEMENT_REQUIRED); return }
        if (candidate.method == TaskMethod.STREAM) { prepareStream(candidate, captured, browser); return }
        clearAlternativeSelection()
        destinations?.select(isCurrent = { foreground && selection === captured && captured.isCurrent() },
            accepted = { admission -> chooseDirect(candidate, captured, browser, admission) })
    }
    private fun chooseDirect(candidate: PageSaveCandidate, captured: PageSaveSelection, browser: SystemBrowserHost,
        admission: OrdinaryDestinationAdmission) {
        requireMain()
        if (selection !== captured || !captured.isCurrent()) return
        if (!networkSaveAllowed()) return
        if (!directAdmissionAvailable()) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.MEDIA_RESOURCES_EXITING); return }
        if (!SaveTaskCapacity.hasRoom(activeTaskCount, limits.maxJournalEntries)) { notice = SaveRuntimeNotice.TaskCapacityReached(limits.maxJournalEntries); return }
        val generation = executionForegroundGeneration
        // Disk admission is worker-only. No credential lease or durable task exists before it passes.
        selection = null
        command(mediaWork = true) {
            if (!foreground || executionForegroundGeneration != generation) return@command
            // Existing/orphan durable work never mints another browser credential session.
            when (inspectDirectCreationNetwork(
                readExisting = { storage.read(admission.creationId) != null },
                inspectWorkspace = { storage.inspectNormalizationPresence(admission.creationId, null, admission.creationId) },
            )) {
                DirectCreationNetworkDecision.EXISTING -> {
                    main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.DIRECT_TASK_ACCEPTED) }
                    publishRecords(); return@command
                }
                DirectCreationNetworkDecision.RECOVERY -> {
                    main.post { notice = saveRuntimeFailureNotice(SaveError.RECOVERY_REQUIRED) }; return@command
                }
                DirectCreationNetworkDecision.FRESH -> Unit
            }
            val claim = executionAdmission.tryAcquireDirect()
            if (claim == null) { main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SAVE_OPERATION_BUSY) }; return@command }
            publishMediaTaskActivity()
            if (!main.post {
                if (!foreground || executionForegroundGeneration != generation || !captured.isCurrent() || !allowed() || !networkSaveAllowed()) {
                    releaseExecution(claim)
                } else continueDirectAdmission(candidate, captured, browser, admission, claim, generation)
            }) releaseExecution(claim)
        }
    }
    private fun continueDirectAdmission(candidate: PageSaveCandidate, captured: PageSaveSelection, browser: SystemBrowserHost,
        admission: OrdinaryDestinationAdmission, claim: SaveExecutionAdmission.Claim, generation: Long) {
        requireMain()
        val id = admission.creationId
        val context = operation()
        val capturedSource = try {
            captureNetworkSource(candidate.url, captured, browser, claim) {}.also {
                it.privateSource?.bindTask(id, context.operationId)
            }
        } catch (_: Exception) {
            releaseExecution(claim); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.DIRECT_REQUEST_SESSION_UNAVAILABLE); return
        }
        val lease = capturedSource.lease
        val request = SaveRequest(id, id, SelectedMediaResource(candidate.id, candidate.id, candidate.url,
            expectedAudio = candidate.expectedAudio, requestContextRef = lease,
            sourceIdentity = capturedSource.privateSource?.seed?.sourceIdentity?.encoded ?: candidate.url.identity(),
            privateSource = capturedSource.privateSource?.binding))
        val saveOrigin = if (capturedSource.privateSource == null) try { browser.captureSaveOrigin() } catch (_: Exception) { null } else null
        val authenticationOrigin = if (capturedSource.privateSource == null) captureAuthenticationOrigin(captured, browser, candidate.url.value) else null
        command(mediaWork = true, onRejected = {
            leases.revoke(lease); discoverySaveLinks.discard(saveOrigin); authenticationOrigins.discard(authenticationOrigin)
            releaseExecution(claim)
        }) {
            var acceptedTaskId: String? = null
            var launched = false
            try {
                if (!foreground || executionForegroundGeneration != generation || !allowed()) return@command
                when (val result = coordinator.submit(request, context, claim)) {
                    is SaveSubmission.Accepted -> {
                        // An idempotent existing row is not a fresh execution and cannot inherit this claim.
                        if (!result.existing) {
                            acceptedTaskId = result.record.task.taskId
                            discoverySaveLinks.accepted(result.record.task.taskId, saveOrigin, false)
                            bindAuthenticationOrigin(result.record.task.taskId, authenticationOrigin, false, candidate.url.value)
                            launched = launchExecution(result.record.task.taskId, lease, claim)
                        }
                        if (launched || result.existing) main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.DIRECT_TASK_ACCEPTED) }
                    }
                    is SaveSubmission.Rejected -> main.post { notice = saveRuntimeFailureNotice(result.error) }
                }
                publishRecords()
            } finally {
                if (!launched) {
                    acceptedTaskId?.let(discoverySaveLinks::cancel)
                    discoverySaveLinks.discard(saveOrigin); leases.revoke(lease)
                    acceptedTaskId?.let(authenticationOrigins::cancel)
                    authenticationOrigins.discard(authenticationOrigin)
                    releaseExecution(claim)
                }
            }
        }
    }
    private fun prepareStream(candidate: PageSaveCandidate, captured: PageSaveSelection, browser: SystemBrowserHost) {
        if (!networkSaveAllowed()) return
        val alternative = alternativeSelection.association(captured, clock.nowMillis(), captured.isCurrent())
        if (selectionAlternativeExpected && alternative == null) {
            dismissSelection(); alternativeFeedback = SaveAlternativeFeedback.UNAVAILABLE; return
        }
        if (!foreground || !ready || !canSave(TaskMethod.STREAM) || resourcesDraining() || !reserveExecution()) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_UNAVAILABLE); return
        }
        val execution = requireNotNull(exclusiveExecution)
        streamAlternativeRequired = alternative != null
        preparationExecution = execution
        val cancellation = CancellationSource()
        val operation = operation().copy(cancelToken = cancellation)
        val capturedSource = try { captureNetworkSource(candidate.url, captured, browser, execution, cancellation::cancel) }
        catch (_: Exception) {
            preparationExecution = null; releaseExecution(execution); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_REQUEST_SESSION_UNAVAILABLE); return
        }
        val lease = capturedSource.lease
        val privateSource = capturedSource.privateSource
        val saveOrigin = if (privateSource == null) try { browser.captureSaveOrigin() } catch (_: Exception) { null } else null
        discoverySaveLinks.discard(streamDiscoveryOrigin); streamDiscoveryOrigin = saveOrigin
        authenticationOrigins.discard(streamAuthenticationOrigin)
        val authenticationOrigin = if (privateSource == null) captureAuthenticationOrigin(captured, browser, candidate.url.value) else null
        streamAuthenticationOrigin = authenticationOrigin
        preparationCancellation = cancellation
        val token = ++preparationGeneration
        val source = SelectedMediaResource(candidate.id, candidate.id, candidate.url,
            expectedAudio = candidate.expectedAudio, requestContextRef = lease,
            sourceIdentity = privateSource?.seed?.sourceIdentity?.encoded ?: candidate.url.identity(), privateSource = privateSource?.seed)
        selection = null; hlsPreparation = null; preparingStream = true; streamSelectionContext = captured
        preparationWorker = execution
        try { worker.execute {
            var proposed: StreamAlternativeProposal? = null
            try {
                if (!foreground || !canSave(TaskMethod.STREAM)) throw SaveFailure(SaveError.INVALID_REQUEST)
                networkPolicy.checkAdmission()
                val prepared = stream.prepare(source, operation)
                privateSource?.attachPreparation(prepared)
                if (alternative != null) {
                    when (val result = coordinator.prepareStreamAlternative(alternative.failed, alternative.generation,
                        source, stream.operation(prepared), minOf(alternative.expiresAt, captured.expiresAtMillis, prepared.expiresAtMillis))) {
                        is SaveAlternativePreparation.Ready -> proposed = result.proposal
                        is SaveAlternativePreparation.Rejected -> { stream.discard(prepared); throw SaveFailure(result.error) }
                    }
                }
                val preparedProposal = proposed
                main.post {
                    val alternativeCurrent = alternative == null || alternativeSelection.association(captured,
                        clock.nowMillis(), captured.isCurrent())?.generation == alternative.generation
                    if (token == preparationGeneration && foreground && captured.isCurrent() && canSave(TaskMethod.STREAM) && networkSaveAllowed() && alternativeCurrent) {
                        hlsPreparation = prepared
                        streamAlternative = preparedProposal; streamAlternativeGeneration = alternative?.generation
                        alternativePrepared = preparedProposal != null
                    } else {
                        preparedProposal?.revoke()
                        stream.discard(prepared); discoverySaveLinks.discard(saveOrigin)
                        authenticationOrigins.discard(authenticationOrigin)
                        if (streamAuthenticationOrigin === authenticationOrigin) streamAuthenticationOrigin = null
                        if (streamDiscoveryOrigin === saveOrigin) streamDiscoveryOrigin = null
                    }
                }
            } catch (failure: Exception) {
                proposed?.revoke()
                discoverySaveLinks.discard(saveOrigin)
                authenticationOrigins.discard(authenticationOrigin)
                main.post {
                    if (streamAuthenticationOrigin === authenticationOrigin) streamAuthenticationOrigin = null
                    if (streamDiscoveryOrigin === saveOrigin) streamDiscoveryOrigin = null
                    if (token == preparationGeneration) {
                        if (alternative != null) alternativeFeedback = SaveAlternativeFeedback.UNAVAILABLE
                        notice = saveRuntimeFailureNotice(SaveErrors.classify(failure))
                    }
                }
                leases.revoke(lease)
            } finally {
                // Deliver/discard the prepared resource on main before releasing this owner.
                // A dialog cancellation must not expose an idle gap before a late result is retired.
                main.post {
                    if (preparationWorker === execution) preparationWorker = null
                    if (token == preparationGeneration) preparingStream = false
                    if (preparationExecution !== execution || hlsPreparation == null) {
                        if (preparationExecution === execution) preparationExecution = null
                        releaseExecution(execution)
                    }
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            cancellation.cancel(); leases.revoke(lease)
            if (token == preparationGeneration) dismissSelection()
            if (preparationWorker === execution) preparationWorker = null
            if (preparationExecution === execution) preparationExecution = null
            releaseExecution(execution)
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_PREPARATION_QUEUE_UNAVAILABLE)
        }
    }
    fun chooseHlsVariant(identity: String?) {
        requireMain()
        val prepared = hlsPreparation ?: return
        val captured = streamSelectionContext ?: return
        if (!foreground || !captured.isCurrent() || !canSave(TaskMethod.STREAM) || preparingStream || preparationExecution?.let(executionAdmission::owns) != true || resourcesDraining()) {
            dismissSelection(); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_CONTEXT_CHANGED); return
        }
        if (!SaveTaskCapacity.hasRoom(activeTaskCount, limits.maxJournalEntries)) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.TASK_CAPACITY_FULL); return }
        destinations?.select(isCurrent = { foreground && hlsPreparation === prepared && streamSelectionContext === captured && captured.isCurrent() },
            accepted = { admission -> chooseHlsVariantSelected(identity, prepared, captured, admission) })
    }
    private fun chooseHlsVariantSelected(identity: String?, prepared: HlsPreparation, captured: PageSaveSelection,
        admission: OrdinaryDestinationAdmission) {
        requireMain()
        if (!foreground || hlsPreparation !== prepared || streamSelectionContext !== captured || !captured.isCurrent() ||
            !canSave(TaskMethod.STREAM) || resourcesDraining()) { dismissSelection(); return }
        if (!networkSaveAllowed()) return
        if (streamAlternativeRequired && streamAlternative == null) {
            dismissSelection(); alternativeFeedback = SaveAlternativeFeedback.UNAVAILABLE; return
        }
        val execution = preparationExecution?.takeIf(executionAdmission::owns)
        if (execution == null || preparingStream) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SAVE_OPERATION_BUSY); return }
        val operation = stream.operation(prepared)
        val id = admission.creationId
        val resource = try {
            val sourceOwner = privateSources.forClaim(execution)
            if (prepared.source.privateSource != null && (sourceOwner == null || sourceOwner.seed !== prepared.source.privateSource || !sourceOwner.ownsPreparation(prepared)))
                throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            val binding = sourceOwner?.bindTask(id, operation.operationId)
            stream.select(prepared, identity, binding)
        } catch (failure: Exception) {
            dismissSelection(); releaseExecution(execution); notice = saveRuntimeFailureNotice(SaveErrors.classify(failure)); return
        }
        val generation = executionForegroundGeneration
        val request = SaveRequest(id, id, resource, TaskMethod.STREAM)
        val alternative = streamAlternative
        val alternativeGeneration = streamAlternativeGeneration
        // The chosen variant and destination creationId are fixed; the transferred proposal
        // cannot be revoked by a later dialog close belonging to another selection.
        streamAlternative = null; streamAlternativeGeneration = null; alternativePrepared = false
        streamAlternativeRequired = false; selectionAlternativeExpected = false
        alternativeSelection.clear()
        val saveOrigin = streamDiscoveryOrigin
        streamDiscoveryOrigin = null
        val authenticationOrigin = streamAuthenticationOrigin
        streamAuthenticationOrigin = null
        // Fixed variant is now an independent task; later tab changes do not change that selection.
        preparationExecution = null
        hlsPreparation = null; streamSelectionContext = null; preparationCancellation = null
        preparingStream = false; preparationGeneration++
        command(mediaWork = true, onRejected = {
            alternative?.revoke()
            discoverySaveLinks.discard(saveOrigin)
            authenticationOrigins.discard(authenticationOrigin)
            try { stream.discard(prepared); resource.requestContextRef?.let(leases::revoke) } finally { releaseExecution(execution) }
        }) {
            var launched = false
            var acceptedTaskId: String? = null
            try {
                if (!foreground || generation != executionForegroundGeneration || !canSave(TaskMethod.STREAM)) {
                    stream.discard(prepared); resource.requestContextRef?.let(leases::revoke); return@command
                }
                val submission = if (alternative == null) coordinator.submit(request, operation, execution, prepared.bytesTransferred)
                else when (val confirmed = coordinator.confirmStreamAlternative(alternative,
                    requireNotNull(alternativeGeneration), request, operation, execution, prepared.bytesTransferred)) {
                    is SaveAlternativeConfirmation.Accepted -> SaveSubmission.Accepted(confirmed.record, false)
                    is SaveAlternativeConfirmation.Rejected -> {
                        main.post { alternativeFeedback = SaveAlternativeFeedback.UNAVAILABLE }
                        return@command
                    }
                    is SaveAlternativeConfirmation.OutcomeUnknown -> {
                        // Never dispatch/re-submit/cancel a possibly persisted row. This worker has
                        // not been handed off, so only its untransferred preparation can be retired.
                        // Existing startup recovery will surface that exact fixed ID if it exists.
                        main.post { unknownAlternativeTaskId = confirmed.taskId; alternativeFeedback = SaveAlternativeFeedback.OUTCOME_UNKNOWN }
                        try { publishRecords() } catch (_: Exception) { /* Result remains unknown. */ }
                        return@command
                    }
                }
                when (val result = submission) {
                    is SaveSubmission.Accepted -> {
                        if (result.existing) return@command
                        acceptedTaskId = result.record.task.taskId
                        discoverySaveLinks.accepted(result.record.task.taskId, saveOrigin, result.existing)
                        bindAuthenticationOrigin(result.record.task.taskId, authenticationOrigin, result.existing, resource.url.value)
                        launched = launchExecution(result.record.task.taskId, resource.requestContextRef, execution)
                        if (launched) main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_TASK_ACCEPTED) }
                    }
                    is SaveSubmission.Rejected -> {
                        stream.discard(prepared); resource.requestContextRef?.let(leases::revoke)
                        main.post { notice = saveRuntimeFailureNotice(result.error) }
                    }
                }
                publishRecords()
            } finally {
                if (!launched) {
                    acceptedTaskId?.let(discoverySaveLinks::cancel)
                    discoverySaveLinks.discard(saveOrigin)
                    acceptedTaskId?.let(authenticationOrigins::cancel)
                    authenticationOrigins.discard(authenticationOrigin)
                    alternative?.revoke()
                    // No worker handoff occurred, including OUTCOME_UNKNOWN. Resource retirement
                    // does not imply the durable new task was absent or rejected.
                    // Discard while the execution reservation still owns task priority.
                    try { stream.discard(prepared); resource.requestContextRef?.let(leases::revoke) }
                    finally { releaseExecution(execution) }
                }
            }
        }
    }

    fun cancel(taskId: String) {
        requireMain()
        val continuous = continuousRecordingOwner?.takeIf { it.descriptor.sessionId == taskId }
        // Cached final publication decision wins; late Discard never reaches storage/media deletion.
        if (continuous != null && ::coordinator.isInitialized && coordinator.snapshot(taskId)?.state?.isTerminal == true) {
            resumeHandoff.cancellationConfirmed(taskId)
            publishContinuousRecording(); return
        }
        if (continuous?.terminal != null) { publishContinuousRecording(); return }
        continuous?.requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)
        privateRecordingBinding?.takeIf { it.descriptor.sessionId == taskId }
            ?.requestStop(PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)
        if (::coordinator.isInitialized) coordinator.signalUserCancel(taskId)
        publishContinuousRecording()
        privateSources.retireTask(taskId)
        if (::coordinator.isInitialized) resumeHandoff.cancel(taskId)
        authenticationOrigins.cancel(taskId)
        discoverySaveLinks.cancel(taskId)
        if (segmentRecording?.owns(taskId) == true) { segmentRecording?.discard(taskId); return }
        if (pendingRecording?.sessionId == taskId && recordingService == null) { recorder.cancel(taskId); abandonRecordingRequest(taskId); return }
        if (::coordinator.isInitialized && coordinator.snapshot(taskId)?.state?.isTerminal == true) {
            resumeHandoff.cancellationConfirmed(taskId)
            command { publishRecords() }; return
        }
        if (currentRecordingId == taskId) recorder.cancel(taskId)
        // The immediate signal above precedes this command gate and any durable/journal IO.
        val attempt = taskCommands.tryCancel(taskId) ?: return
        pendingCommands = taskCommands.pendingTaskIds()
        command(mediaWork = true, onRejected = { finishTaskCommand(attempt) }) {
            try {
                if (coordinator.cancel(taskId)?.state?.isTerminal == true) resumeHandoff.cancellationConfirmed(taskId)
                publishRecords()
            } finally { main.post { finishTaskCommand(attempt) } }
        }
    }
    fun resume(taskId: String, browser: SystemBrowserHost) {
        requireMain()
        val record = records.singleOrNull { it.task.taskId == taskId } ?: return
        if (record.task.state != TaskState.WAITING_USER) return
        val direct = record.request.method == TaskMethod.DIRECT
        if (!foreground || !ready || !canResumeFinalized(record) ||
            (if (direct) !directAdmissionAvailable() else executionBusy() || resourcesDraining())) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.MEDIA_RESOURCES_EXITING); return
        }
        if (record.needsNetworkTransfer() && !networkSaveAllowed()) return
        val source = coordinator.sourceForTask(taskId)
        if (record.request.method == TaskMethod.RECORD && record.transfer == null) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_RESTART_REQUIRES_FRESH_CONSENT); return
        }
        if (record.request.method == TaskMethod.STREAM && record.transfer == null) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.STREAM_RESELECT_REQUIRED); return
        }
        if (source == null && record.transfer == null) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SOURCE_RESELECT_AFTER_RESTART); return
        }
        if (!record.request.isPrivateNetwork && record.waitReason == SaveWaitReason.AUTHENTICATION && source != null &&
            !sameOrigin(browser.state.selectedTab?.address?.value, source.url.value)) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SAME_ORIGIN_LOGIN_REQUIRED); return
        }
        val attempt = taskCommands.tryResume(record.task) ?: return
        pendingCommands = taskCommands.pendingTaskIds()
        val generation = executionForegroundGeneration
        // Reserve and probe storage on controls before creating a fresh request-context lease.
        command(mediaWork = true, onRejected = { finishTaskCommand(attempt) }) {
            val claim = if (foreground && generation == executionForegroundGeneration && taskCommands.isCurrent(attempt)) {
                if (direct) executionAdmission.tryAcquireDirect()
                else if (reserveExecution()) exclusiveExecution else null
            } else null
            if (claim == null) {
                main.post { finishTaskCommand(attempt); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SAVE_OPERATION_BUSY) }
                return@command
            }
            val allowNetworkLease = record.transfer == null && (!direct ||
                storage.inspectNormalizationPresence(taskId, record.artifact, record.commitId) == DirectWorkspacePresence.ABSENT)
            publishMediaTaskActivity()
            if (!main.post {
                if (!foreground || generation != executionForegroundGeneration || !taskCommands.isCurrent(attempt)) {
                    releaseExecution(claim); finishTaskCommand(attempt)
                } else continueResumedAdmission(record, source, browser, attempt, claim, generation, allowNetworkLease)
            }) releaseExecution(claim)
        }
    }
    private fun continueResumedAdmission(record: SaveRecord, source: SelectedMediaResource?, browser: SystemBrowserHost,
        attempt: SaveTaskCommandGate.Attempt, claim: SaveExecutionAdmission.Claim, generation: Long,
        allowNetworkLease: Boolean) {
        requireMain()
        val taskId = record.task.taskId
        val resumedOperation = (if (record.request.method == TaskMethod.RECORD)
            operation(limits.verificationTimeoutMillis + 30_000) else operation())
            .copy(cancelToken = attempt.cancelToken)
        val lease = try {
            if (record.request.isPrivateNetwork && record.transfer == null) {
                val entry = privateSources.forTask(taskId) ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                if (source?.privateSource !== entry.binding || !privateSources.resumeQueued(entry, claim, resumedOperation.operationId))
                    throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                if (allowNetworkLease) {
                    val refreshed = entry.refreshForExplicitResume() ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                    entry.issueLease(claim, refreshed)
                } else null
            } else if (allowNetworkLease) source?.let { leaseFor(it.url, browser) } else null
        } catch (_: Exception) {
            releaseExecution(claim); finishTaskCommand(attempt)
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RESUME_REQUEST_SESSION_UNAVAILABLE); return
        }
        val execution = SaveResumeExecutionReservation { releaseExecution(claim) }
        command(mediaWork = true, onRejected = {
            execution.release(); finishTaskCommand(attempt); lease?.let(leases::revoke)
        }) {
            var launched = false
            try {
                val current = coordinator.record(taskId)
                if (current != null && foreground && generation == executionForegroundGeneration &&
                    taskCommands.canResume(attempt, current.task) && canResumeFinalized(current) &&
                    executionAdmission.owns(claim) && !executionReleaseUnconfirmed()) {
                    val operation = resumedOperation
                    val resumed = coordinator.resume(taskId, operation, claim, lease)
                    if (attempt.ownsResumed(resumed, operation)) {
                        when {
                            attempt.cancelToken.isCancelled -> coordinator.cancel(taskId)
                            else -> launched = launchResumedExecution(requireNotNull(resumed), operation, attempt, lease, execution, claim)
                        }
                    }
                }
                publishRecords()
            } finally {
                if (!launched) { execution.release(); lease?.let(leases::revoke) }
                main.post { finishTaskCommand(attempt) }
            }
        }
    }

    private fun finishTaskCommand(attempt: SaveTaskCommandGate.Attempt) {
        requireMain()
        if (taskCommands.release(attempt)) pendingCommands = taskCommands.pendingTaskIds()
    }

    private fun launchExecution(taskId: String, lease: RequestContextRef?, claim: SaveExecutionAdmission.Claim): Boolean {
        if (!foreground) {
            discoverySaveLinks.cancel(taskId)
            lease?.let(leases::revoke); deferUnstartedExecution(taskId); return false
        }
        if (coordinator.record(taskId)?.needsNetworkTransfer() == true && !networkSaveAllowed()) {
            discoverySaveLinks.cancel(taskId)
            lease?.let(leases::revoke); coordinator.deferForNetwork(taskId); return false
        }
        val accepted = SaveWorkerHandoff().launch({ worker.execute(it) }) {
            runSaveExecution(taskId, lease, claim) { releaseExecution(claim) }
        }
        if (!accepted) {
            discoverySaveLinks.cancel(taskId)
            lease?.let(leases::revoke); deferUnstartedExecution(taskId)
            main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.EXECUTION_QUEUE_UNAVAILABLE) }
        }
        return accepted
    }
    /** Only the exact newly resumed operation receives pre-admission recovery authority. */
    private fun launchResumedExecution(record: SaveRecord, operation: OperationContext,
        attempt: SaveTaskCommandGate.Attempt, lease: RequestContextRef?, execution: SaveResumeExecutionReservation,
        claim: SaveExecutionAdmission.Claim): Boolean =
        resumeHandoff.launch(record, operation, attempt,
            foreground = { foreground }, networkAllowed = { networkSaveAllowed() },
            submit = { job -> worker.execute(job) },
            work = { runSaveExecution(record.task.taskId, lease, claim, execution::release) },
            releaseUnaccepted = {
                discoverySaveLinks.cancel(record.task.taskId)
                execution.release(); lease?.let(leases::revoke)
            },
        )

    /** Shared accepted-worker path. Its first-read failures are outside unadmitted recovery. */
    private fun runSaveExecution(taskId: String, lease: RequestContextRef?, claim: SaveExecutionAdmission.Claim, releaseExecution: () -> Unit) {
        var executed = false
        try {
            // Covers a submit racing the immediate background-memory fence.
            if (!foreground) deferUnstartedExecution(taskId)
            coordinator.execute(taskId, claim)
            executed = true
        } catch (_: Exception) { main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.TASK_STATE_READ_FAILED) } }
        finally {
            // Discovery cannot retry Save or alter an already committed media result.
            if (executed) discoverySaveLinks.afterExecution(taskId) { coordinator.record(taskId) }
            else discoverySaveLinks.cancel(taskId)
            try {
                lease?.let(leases::revoke)
                // Private sessions are cleared by exact positive retirement below. discard() would
                // cancel the preparation token, including an output-only continuation.
                if (!executionAdmission.isDirect(claim) && ::stream.isInitialized && !stream.isDraining &&
                    privateSources.forClaim(claim) == null) stream.discardIdle()
            } finally { releaseExecution(); command { publishRecords() } }
        }
    }

    /** Scheduling deferral is separate from pausing an active capture or cancelling output. */
    private fun deferUnstartedExecution(taskId: String) {
        val record = coordinator.record(taskId)
        if (record?.request?.method == TaskMethod.RECORD && record.transfer != null)
            coordinator.deferFinalizedRecording(taskId)
        else coordinator.pauseForBackground(taskId)
    }
    private class CapturedNetworkSource(val lease: RequestContextRef,
        val privateSource: PrivateNetworkRuntimeSources<HlsPreparation>.Entry?)

    /** New saves always capture the exact selected document's partition; null CDN Cookie is valid. */
    private fun captureNetworkSource(url: SourceUrl, captured: PageSaveSelection, browser: SystemBrowserHost,
        claim: SaveExecutionAdmission.Claim, stopPreparation: () -> Unit): CapturedNetworkSource {
        requireMain()
        val context = browser.captureSaveRequestContext(captured, url.value)
            ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        val entry = if (context.partition is BrowserSavePartition.Private)
            privateSources.register(context, url, claim, stopPreparation) else null
        val lease = entry?.issueLease(claim, context)
            ?: leases.put(url, cookie = context.cookie, userAgent = context.userAgent, ttlMillis = 60_000)
        return CapturedNetworkSource(lease, entry)
    }

    /** Legacy ordinary explicit Resume only. Never inspect a private View/default-cookie fallback. */
    private fun leaseFor(url: SourceUrl, browser: SystemBrowserHost): RequestContextRef {
        if (browser.isPrivateMode) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        val page = browser.state.selectedTab
        val view = browser.selectedView()
        val cookie = if (page?.loadState == BrowserLoadState.READY && view?.url == page.address?.value && sameOrigin(page.address?.value, url.value))
            try { CookieManager.getInstance().getCookie(url.value) } catch (_: RuntimeException) { null } else null
        return leases.put(url, cookie = cookie, userAgent = view?.settings?.userAgentString, ttlMillis = 60_000)
    }
    fun canSave(method: TaskMethod): Boolean {
        val feature = when (method) { TaskMethod.DIRECT -> Feature.DIRECT_SAVE; TaskMethod.STREAM -> Feature.STREAM_SAVE; TaskMethod.RECORD -> Feature.RECORD_SAVE; else -> return false }
        return gate.evaluate(FeatureRequest(feature), entitlements.snapshot(),
            PlatformCapabilities(setOf(Feature.DIRECT_SAVE, Feature.STREAM_SAVE, Feature.RECORD_SAVE, Feature.SEGMENT_RECORDING)), BuildFeaturePolicy(), clock.nowMillis()).allowed
    }

    /** Reserves the single application media owner only after a visible explicit start command. */
    fun prepareRecording(choice: RecordingAudioChoice, width: Int, height: Int, density: Int, admission: OrdinaryDestinationAdmission): String? =
        prepareRecordingOwned(choice, width, height, density, admission, null, null)

    internal fun preparePrivateRecording(choice: RecordingAudioChoice, width: Int, height: Int, density: Int,
        admission: OrdinaryDestinationAdmission, createBinding: (CaptureDescriptor) -> PrivateRecordingNativeBinding?,
        serviceReady: (PrivateRecordingNativeBinding, Any) -> Unit): String? =
        prepareRecordingOwned(choice, width, height, density, admission, createBinding, serviceReady)

    private fun prepareRecordingOwned(choice: RecordingAudioChoice, width: Int, height: Int, density: Int,
        admission: OrdinaryDestinationAdmission, createBinding: ((CaptureDescriptor) -> PrivateRecordingNativeBinding?)?,
        serviceReady: ((PrivateRecordingNativeBinding, Any) -> Unit)?): String? {
        requireMain()
        if (!ready || !foreground || !canSave(TaskMethod.RECORD) || resourcesDraining() ||
            VerifiedMediaPreviewResources.isOccupied || pendingRecording != null || currentRecordingId != null || recordingService != null || !reserveExecution()) {
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_UNAVAILABLE); return null
        }
        if (!SaveTaskCapacity.hasRoom(activeTaskCount, limits.maxJournalEntries) || width <= 0 || height <= 0 || density <= 0) {
            setExecuting(false); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_CAPACITY_OR_GEOMETRY_INVALID); return null
        }
        privateRecordingBinding = null; privateRecordingReady = null; privateRecordingSubmitted = false
        continuousRecordingOwner = null; publishContinuousRecording()
        try {
            // A truthful initial canvas intent, then CodecProfileSelector checks real supported alignment.
            val scale = minOf(1.0, 1920.0 / maxOf(width, height))
            val w = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val h = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val id = admission.creationId
            val descriptor = CaptureDescriptor(id, ++recordingGeneration, choice, RecordingProfile(w, h))
            val audioAllowed = choice == RecordingAudioChoice.NONE ||
                application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val binding = if (createBinding != null) {
                // Publish only the exact reserved identity, never permission to acquire native resources.
                pendingRecording = descriptor; currentRecordingId = id
                createBinding(descriptor)?.also { privateRecordingBinding = it }?.takeIf { it.descriptor == descriptor && !it.isStopRequested && !it.isArmed }
                    ?: throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
            } else null
            privateRecordingBinding = binding; privateRecordingReady = serviceReady; privateRecordingSubmitted = false
            continuousRecordingOwner = ContinuousRecordingOwner(descriptor, binding?.ownerIdentity ?: Any())
            if (!recorder.prepare(descriptor, audioAllowed, binding)) {
                binding?.closeNeverQueued()
                privateRecordingBinding = null; privateRecordingReady = null
                pendingRecording = null; currentRecordingId = null
                setExecuting(false); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_SESSION_EXITING); return null
            }
            pendingRecording = descriptor; currentRecordingId = id; notice = null
            main.postDelayed({ if (pendingRecording?.sessionId == id && recordingService == null) abandonRecordingRequest(id) }, 120_000)
            return id
        } catch (_: RuntimeException) {
            privateRecordingBinding?.let { it.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT); it.closeNeverQueued() }
            privateRecordingBinding = null; privateRecordingReady = null
            pendingRecording = null; currentRecordingId = null
            setExecuting(false); notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_CONFIGURATION_INVALID); return null
        }
    }
    fun isRecordingRequestAllowed(id: String): Boolean = if (segmentRecording?.owns(id) == true)
        segmentRecording?.allowsConsent(id) == true else pendingRecording?.sessionId == id && canSave(TaskMethod.RECORD) && !resourcesDraining() &&
            privateRecordingBinding?.isStopRequested != true
    fun requiresDefaultDisplayRecording(id: String): Boolean = segmentRecording?.owns(id) == true
    private fun recordingFeatureAllowed(feature: Feature): Boolean = gate.evaluate(FeatureRequest(feature), entitlements.snapshot(),
        PlatformCapabilities(setOf(Feature.RECORD_SAVE, Feature.SEGMENT_RECORDING, Feature.REGION_RECORDING)),
        BuildFeaturePolicy(), clock.nowMillis()).allowed
    fun canRegionRecording(): Boolean = recordingFeatureAllowed(Feature.REGION_RECORDING)
    fun prepareRegionRecording(choice: RecordingAudioChoice, recipe: RegionCaptureRecipe, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        val id = segmentRecording?.prepare(choice, recipe, admission)
        if (id == null) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.REGION_RECORDING_UNAVAILABLE); return null }
        setExecuting(true); currentRecordingId = id; recordingSnapshot = null; notice = null
        return id
    }
    fun preparePreviewRecording(choice: RecordingAudioChoice, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        val id = segmentRecording?.preparePreview(choice, admission)
        if (id == null) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.PREVIEW_RECORDING_UNAVAILABLE); return null }
        setExecuting(true); currentRecordingId = id; recordingSnapshot = null; notice = null
        return id
    }
    fun retryRecordedSegments() {
        requireMain(); segmentRecording?.retryProcessing()
        segmentRecording?.requestId?.let { currentRecordingId = it; setExecuting(true) }
    }
    fun nextRecordedSegments() { requireMain(); segmentRecording?.nextRetained() }
    fun discardRecordedSegments() {
        requireMain(); segmentRecording?.discardRetained()
        segmentRecording?.requestId?.let { currentRecordingId = it; setExecuting(true) }
    }
    fun hasPendingRecording(id: String): Boolean = isRecordingRequestAllowed(id) && ProjectionGrantHandoff.contains(id)
    fun receiveProjectionGrant(id: String, resultCode: Int, data: Intent) {
        requireMain()
        if (resultCode != Activity.RESULT_OK || !isRecordingRequestAllowed(id) ||
            !ProjectionGrantHandoff.offer(id, resultCode, data)) { abandonRecordingRequest(id); return }
        try {
            RecordingForegroundService.start(application, id)
            privateRecordingBinding?.takeIf { it.descriptor.sessionId == id }?.let { binding ->
                main.postDelayed({ if (privateRecordingBinding === binding && !privateRecordingSubmitted)
                    stopPrivateRecording(id, binding, PrivateCaptureDisposition.KEEP_VALID_OUTPUT) }, 30_000)
            }
            main.postDelayed({ if ((pendingRecording?.sessionId == id || segmentRecording?.owns(id) == true) && recordingService == null) recordingStartRejected(id) }, 10_000)
        } catch (_: RuntimeException) { recordingStartRejected(id) }
    }
    fun recordingAudioPermissionDenied(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.deny(id); return }
        if (pendingRecording?.sessionId == id && recordingService == null) {
            recorder.denied(id, RecordingError.AUDIO_PERMISSION_DENIED)
            abandonRecordingRequest(id)
        }
    }
    fun abandonRecordingRequest(id: String) {
        requireMain()
        privateRecordingBinding?.takeIf { it.descriptor.sessionId == id }?.let {
            stopPrivateRecording(id, it, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return
        }
        if (segmentRecording?.owns(id) == true) { segmentRecording?.deny(id); return }
        if (pendingRecording?.sessionId != id || recordingService != null) return
        pendingRecording = null; ProjectionGrantHandoff.discard(id); recorder.denied(id)
        currentRecordingId = null; setExecuting(false)
    }
    fun recordingStartRejected(id: String) {
        requireMain()
        privateRecordingBinding?.takeIf { it.descriptor.sessionId == id }?.let {
            stopPrivateRecording(id, it, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_FOREGROUND_START_REJECTED); return
        }
        if (segmentRecording?.owns(id) == true) { segmentRecording?.deny(id); return }
        if (pendingRecording?.sessionId != id && currentRecordingId != id) return
        pendingRecording = null; ProjectionGrantHandoff.discard(id)
        recorder.denied(id, RecordingError.FGS_START_REJECTED)
        currentRecordingId = null; setExecuting(false)
        notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_FOREGROUND_START_REJECTED)
    }
    fun onRecordingServiceReady(service: RecordingForegroundService, id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) {
            recordingService = service
            if (segmentRecording?.serviceReady(service, id) != true) { segmentRecording?.deny(id); service.finishSession(id) }
            return
        }
        val descriptor = pendingRecording?.takeIf { it.sessionId == id }
        if (descriptor == null || !hasPendingRecording(id)) { service.finishSession(id); recordingStartRejected(id); return }
        val execution = exclusiveExecution ?: run { service.finishSession(id); return }
        recordingService = service
        privateRecordingBinding?.let { binding ->
            if (binding.descriptor != descriptor || binding.isStopRequested) {
                stopPrivateRecording(id, binding, PrivateCaptureDisposition.KEEP_VALID_OUTPUT); return
            }
            // The service is promoted, but no Save task/native owner is submitted until the exact
            // Activity handback has fresh confirmation and an armed isolated-surface permit.
            val notify = privateRecordingReady; privateRecordingReady = null
            try { notify?.invoke(binding, service) } catch (_: Exception) {
                stopPrivateRecording(id, binding, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            }
            return
        }
        acceptContinuousSubmission(descriptor)
        submitRecordingOwner(service, id, descriptor, execution, null)
    }
    internal fun startPrivateRecording(id: String, binding: PrivateRecordingNativeBinding, exactService: Any): Boolean {
        requireMain()
        val descriptor = pendingRecording ?: return false
        val service = recordingService ?: return false
        val execution = exclusiveExecution ?: return false
        if (privateRecordingBinding !== binding || recordingService !== exactService || privateRecordingSubmitted || descriptor != binding.descriptor ||
            descriptor.sessionId != id || !foreground || !hasPendingRecording(id) || !binding.isArmed) return false
        privateRecordingSubmitted = true
        privateRecordingReady = null
        acceptContinuousSubmission(descriptor)
        submitRecordingOwner(service, id, descriptor, execution, binding)
        return true
    }
    /** Main-thread, capture-only obligations. Unrelated network/output work grants no evidence here. */
    internal fun recordingSourceObligationsDrained(): Boolean {
        requireMain()
        if (segmentRecording?.let { it.busy || it.recoveryActive || it.nativeOccupied || it.previewActive || it.requiresRecovery } == true)
            return false
        if (::recorder.isInitialized && (recorder.nativeOccupied || recorder.isDraining || recorder.requiresProcessRecovery()))
            return false
        val binding = privateRecordingBinding
        if (binding != null && currentRecordingId == binding.descriptor.sessionId)
            return binding.isCaptureDrained // Positive owner release, including a proved never-queued owner.
        return pendingRecording == null && currentRecordingId == null && recordingService == null
    }
    /** Exact transient UI identity; loading without an actual reserved preparation cannot authorize a dialog. */
    internal val currentSelectionPresentationIdentity: Any? get() = hlsPreparation ?:
        (if (preparingStream) preparationExecution else null) ?: (if (!preparingStream) selection else null)

    internal fun ownsPrivateRecording(binding: PrivateRecordingNativeBinding): Boolean =
        privateRecordingBinding === binding && currentRecordingId == binding.descriptor.sessionId

    /** Accepted handoff readback, independent of whether an Activity has processed the return. */
    internal fun isPrivateRecordingSubmitted(binding: PrivateRecordingNativeBinding): Boolean =
        ownsPrivateRecording(binding) && privateRecordingSubmitted &&
            continuousRecordingOwner?.let { it.descriptor == binding.descriptor && it.submitted } == true
    internal fun canAbandonPrivateRecordingSetup(binding: PrivateRecordingNativeBinding): Boolean =
        ownsPrivateRecording(binding) && !privateRecordingSubmitted &&
            continuousRecordingOwner?.let { it.descriptor == binding.descriptor && !it.submitted } == true

    internal fun stopPrivateRecording(id: String, binding: PrivateRecordingNativeBinding, disposition: PrivateCaptureDisposition) {
        requireMain()
        if (privateRecordingBinding !== binding || currentRecordingId != id || binding.descriptor.sessionId != id) return
        continuousRecordingOwner?.takeIf { it.descriptor.sessionId == id }?.requestStop(disposition)
        binding.requestStop(disposition)
        privateRecordingReady = null
        publishContinuousRecording()
        val sourceReleasedWithoutQueue = binding.closeNeverQueued()
        if (sourceReleasedWithoutQueue && !privateRecordingSubmitted) {
            pendingRecording = null; ProjectionGrantHandoff.discard(id)
            recorder.denied(id)
            setExecuting(false)
            recordingService?.finishSession(id)
            if (recordingService == null) currentRecordingId = null
        } else if (disposition == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT) cancel(id)
        else if (!binding.isCaptureDrained) recorder.stopAndSave(id)
        // Once submitted, the original worker owns claim/output retirement even if capture has
        // already drained. KEEP never turns a valid finalized output into denied/cancelled work.
    }
    private fun submitRecordingOwner(service: RecordingForegroundService, id: String, descriptor: CaptureDescriptor,
        execution: SaveExecutionAdmission.Claim, privateBinding: PrivateRecordingNativeBinding?) {
        command(mediaWork = true, onRejected = {
            // RejectedExecutionException is positive evidence that this exact command never entered.
            // Only this path may undo submitted; accepted commands retain original-worker retirement.
            if (privateBinding != null && privateRecordingBinding === privateBinding &&
                currentRecordingId == id && exclusiveExecution === execution && recordingService === service) {
                privateRecordingSubmitted = false
                stopPrivateRecording(id, privateBinding, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            } else if (privateBinding == null) recordingStartRejected(id)
            continuousRecordingOwner?.takeIf { it.descriptor == descriptor }?.let {
                it.terminal = if (it.discardRequested) ContinuousRecordingPhase.CANCELLED else ContinuousRecordingPhase.FAILED
                it.nativeDrained = !recorder.nativeOccupied
            }
            publishContinuousRecording()
            service.finishSession(id)
        }) {
            var launched = false
            try {
                val continuous = continuousRecordingOwner?.takeIf { it.descriptor == descriptor }
                    ?: throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
                if (continuous.discardRequested) throw SaveFailure(SaveError.CANCELLED)
                if (continuous.stopRequested) throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
                if (privateBinding != null && (privateBinding.isStopRequested || !privateBinding.isArmed))
                    throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
                if (!recorder.admitForeground(id)) throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
                val context = operation(2L * 60 * 60 * 1000 + limits.verificationTimeoutMillis + 30_000).copy(retryBudget = 0)
                val result = coordinator.submit(SaveRequest(id, id, SaveInput.Capture(descriptor), TaskMethod.RECORD), context, execution)
                if (result !is SaveSubmission.Accepted) throw SaveFailure((result as SaveSubmission.Rejected).error)
                if (result.existing) return@command
                // A Discard arriving before submit registered its runtime must survive that gap.
                if (continuous.discardRequested) {
                    coordinator.signalUserCancel(id)
                    coordinator.cancel(id)
                    throw SaveFailure(SaveError.CANCELLED)
                }
                val accepted = SaveWorkerHandoff().launch({ worker.execute(it) }) {
                    try { coordinator.execute(id, execution) }
                    catch (_: Exception) { main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_EXECUTION_FAILED) } }
                    finally {
                        releaseExecution(execution)
                        command { publishRecords() }
                        main.post {
                            if (pendingRecording?.sessionId == id) pendingRecording = null
                            publishContinuousRecording()
                            if (currentRecordingId != id) return@post
                            if (recorder.nativeOccupied) {
                                notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_NATIVE_RELEASE_UNCONFIRMED)
                                service.showStatus(id, RecordingNotificationStatus.RECOVERY_REQUIRED)
                            } else finishRecordingOwnerIfReleased(id)
                        }
                    }
                }
                launched = accepted
                if (!accepted) throw SaveFailure(SaveError.BUSY)
                main.post { if (pendingRecording?.sessionId == id) pendingRecording = null }
                publishRecords()
            } catch (failure: Exception) {
                if (!launched) {
                    recorder.denied(id, RecordingError.CAPTURE_UNAVAILABLE)
                    try { coordinator.cancel(id) } catch (_: Exception) { /* Preserve journal for recovery. */ }
                }
                main.post { notice = saveRuntimeFailureNotice(SaveErrors.classify(failure)) }
            } finally {
                if (!launched && privateBinding != null) {
                    privateBinding.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
                    privateBinding.closeNeverQueued()
                }
                if (!launched) main.post {
                    if (pendingRecording?.sessionId == id) pendingRecording = null
                    ProjectionGrantHandoff.discard(id)
                    if (currentRecordingId == id) releaseExecution(execution)
                    continuousRecordingOwner?.takeIf { it.descriptor == descriptor }?.let {
                        if (coordinator.snapshot(id) == null)
                            it.terminal = if (it.discardRequested) ContinuousRecordingPhase.CANCELLED else ContinuousRecordingPhase.FAILED
                        it.nativeDrained = !recorder.nativeOccupied
                    }
                    publishContinuousRecording()
                    service.finishSession(id)
                }
            }
        }
    }
    private fun finishRecordingOwnerIfReleased(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) return
        if (currentRecordingId != id || executionBusy() || recorder.nativeOccupied) return
        val service = recordingService
        if (service == null) currentRecordingId = null
        else service.finishSession(id) // onDestroy is the final old-service admission boundary.
    }
    fun onRecordingServiceDestroyed(service: RecordingForegroundService, id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) {
            segmentRecording?.serviceDestroyed(service, id)
            if (recordingService === service) recordingService = null
            return
        }
        if (recordingService === service) recordingService = null
        if (currentRecordingId == id && !executionBusy() && !recorder.nativeOccupied) currentRecordingId = null
    }
    fun canPauseRecording(): Boolean = gate.evaluate(FeatureRequest(Feature.SEGMENT_RECORDING), entitlements.snapshot(),
        PlatformCapabilities(setOf(Feature.RECORD_SAVE, Feature.SEGMENT_RECORDING)), BuildFeaturePolicy(), clock.nowMillis()).allowed
    fun pauseRecording(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.pause(id); return }
        if (!canPauseRecording()) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED); return }
        if (currentRecordingId == id) recorder.pause(id)
    }
    fun resumeRecording(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.resume(id); return }
        if (!canPauseRecording()) { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.RECORDING_PAUSE_RESUME_ENTITLEMENT_REQUIRED); return }
        if (currentRecordingId == id) recorder.resume(id)
    }
    fun stopRecording(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.stop(id); return }
        if (currentRecordingId == id) {
            continuousRecordingOwner?.takeIf { it.descriptor.sessionId == id }
                ?.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            privateRecordingBinding?.takeIf { it.descriptor.sessionId == id }?.let {
                stopPrivateRecording(id, it, PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            } ?: recorder.stopAndSave(id)
            publishContinuousRecording()
        }
    }
    /** A broadcast is not a MediaProjection revocation. BASIC follows the actual onStop callback. */
    internal fun onRecordingScreenOff(id: String) {
        requireMain()
        if (continuousRecordingOwner?.ignoresScreenOff(id) == true) return
        safetyStopRecording(id, RecordingStopReason.SYSTEM_STOPPED)
    }
    fun safetyStopRecording(id: String, reason: RecordingStopReason) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.stop(id, reason); return }
        if (currentRecordingId == id) {
            continuousRecordingOwner?.takeIf { it.descriptor.sessionId == id }
                ?.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            recorder.stop(id, reason); publishContinuousRecording()
        }
    }
    fun onRecordingServiceLost(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.serviceLost(id); recordingService = null; return }
        if (currentRecordingId == id) {
            continuousRecordingOwner?.takeIf { it.descriptor.sessionId == id }
                ?.requestStop(PrivateCaptureDisposition.KEEP_VALID_OUTPUT)
            recorder.foregroundLost(id); recordingService = null; publishContinuousRecording()
        }
    }
    /** The library shares the existing storage owner and its atomic publication transaction. */
    val libraryRepository: LibraryRepository? get() = if (ready && ::storage.isInitialized) storage.library else null

    fun openLibraryMedia(reference: MediaReadRef): FileInputStream {
        if (!ready || executionBusy() || pendingRecording != null || previewUnconfirmed || (::recorder.isInitialized && recorder.isDraining))
            throw SaveFailure(SaveError.BUSY)
        return storage.openPublishedMedia(reference)
    }

    /** Additional read ownership survives a native decoder's duplicated descriptor. */
    fun retainLibraryNativeReader(reference: MediaReadRef): AutoCloseable {
        if (!ready || executionBusy() || pendingRecording != null || previewUnconfirmed || (::recorder.isInitialized && recorder.isDraining))
            throw SaveFailure(SaveError.BUSY)
        return storage.retainNativeMediaReader(reference)
    }

    fun openPublishedMedia(receipt: MediaCommitReceipt): FileInputStream {
        if (executionBusy() || pendingRecording != null || previewUnconfirmed || (::recorder.isInitialized && recorder.isDraining))
            throw SaveFailure(SaveError.BUSY)
        return storage.openPublishedMedia(receipt)
    }
    fun previewReleaseUnconfirmed() { requireMain(); previewUnconfirmed = true; draining = true }
    fun refreshLibraryDisposition() { requireMain(); if (ready) command { publishRecords() } }

    private fun readTaskSnapshot(): Pair<List<SaveRecord>, Int> {
        val recent = storage.listRecent(100)
        val unfinished = storage.listUnfinished(limits.maxJournalEntries)
        val byId = (recent + unfinished).associateBy { it.task.taskId }
        val ids = SaveTaskCapacity.visibleTaskIds(unfinished.map { it.task.taskId }, recent.map { it.task.taskId })
        return ids.mapNotNull(byId::get) to SaveTaskCapacity.activeCount(unfinished.map { it.task.state })
    }

    private fun readMediaDispositions(items: List<SaveRecord>): Map<String, MediaLifecycle> =
        readSaveMediaDispositions(items.mapNotNull { it.receipt?.mediaId }, storage.library::item)

    private fun canResumeFinalized(record: SaveRecord): Boolean =
        (record.request.method == TaskMethod.RECORD && record.transfer?.recordingExpectation != null) || canSave(record.request.method)
    private fun allowed() = canSave(TaskMethod.DIRECT)
    private fun refresh() {
        publishContinuousRecording()
        retireCompletedExecutions()
        if (::stream.isInitialized) stream.expireIdle()
        if (streamSelectionContext?.isCurrent() == false || hlsPreparation?.let { clock.nowMillis() >= it.expiresAtMillis } == true) dismissSelection()
        draining = resourcesDraining()
        networkDraining = AndroidLocalMp4Verifier.requiresRestart || Media3LocalHlsEngine.requiresRestart || (::transport.isInitialized && transport.requiresProcessRecovery()) || (::stream.isInitialized && stream.isDraining)
        if (!executionBusy() && pendingCommands.isEmpty() && clock.nowMillis() - lastExpiryCheckMillis < 30_000) return
        if (!ready || !refreshing.compareAndSet(false, true)) return
        command(onRejected = { refreshing.set(false) }) {
            try { publishRecords() } finally { refreshing.set(false) }
        }
    }
    private fun publishRecords() = publishRecords(scheduleExpiry = true)
    private fun publishRecords(scheduleExpiry: Boolean) {
        if (!::storage.isInitialized) return
        if (scheduleExpiry) destinations?.refresh()
        val now = clock.nowMillis()
        if (scheduleExpiry && ::coordinator.isInitialized && now - lastExpiryCheckMillis >= 30_000) {
            // Empty periodic reads must not suspend/restart discovery or renew its demand round.
            val unfinished = storage.listUnfinished(limits.maxRecoveryEntries)
            // Preserve bounded in-memory source expiry even when no media cleanup is due.
            unfinished.forEach { coordinator.sourceForTask(it.task.taskId) }
            lastExpiryCheckMillis = now
            // Terminal-only residue has no WAITING_USER expiry event; scan it independently.
            main.post { command(mediaWork = true) {
                coordinator.sweepTerminalCleanup(operation(2_000))
            } }
            expiryActivity.scheduleIfDue(unfinished.map { it.task }, now,
                dispatch = { cleanup, rejected ->
                    // Claim main-thread priority before a control worker starts real cleanup.
                    if (!main.post {
                        command(mediaWork = true, onRejected = rejected) { cleanup(); publishRecords() }
                    }) rejected()
                },
                cleanup = { coordinator.expireWaitingUsers(operation(2_000)) },
            )
        }
        val (next, count) = readTaskSnapshot()
        resumeHandoff.observeTerminal(next)
        privateSources.retireUnavailable { taskId, binding ->
            val snapshot = coordinator.snapshot(taskId)
            snapshot?.state?.isTerminal == true || snapshot?.state == TaskState.WAITING_USER &&
                coordinator.sourceForTask(taskId)?.privateSource !== binding
        }
        authenticationOrigins.retainNonterminal(next.filterNot { it.task.state.isTerminal }.map { it.task.taskId }.toSet())
        val dispositions = readMediaDispositions(next)
        main.post { records = next; recoveryCancelTaskIds = resumeHandoff.pendingCancellations(); activeTaskCount = count; mediaDispositions = dispositions; draining = resourcesDraining(); networkDraining = AndroidLocalMp4Verifier.requiresRestart || Media3LocalHlsEngine.requiresRestart || (::transport.isInitialized && transport.requiresProcessRecovery()) || (::stream.isInitialized && stream.isDraining) }
    }
    private fun resourcesDraining() = (::storage.isInitialized && storage.normalizationReleaseUnconfirmed()) || previewUnconfirmed || VerifiedMediaPreviewResources.requiresRestart || VerifiedMediaPreviewResources.isOccupied ||
        OrdinaryMediaNativeResources.requiresRestart || (OrdinaryMediaNativeResources.isOccupied && directBatchOwner == null) ||
        (::recorder.isInitialized && recorder.isDraining) || AndroidLocalMp4Verifier.isDraining || (::transport.isInitialized && transport.requiresProcessRecovery()) ||
        (::stream.isInitialized && !executionBusy() && stream.isDraining)
    /** Application-scoped observer. Registration replays the current state, including after recreation. */
    fun addMediaTaskActivityListener(listener: (MediaTaskActivitySnapshot) -> Unit): AutoCloseable {
        requireMain()
        publishMediaTaskActivity()
        return activityTracker.addListener(listener)
    }

    private fun publishMediaTaskActivity() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publishMediaTaskActivity() }
            return
        }
        val segments = segmentRecording
        val blockedResources = resourcesDraining() ||
            AndroidLocalMp4Verifier.requiresRestart || Media3LocalHlsEngine.requiresRestart ||
            segments?.requiresRecovery == true
        val next = MediaTaskActivitySignals(
            initializing = initializationActivity.active,
            executing = executionBusy(), preparingStream = preparingStream,
            selectedStream = hlsPreparation != null,
            pendingCommandCount = pendingMediaCommands.get() + pendingCommands.size + if (expiryActivity.active) 1 else 0,
            pendingRecording = pendingRecording != null, recordingOwner = currentRecordingId != null,
            recordingService = recordingService != null,
            segmentRecording = segments?.let { it.busy || it.recoveryActive || it.nativeOccupied || it.previewActive } == true,
            resourcesDraining = blockedResources,
        )
        mediaTaskActivity = next.snapshot()
        activityTracker.update(next)
    }

    private fun <T> activityStateOf(initial: T): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private var state by mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state = value
            publishMediaTaskActivity()
        }
    }

    private fun executionBusy(): Boolean = executionAdmission.snapshot().busy
    private fun reserveExecution(): Boolean {
        val claim = executionAdmission.tryAcquireExclusive() ?: return false
        exclusiveOwner.set(claim)
        publishMediaTaskActivity()
        return true
    }
    private fun releaseExecution(claim: SaveExecutionAdmission.Claim) {
        executionRetirement.request(claim)
        publishMediaTaskActivity()
    }
    /** Native timeout is not a permanent poison unless the original native gate says so. */
    private fun retireCompletedExecutions() { executionRetirement.retry() }
    /** Legacy exclusive callers never clear a Direct claim. New workers carry exact release capabilities. */
    private fun setExecuting(value: Boolean) {
        if (value) {
            if (exclusiveExecution == null) reserveExecution()
        } else exclusiveExecution?.let(::releaseExecution)
    }
    private fun executionReleaseUnconfirmed(): Boolean =
        (::storage.isInitialized && storage.normalizationReleaseUnconfirmed()) ||
        OrdinaryMediaNativeResources.requiresRestart || VerifiedMediaPreviewResources.requiresRestart ||
        AndroidLocalMp4Verifier.isDraining || Media3LocalHlsEngine.requiresRestart ||
        (::transport.isInitialized && transport.requiresProcessRecovery()) ||
        (::recorder.isInitialized && recorder.requiresProcessRecovery())
    private fun claimDirectBatch(): Boolean {
        if (!ready || !foreground || pendingRecording != null || currentRecordingId != null || recordingService != null ||
            preparingStream || hlsPreparation != null || VerifiedMediaPreviewResources.isOccupied ||
            OrdinaryMediaNativeResources.isOccupied || executionReleaseUnconfirmed() ||
            (::stream.isInitialized && stream.isDraining) ||
            segmentRecording?.let { it.busy || it.recoveryActive || it.nativeOccupied || it.previewActive || it.requiresRecovery } == true) return false
        val owner = Any()
        if (!OrdinaryMediaNativeResources.claim(owner)) return false
        directBatchOwner = owner
        return true
    }
    private fun directAdmissionAvailable(): Boolean = ready && foreground && allowed() &&
        !executionAdmission.snapshot().exclusive && !executionReleaseUnconfirmed() &&
        (directBatchOwner != null || !resourcesDraining()) &&
        !preparingStream && hlsPreparation == null && currentRecordingId == null && pendingRecording == null && recordingService == null

    private fun command(mediaWork: Boolean = false, onRejected: () -> Unit = {}, action: () -> Unit) {
        if (mediaWork) { pendingMediaCommands.incrementAndGet(); publishMediaTaskActivity() }
        fun released() {
            if (mediaWork) { pendingMediaCommands.decrementAndGet(); publishMediaTaskActivity() }
        }
        try {
            controls.execute {
                try { action() }
                catch (_: Exception) { main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.SAVE_STORAGE_UNAVAILABLE) } }
                finally { released() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { onRejected() } finally { released() }
            main.post { notice = SaveRuntimeNotice.Fixed(SaveRuntimeFixedNotice.CONTROL_QUEUE_FULL) }
        }
    }
    private fun operation(lifetime: Long = 2L * 60 * 60 * 1000) = OperationContext(UUID.randomUUID().toString(),
        clock.nowMillis() + lifetime, CancellationSource(), retryBudget = 2,
        resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object {
        @Volatile private var instance: AndroidSaveRuntime? = null
        fun get(context: Context): AndroidSaveRuntime = instance ?: synchronized(this) {
            instance ?: AndroidSaveRuntime(context).also { instance = it }
        }
        private fun executor(name: String, queue: Int) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queue), { job -> Thread(job, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        internal fun sameOrigin(first: String?, second: String): Boolean = if (first == null) false else try {
            val a = URI(first); val b = URI(second)
            a.scheme.equals("https", true) && b.scheme.equals("https", true) && a.host.equals(b.host, true) &&
                (if (a.port < 0) 443 else a.port) == (if (b.port < 0) 443 else b.port) && a.rawUserInfo == null && b.rawUserInfo == null
        } catch (_: Exception) { false }
    }
}
