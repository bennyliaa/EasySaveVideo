package com.easysavevideo.app.save

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.browser.SystemBrowserHost
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
    private val controls = executor("esv-save-control", 16)
    private val worker = executor("esv-save-transfer", 1)
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
    private var recordingGeneration = 0L
    private var recordingService: RecordingForegroundService? = null
        set(value) { field = value; publishMediaTaskActivity() }
    var recordingSnapshot by activityStateOf<RecordingSnapshot?>(null); private set
    var currentRecordingId by activityStateOf<String?>(null); private set
    private var previewUnconfirmed = false
    private lateinit var transport: SystemHttpsDirectTransfer
    private lateinit var stream: AndroidHlsStreamPort
    private var streamSelectionContext: PageSaveSelection? = null
    private var streamDiscoveryOrigin: DiscoverySaveOriginHandle? = null
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
    private var preparationGeneration = 0L
    var hlsPreparation by activityStateOf<HlsPreparation?>(null); private set
    var preparingStream by activityStateOf(false); private set
    private val activityTracker = MediaTaskActivityTracker()
    private val initializationActivity = MediaTaskActivityHandoff(::publishMediaTaskActivity)
    private val expiryActivity = MediaTaskActivityExpiry(::publishMediaTaskActivity)
    private val pendingMediaCommands = AtomicInteger()
    var mediaTaskActivity by mutableStateOf(MediaTaskActivitySnapshot()); private set
    private val executing = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    @Volatile private var lastExpiryCheckMillis = 0L
    @Volatile private var foreground = false
    var ready by mutableStateOf(false); private set
    var libraryExports by mutableStateOf<AndroidExportRuntime?>(null); private set
    var destinations by mutableStateOf<AndroidOrdinaryDestinationRuntime?>(null); private set
    var editor by mutableStateOf<AndroidEditRuntime?>(null); private set
    var editorInitializationStatus by mutableStateOf(EditorInitializationStatus.INITIALIZING); private set
    var segmentRecording by activityStateOf<AndroidSegmentRecordingRuntime?>(null); private set
    var scanning by mutableStateOf(false); private set
    var selection by mutableStateOf<PageSaveSelection?>(null); private set
    var records by mutableStateOf<List<SaveRecord>>(emptyList()); private set
    var activeTaskCount by mutableStateOf(0); private set
    var mediaDispositions by mutableStateOf<Map<String, MediaLifecycle>>(emptyMap()); private set
    var notice by mutableStateOf<String?>(null); private set
    var draining by activityStateOf(false); private set
    var networkDraining by activityStateOf(false); private set
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
                    override fun length(artifact: StagingArtifact) = storage.stagingFile(artifact).length()
                    override fun availableBytes(artifact: StagingArtifact) = storage.availableBytes(artifact)
                    override fun open(artifact: StagingArtifact, append: Boolean) = storage.openStaging(artifact, append)
                    override fun rewind(artifact: StagingArtifact, length: Long) = storage.truncateStaging(artifact, length)
                    override fun sync(artifact: StagingArtifact) = storage.syncStaging(artifact)
                }
                transport = SystemHttpsDirectTransfer(staging, leases)
                stream = AndroidHlsStreamPort(application, storage, leases, limits, clock)
                recorder = AndroidRecordSavePort(application, storage, { canSave(TaskMethod.RECORD) }, ::canPauseRecording) { next ->
                    main.post {
                        if (recordingSnapshot?.revision?.let { it > next.revision } != true) recordingSnapshot = next
                        val label = when (next.state) {
                            RecordingState.PAUSED -> "已暂停，通知可停止并保存"
                            RecordingState.RECORDING -> "录制中，系统选择的共享范围"
                            RecordingState.STOPPING, RecordingState.FINALIZING -> "正在结束录制"
                            RecordingState.CANCELLING -> "正在放弃录制"
                            RecordingState.RECOVERY_REQUIRED -> "系统资源仍未退出，请在系统中结束应用"
                            else -> "正在准备或验证录制"
                        }
                        next.request?.requestId?.let { id ->
                            recordingService?.showStatus(id, label)
                            if (!executing.get()) finishRecordingOwnerIfReleased(id)
                        }
                    }
                }
                coordinator = SaveCoordinator(storage, transport,
                    AndroidLocalMp4Verifier(storage::verificationFile, limits.verificationTimeoutMillis), storage, clock, limits, stream = stream, recording = recorder)
                coordinator.recover(operation(2_000))
                val (initial, initialActiveCount) = readTaskSnapshot()
                val dispositions = readMediaDispositions(initial)
                // Export initialization is independent of original-media publication. Unavailable export
                // support never turns an existing ordinary save into a false failure or removes it.
                val exports = try { AndroidExportRuntime(application, storage) } catch (_: Exception) { null }
                val destinationRuntime = AndroidOrdinaryDestinationRuntime(application, storage, exports)
                val segmentRuntime = try { AndroidSegmentRecordingRuntime(application, storage, ::recordingFeatureAllowed,
                    otherMediaIdle = { ready && foreground && !executing.get() && currentRecordingId == null && recordingService == null && !resourcesDraining() },
                    released = { id ->
                        if (currentRecordingId == id) { currentRecordingId = null; pendingRecording = null; setExecuting(false) }
                        command { publishRecords() }
                    }, published = { command { publishRecords() } }, activityChanged = ::publishMediaTaskActivity) } catch (_: Exception) { null }
                val editInitialization = initializeEditor(storageProbe = { storage.edits; Unit }) {
                    AndroidEditRuntime(application, storage, destinationRuntime) { ready && !executing.get() &&
                        pendingRecording == null && currentRecordingId == null && !resourcesDraining() }
                }
                initializationActivity.enqueue({ install -> main.post(install) }) {
                    editor = editInitialization.value; editorInitializationStatus = editInitialization.status; segmentRecording = segmentRuntime; segmentRuntime?.setForeground(foreground)
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
        requireMain(); foreground = value; segmentRecording?.setForeground(value); main.removeCallbacks(poll)
        if (value) { main.post(poll) }
        else {
            destinations?.cancelSelection()
            val ownedPreparation = hlsPreparation
            discoverySaveLinks.discard(streamDiscoveryOrigin); streamDiscoveryOrigin = null
            detector.cancel(); scanning = false; selection = null
            preparationGeneration++; preparationCancellation?.cancel()
            // Keep activity owned until idle prepared state has actually been discarded.
            if (::stream.isInitialized) ownedPreparation?.let(stream::discard)
            hlsPreparation = null; streamSelectionContext = null; preparingStream = false
            // Accepted tasks pause through the coordinator's token so backgrounding is WAITING_USER,
            // not a user cancellation through the original preparation token.
            if (::coordinator.isInitialized) coordinator.signalBackgroundPauseAll()
            command(mediaWork = true) {
                if (::coordinator.isInitialized) storage.listUnfinished(100).forEach { coordinator.pauseForBackground(it.task.taskId) }
                leases.clear(); publishRecords()
            }
        }
    }
    fun inspectFromUserClick(browser: SystemBrowserHost) {
        requireMain()
        if (!ready || !foreground || !allowed()) { notice = "保存暂时不可用，请稍后重试。"; return }
        if (scanning) return
        val tab = browser.state.selectedTab
        val view = browser.selectedView()
        if (tab == null || view == null || tab.loadState != BrowserLoadState.READY || tab.address?.value != view.url) {
            notice = "请先打开网页，等待页面加载完成后再保存。"; return
        }
        val revision = browser.saveInspectionRevision
        val pageUrl = view.url ?: return
        fun isCurrent(): Boolean = foreground && browser.state.selectedTab?.let {
            it.context == tab.context && it.epoch == tab.epoch && it.loadState == BrowserLoadState.READY &&
                it.address?.value == pageUrl && browser.selectedView() === view &&
                browser.saveInspectionRevision == revision && view.url == pageUrl
        } == true
        scanning = true; selection = null; notice = null
        detector.inspectFromUserClick(view, tab.context, tab.epoch, pageUrl, ::isCurrent) { result ->
            scanning = false
            when (result) {
                is PageSaveDetection.Found -> selection = result.selection
                is PageSaveDetection.Unavailable -> notice = when (result.reason) {
                    PageSaveDetection.Reason.STALE_PAGE -> "页面或标签已变化，请在当前页面重新点击保存。"
                    PageSaveDetection.Reason.JAVASCRIPT_DISABLED -> "当前关闭了 JavaScript。启用后可在页面点击保存进行一次检测。"
                    PageSaveDetection.Reason.TIMED_OUT -> "页面检测超时，未开始下载。"
                    PageSaveDetection.Reason.UNSUPPORTED -> "当前资源属于 blob 或其他未支持类型。仅支持 HTTPS MP4 与受限静态 HLS。"
                    PageSaveDetection.Reason.NO_MEDIA -> "当前主页面未找到可检测的视频。跨域播放器、blob 或嵌套页面可能无法检测。"
                    PageSaveDetection.Reason.INVALID_RESPONSE -> "页面返回的检测数据无法安全读取，未开始下载。"
                }
            }
        }
    }
    fun dismissSelection() {
        requireMain()
        val ownedPreparation = hlsPreparation
        val ownedCancellation = preparationCancellation
        discoverySaveLinks.discard(streamDiscoveryOrigin); streamDiscoveryOrigin = null
        detector.cancel(); scanning = false; selection = null
        preparationCancellation = null; preparationGeneration++
        ownedCancellation?.cancel()
        // Old dialog callbacks lose ownership at variant handoff. They cannot cancel an accepted task.
        if (::stream.isInitialized) ownedPreparation?.let(stream::discard)
        hlsPreparation = null; streamSelectionContext = null; preparingStream = false
    }
    fun dismissNotice() { notice = null }
    fun choose(candidate: PageSaveCandidate, browser: SystemBrowserHost) {
        requireMain()
        val captured = selection ?: return
        if (!captured.isCurrent() || captured.candidates.none { it === candidate }) { selection = null; notice = "候选已过期，请重新检测当前页面。"; return }
        if (candidate.protected || !candidate.supported) { notice = "该资源受保护或格式未支持，未开始保存。"; return }
        if (!canSave(candidate.method)) { notice = "②流媒体保存需要有效权益。本开发版本没有真实购买入口。"; return }
        if (candidate.method == TaskMethod.STREAM) { prepareStream(candidate, captured, browser); return }
        destinations?.select(isCurrent = { foreground && selection === captured && captured.isCurrent() },
            accepted = { admission -> chooseDirect(candidate, captured, browser, admission) })
    }
    private fun chooseDirect(candidate: PageSaveCandidate, captured: PageSaveSelection, browser: SystemBrowserHost,
        admission: OrdinaryDestinationAdmission) {
        requireMain()
        if (selection !== captured || !captured.isCurrent()) return
        if (!foreground || !allowed() || executing.get() || resourcesDraining()) { notice = "已有保存或系统媒体校验正在退出，请稍后再试。"; return }
        if (!SaveTaskCapacity.hasRoom(activeTaskCount, limits.maxJournalEntries)) { notice = "最多同时保留 ${limits.maxJournalEntries} 条未完成任务，当前已满。已有媒体未被删除。"; return }
        val lease = try { leaseFor(candidate.url, browser) } catch (_: Exception) { notice = "无法安全建立短期请求会话，未开始保存。"; return }
        val id = admission.creationId
        val request = SaveRequest(id, id, SelectedMediaResource(candidate.id, candidate.id, candidate.url,
            expectedAudio = candidate.expectedAudio, requestContextRef = lease))
        val saveOrigin = try { browser.captureSaveOrigin() } catch (_: Exception) { null }
        // Clear immediately, so repeated taps cannot create fresh idempotency keys for one selection.
        selection = null
        command(mediaWork = true, onRejected = { leases.revoke(lease); discoverySaveLinks.discard(saveOrigin) }) {
            var acceptedTaskId: String? = null
            var launched = false
            try {
                if (!foreground || !allowed()) { leases.revoke(lease); return@command }
                when (val result = coordinator.submit(request, operation())) {
                    is SaveSubmission.Accepted -> {
                        acceptedTaskId = result.record.task.taskId
                        discoverySaveLinks.accepted(result.record.task.taskId, saveOrigin, result.existing)
                        launched = launchExecution(result.record.task.taskId, lease)
                        main.post { notice = "①直接下载任务已接受，请在保存任务查看进度。" }
                    }
                    is SaveSubmission.Rejected -> { leases.revoke(lease); main.post { notice = saveErrorText(result.error) } }
                }
                publishRecords()
            } finally {
                if (!launched) {
                    acceptedTaskId?.let(discoverySaveLinks::cancel)
                    discoverySaveLinks.discard(saveOrigin); leases.revoke(lease)
                }
            }
        }
    }
    private fun prepareStream(candidate: PageSaveCandidate, captured: PageSaveSelection, browser: SystemBrowserHost) {
        if (!foreground || !ready || !canSave(TaskMethod.STREAM) || resourcesDraining() || !reserveExecution()) {
            notice = "②流媒体保存暂不可用，请检查权益并等待现有任务退出。"; return
        }
        val lease = try { leaseFor(candidate.url, browser) } catch (_: Exception) {
            setExecuting(false); notice = "无法建立短期请求会话。"; return
        }
        val saveOrigin = try { browser.captureSaveOrigin() } catch (_: Exception) { null }
        discoverySaveLinks.discard(streamDiscoveryOrigin); streamDiscoveryOrigin = saveOrigin
        val cancellation = CancellationSource()
        preparationCancellation = cancellation
        val token = ++preparationGeneration
        val operation = operation().copy(cancelToken = cancellation)
        val source = SelectedMediaResource(candidate.id, candidate.id, candidate.url,
            expectedAudio = candidate.expectedAudio, requestContextRef = lease)
        selection = null; hlsPreparation = null; preparingStream = true; streamSelectionContext = captured
        try { worker.execute {
            try {
                if (!foreground || !canSave(TaskMethod.STREAM)) throw SaveFailure(SaveError.INVALID_REQUEST)
                val prepared = stream.prepare(source, operation)
                main.post {
                    if (token == preparationGeneration && foreground && captured.isCurrent() && canSave(TaskMethod.STREAM)) {
                        hlsPreparation = prepared
                    } else {
                        stream.discard(prepared); discoverySaveLinks.discard(saveOrigin)
                        if (streamDiscoveryOrigin === saveOrigin) streamDiscoveryOrigin = null
                    }
                }
            } catch (failure: Exception) {
                discoverySaveLinks.discard(saveOrigin)
                main.post {
                    if (streamDiscoveryOrigin === saveOrigin) streamDiscoveryOrigin = null
                    if (token == preparationGeneration) notice = saveErrorText(SaveErrors.classify(failure))
                }
                leases.revoke(lease)
            } finally {
                // Deliver/discard the prepared resource on main before releasing this owner.
                // A dialog cancellation must not expose an idle gap before a late result is retired.
                main.post {
                    setExecuting(false)
                    if (token == preparationGeneration) preparingStream = false
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            cancellation.cancel(); leases.revoke(lease)
            if (token == preparationGeneration) dismissSelection()
            setExecuting(false)
            notice = "流媒体检查队列暂不可用，未开始保存。"
        }
    }
    fun chooseHlsVariant(identity: String?) {
        requireMain()
        val prepared = hlsPreparation ?: return
        val captured = streamSelectionContext ?: return
        if (!foreground || !captured.isCurrent() || !canSave(TaskMethod.STREAM) || executing.get() || resourcesDraining()) {
            dismissSelection(); notice = "页面、来源或权益已变化，请重新检查。"; return
        }
        if (!SaveTaskCapacity.hasRoom(activeTaskCount, limits.maxJournalEntries)) { notice = "保存任务容量已满，已有媒体未被删除。"; return }
        destinations?.select(isCurrent = { foreground && hlsPreparation === prepared && streamSelectionContext === captured && captured.isCurrent() },
            accepted = { admission -> chooseHlsVariantSelected(identity, prepared, captured, admission) })
    }
    private fun chooseHlsVariantSelected(identity: String?, prepared: HlsPreparation, captured: PageSaveSelection,
        admission: OrdinaryDestinationAdmission) {
        requireMain()
        if (!foreground || hlsPreparation !== prepared || streamSelectionContext !== captured || !captured.isCurrent() ||
            !canSave(TaskMethod.STREAM) || resourcesDraining()) { dismissSelection(); return }
        if (!reserveExecution()) { notice = "已有保存操作处理中。"; return }
        val resource = try { stream.select(prepared, identity) } catch (failure: Exception) {
            setExecuting(false); dismissSelection(); notice = saveErrorText(SaveErrors.classify(failure)); return
        }
        val operation = stream.operation(prepared)
        val id = admission.creationId
        val request = SaveRequest(id, id, resource, TaskMethod.STREAM)
        val saveOrigin = streamDiscoveryOrigin
        streamDiscoveryOrigin = null
        // Fixed variant is now an independent task; later tab changes do not change that selection.
        hlsPreparation = null; streamSelectionContext = null; preparationCancellation = null
        preparingStream = false; preparationGeneration++
        command(mediaWork = true, onRejected = {
            discoverySaveLinks.discard(saveOrigin)
            setExecuting(false); stream.discard(prepared); resource.requestContextRef?.let(leases::revoke)
        }) {
            var launched = false
            var acceptedTaskId: String? = null
            try {
                if (!foreground || !canSave(TaskMethod.STREAM)) {
                    stream.discard(prepared); resource.requestContextRef?.let(leases::revoke); return@command
                }
                when (val result = coordinator.submit(request, operation, prepared.bytesTransferred)) {
                    is SaveSubmission.Accepted -> {
                        acceptedTaskId = result.record.task.taskId
                        discoverySaveLinks.accepted(result.record.task.taskId, saveOrigin, result.existing)
                        launched = launchExecution(result.record.task.taskId, resource.requestContextRef, reserved = true)
                        main.post { notice = "②流媒体任务已接受，固定所选清晰度；仅在独立 MP4 通过验证后完成。" }
                    }
                    is SaveSubmission.Rejected -> {
                        stream.discard(prepared); resource.requestContextRef?.let(leases::revoke)
                        main.post { notice = saveErrorText(result.error) }
                    }
                }
                publishRecords()
            } finally {
                if (!launched) {
                    acceptedTaskId?.let(discoverySaveLinks::cancel)
                    discoverySaveLinks.discard(saveOrigin)
                    // Discard while the execution reservation still owns task priority.
                    try { stream.discard(prepared); resource.requestContextRef?.let(leases::revoke) }
                    finally { setExecuting(false) }
                }
            }
        }
    }

    fun cancel(taskId: String) {
        requireMain()
        discoverySaveLinks.cancel(taskId)
        if (segmentRecording?.owns(taskId) == true) { segmentRecording?.discard(taskId); return }
        if (pendingRecording?.sessionId == taskId && recordingService == null) { recorder.cancel(taskId); abandonRecordingRequest(taskId); return }
        if (::coordinator.isInitialized && coordinator.snapshot(taskId)?.state?.isTerminal == true) {
            command { publishRecords() }; return
        }
        if (currentRecordingId == taskId) recorder.cancel(taskId)
        pendingCommands = pendingCommands + taskId
        coordinator.signalUserCancel(taskId)
        command(mediaWork = true, onRejected = { pendingCommands = pendingCommands - taskId }) {
            try { coordinator.cancel(taskId); publishRecords() }
            finally { main.post { pendingCommands = pendingCommands - taskId } }
        }
    }
    fun resume(taskId: String, browser: SystemBrowserHost) {
        requireMain()
        val record = records.firstOrNull { it.task.taskId == taskId } ?: return
        if (!foreground || !ready || !canResumeFinalized(record) || executing.get() || resourcesDraining()) {
            notice = "已有保存或系统媒体校验正在退出，请稍后再试。"; return
        }
        val source = coordinator.sourceForTask(taskId)
        if (record.request.method == TaskMethod.RECORD && record.transfer == null) {
            notice = "录制会话已中断，不能复用旧授权。请明确开始新录制；旧任务可放弃。"; return
        }
        if (record.request.method == TaskMethod.STREAM && record.transfer == null) {
            notice = "流媒体片段未完成。请返回网页重新选择固定清晰度，新任务从头开始；旧任务可取消。"
            return
        }
        if (source == null && record.transfer == null) {
            notice = "重启后不会保留完整来源地址。请返回网页重新检测并选择，新任务会从头下载；旧任务可取消。"
            return
        }
        if (record.waitReason == SaveWaitReason.AUTHENTICATION && source != null &&
            !sameOrigin(browser.state.selectedTab?.address?.value, source.url.value)) {
            notice = "请先在浏览器打开并登录该资源的同源页面，再回来手动继续。"; return
        }
        val lease = try { source?.let { leaseFor(it.url, browser) } } catch (_: Exception) {
            notice = "无法安全建立短期请求会话，未继续保存。"; return
        }
        pendingCommands = pendingCommands + taskId
        command(mediaWork = true, onRejected = { pendingCommands = pendingCommands - taskId; lease?.let(leases::revoke) }) {
            try {
                if (foreground && canResumeFinalized(record)) {
                    val resumed = coordinator.resume(taskId, if (record.request.method == TaskMethod.RECORD) operation(limits.verificationTimeoutMillis + 30_000) else operation(), lease)
                    if (resumed?.task?.state == TaskState.RUNNING) launchExecution(taskId, lease) else lease?.let(leases::revoke)
                } else lease?.let(leases::revoke)
                publishRecords()
            } finally { main.post { pendingCommands = pendingCommands - taskId } }
        }
    }

    private fun launchExecution(taskId: String, lease: RequestContextRef?, reserved: Boolean = false): Boolean {
        if (!foreground) {
            if (reserved) setExecuting(false)
            discoverySaveLinks.cancel(taskId)
            lease?.let(leases::revoke); deferUnstartedExecution(taskId); return false
        }
        if (!reserved && !reserveExecution()) {
            discoverySaveLinks.cancel(taskId)
            lease?.let(leases::revoke); deferUnstartedExecution(taskId); return false
        }
        try {
            worker.execute {
                var executed = false
                try {
                    // Covers a submit racing the immediate background-memory fence.
                    if (!foreground) deferUnstartedExecution(taskId)
                    coordinator.execute(taskId)
                    executed = true
                } catch (_: Exception) { main.post { notice = "任务状态暂时无法读取，已保留私有文件供恢复。" } }
                finally {
                    // Optional discovery work has its own failure boundary. It cannot retry Save,
                    // alter a committed media result, or turn a history error into a Save error.
                    if (executed) discoverySaveLinks.afterExecution(taskId) { coordinator.record(taskId) }
                    else discoverySaveLinks.cancel(taskId)
                    try {
                        lease?.let(leases::revoke)
                        if (::stream.isInitialized && !stream.isDraining) stream.discardIdle()
                    } finally { setExecuting(false); command { publishRecords() } }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            discoverySaveLinks.cancel(taskId)
            setExecuting(false); lease?.let(leases::revoke); deferUnstartedExecution(taskId)
            main.post { notice = "保存执行队列暂不可用，任务已暂停。" }; return false
        }
        return true
    }
    /** Scheduling deferral is separate from pausing an active capture or cancelling output. */
    private fun deferUnstartedExecution(taskId: String) {
        val record = coordinator.record(taskId)
        if (record?.request?.method == TaskMethod.RECORD && record.transfer != null)
            coordinator.deferFinalizedRecording(taskId)
        else coordinator.pauseForBackground(taskId)
    }
    private fun leaseFor(url: SourceUrl, browser: SystemBrowserHost): RequestContextRef {
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
    fun prepareRecording(choice: RecordingAudioChoice, width: Int, height: Int, density: Int, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        if (!ready || !foreground || !canSave(TaskMethod.RECORD) || resourcesDraining() ||
            VerifiedMediaPreviewResources.isOccupied || pendingRecording != null || currentRecordingId != null || recordingService != null || !reserveExecution()) {
            notice = "③暂不可开始。请检查录制权益，并等待现有保存或预览资源退出。"; return null
        }
        if (!SaveTaskCapacity.hasRoom(activeTaskCount, limits.maxJournalEntries) || width <= 0 || height <= 0 || density <= 0) {
            setExecuting(false); notice = "任务容量或显示尺寸不适合开始录制。"; return null
        }
        try {
            // A truthful initial canvas intent, then CodecProfileSelector checks real supported alignment.
            val scale = minOf(1.0, 1920.0 / maxOf(width, height))
            val w = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val h = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
            val id = admission.creationId
            val descriptor = CaptureDescriptor(id, ++recordingGeneration, choice, RecordingProfile(w, h))
            val audioAllowed = choice == RecordingAudioChoice.NONE ||
                application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!recorder.prepare(descriptor, audioAllowed)) {
                setExecuting(false); notice = "录制会话仍在退出，请稍后重新开始。"; return null
            }
            pendingRecording = descriptor; currentRecordingId = id; notice = null
            main.postDelayed({ if (pendingRecording?.sessionId == id && recordingService == null) abandonRecordingRequest(id) }, 120_000)
            return id
        } catch (_: RuntimeException) { setExecuting(false); notice = "无法建立有效的录制配置。"; return null }
    }
    fun isRecordingRequestAllowed(id: String): Boolean = if (segmentRecording?.owns(id) == true)
        segmentRecording?.allowsConsent(id) == true else pendingRecording?.sessionId == id && canSave(TaskMethod.RECORD) && !resourcesDraining()
    fun requiresDefaultDisplayRecording(id: String): Boolean = segmentRecording?.owns(id) == true
    private fun recordingFeatureAllowed(feature: Feature): Boolean = gate.evaluate(FeatureRequest(feature), entitlements.snapshot(),
        PlatformCapabilities(setOf(Feature.RECORD_SAVE, Feature.SEGMENT_RECORDING, Feature.REGION_RECORDING)),
        BuildFeaturePolicy(), clock.nowMillis()).allowed
    fun canRegionRecording(): Boolean = recordingFeatureAllowed(Feature.REGION_RECORDING)
    fun prepareRegionRecording(choice: RecordingAudioChoice, recipe: RegionCaptureRecipe, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        val id = segmentRecording?.prepare(choice, recipe, admission)
        if (id == null) { notice = "选区、权益或媒体资源尚未就绪。请重新核对选区并等待现有任务退出。"; return null }
        setExecuting(true); currentRecordingId = id; recordingSnapshot = null; notice = null
        return id
    }
    fun preparePreviewRecording(choice: RecordingAudioChoice, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        val id = segmentRecording?.preparePreview(choice, admission)
        if (id == null) { notice = "当前显示几何、权益或媒体资源尚未就绪，未取得预览或开始录制。"; return null }
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
        if (segmentRecording?.owns(id) == true) { segmentRecording?.deny(id); return }
        if (pendingRecording?.sessionId != id || recordingService != null) return
        pendingRecording = null; ProjectionGrantHandoff.discard(id); recorder.denied(id)
        currentRecordingId = null; setExecuting(false)
    }
    fun recordingStartRejected(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.deny(id); return }
        if (pendingRecording?.sessionId != id && currentRecordingId != id) return
        pendingRecording = null; ProjectionGrantHandoff.discard(id)
        recorder.denied(id, RecordingError.FGS_START_REJECTED)
        currentRecordingId = null; setExecuting(false)
        notice = "系统未能启动本次前台录制服务。旧授权已丢弃，请手动重新开始。"
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
        recordingService = service
        command(mediaWork = true, onRejected = { recordingStartRejected(id); service.finishSession(id) }) {
            var launched = false
            try {
                if (!recorder.admitForeground(id)) throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
                val context = operation(2L * 60 * 60 * 1000 + limits.verificationTimeoutMillis + 30_000).copy(retryBudget = 0)
                val result = coordinator.submit(SaveRequest(id, id, SaveInput.Capture(descriptor), TaskMethod.RECORD), context)
                if (result !is SaveSubmission.Accepted) throw SaveFailure((result as SaveSubmission.Rejected).error)
                worker.execute {
                    try { coordinator.execute(id) }
                    catch (_: Exception) { main.post { notice = "录制任务未能结束，私有文件已保留供本地恢复。" } }
                    finally {
                        setExecuting(false)
                        command { publishRecords() }
                        main.post {
                            if (pendingRecording?.sessionId == id) pendingRecording = null
                            if (currentRecordingId != id) return@post
                            if (recorder.nativeOccupied) {
                                notice = "系统录制资源仍未确认退出。已保留前台状态与文件；不能开始另一项录制。必要时请在系统中完全结束应用。"
                                service.showStatus(id, "系统资源仍未退出，请在系统中结束应用")
                            } else finishRecordingOwnerIfReleased(id)
                        }
                    }
                }
                launched = true
                main.post { if (pendingRecording?.sessionId == id) pendingRecording = null }
                publishRecords()
            } catch (failure: Exception) {
                if (!launched) {
                    recorder.denied(id, RecordingError.CAPTURE_UNAVAILABLE)
                    try { coordinator.cancel(id) } catch (_: Exception) { /* Preserve journal for recovery. */ }
                }
                main.post { notice = saveErrorText(SaveErrors.classify(failure)) }
            } finally {
                if (!launched) main.post {
                    if (pendingRecording?.sessionId == id) pendingRecording = null
                    ProjectionGrantHandoff.discard(id)
                    if (currentRecordingId == id) setExecuting(false)
                    service.finishSession(id)
                }
            }
        }
    }
    private fun finishRecordingOwnerIfReleased(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) return
        if (currentRecordingId != id || executing.get() || recorder.nativeOccupied) return
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
        if (currentRecordingId == id && !executing.get() && !recorder.nativeOccupied) currentRecordingId = null
    }
    fun canPauseRecording(): Boolean = gate.evaluate(FeatureRequest(Feature.SEGMENT_RECORDING), entitlements.snapshot(),
        PlatformCapabilities(setOf(Feature.RECORD_SAVE, Feature.SEGMENT_RECORDING)), BuildFeaturePolicy(), clock.nowMillis()).allowed
    fun pauseRecording(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.pause(id); return }
        if (!canPauseRecording()) { notice = "暂停/继续按现有契约需要录制与片段录制权益。"; return }
        if (currentRecordingId == id) recorder.pause(id)
    }
    fun resumeRecording(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.resume(id); return }
        if (!canPauseRecording()) { notice = "暂停/继续按现有契约需要录制与片段录制权益。"; return }
        if (currentRecordingId == id) recorder.resume(id)
    }
    fun stopRecording(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.stop(id); return }
        if (currentRecordingId == id) recorder.stopAndSave(id)
    }
    fun safetyStopRecording(id: String, reason: RecordingStopReason) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.stop(id, reason); return }
        if (currentRecordingId == id) recorder.stop(id, reason)
    }
    fun onRecordingServiceLost(id: String) {
        requireMain()
        if (segmentRecording?.owns(id) == true) { segmentRecording?.serviceLost(id); recordingService = null; return }
        if (currentRecordingId == id) { recorder.foregroundLost(id); recordingService = null }
    }
    /** The library shares the existing storage owner and its atomic publication transaction. */
    val libraryRepository: LibraryRepository? get() = if (ready && ::storage.isInitialized) storage.library else null

    fun openLibraryMedia(reference: MediaReadRef): FileInputStream {
        if (!ready || executing.get() || pendingRecording != null || previewUnconfirmed || (::recorder.isInitialized && recorder.isDraining))
            throw SaveFailure(SaveError.BUSY)
        return storage.openPublishedMedia(reference)
    }

    /** Additional read ownership survives a native decoder's duplicated descriptor. */
    fun retainLibraryNativeReader(reference: MediaReadRef): AutoCloseable {
        if (!ready || executing.get() || pendingRecording != null || previewUnconfirmed || (::recorder.isInitialized && recorder.isDraining))
            throw SaveFailure(SaveError.BUSY)
        return storage.retainNativeMediaReader(reference)
    }

    fun openPublishedMedia(receipt: MediaCommitReceipt): FileInputStream {
        if (executing.get() || pendingRecording != null || previewUnconfirmed || (::recorder.isInitialized && recorder.isDraining))
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

    private fun readMediaDispositions(items: List<SaveRecord>): Map<String, MediaLifecycle> = items.mapNotNull { record ->
        record.receipt?.let { receipt -> storage.library.item(receipt.mediaId)?.let { receipt.mediaId to it.lifecycle } }
    }.toMap()

    private fun canResumeFinalized(record: SaveRecord): Boolean =
        (record.request.method == TaskMethod.RECORD && record.transfer?.recordingExpectation != null) || canSave(record.request.method)
    private fun allowed() = canSave(TaskMethod.DIRECT)
    private fun refresh() {
        if (::stream.isInitialized) stream.expireIdle()
        if (streamSelectionContext?.isCurrent() == false || hlsPreparation?.let { clock.nowMillis() >= it.expiresAtMillis } == true) dismissSelection()
        draining = resourcesDraining()
        networkDraining = AndroidLocalMp4Verifier.requiresRestart || Media3LocalHlsEngine.requiresRestart || (::transport.isInitialized && transport.isDraining) || (::stream.isInitialized && stream.isDraining)
        if (!executing.get() && pendingCommands.isEmpty() && clock.nowMillis() - lastExpiryCheckMillis < 30_000) return
        if (!ready || !refreshing.compareAndSet(false, true)) return
        command(onRejected = { refreshing.set(false) }) {
            try { publishRecords() } finally { refreshing.set(false) }
        }
    }
    private fun publishRecords() {
        if (!::storage.isInitialized) return
        destinations?.refresh()
        val now = clock.nowMillis()
        if (::coordinator.isInitialized && now - lastExpiryCheckMillis >= 30_000) {
            // Empty periodic reads must not suspend/restart discovery or renew its demand round.
            val unfinished = storage.listUnfinished(limits.maxRecoveryEntries)
            // Preserve bounded in-memory source expiry even when no media cleanup is due.
            unfinished.forEach { coordinator.sourceForTask(it.task.taskId) }
            lastExpiryCheckMillis = now
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
        val dispositions = readMediaDispositions(next)
        main.post { records = next; activeTaskCount = count; mediaDispositions = dispositions; draining = resourcesDraining(); networkDraining = AndroidLocalMp4Verifier.requiresRestart || Media3LocalHlsEngine.requiresRestart || (::transport.isInitialized && transport.isDraining) || (::stream.isInitialized && stream.isDraining) }
    }
    private fun resourcesDraining() = previewUnconfirmed || VerifiedMediaPreviewResources.requiresRestart || VerifiedMediaPreviewResources.isOccupied ||
        OrdinaryMediaNativeResources.requiresRestart || OrdinaryMediaNativeResources.isOccupied ||
        (::recorder.isInitialized && recorder.isDraining) || AndroidLocalMp4Verifier.isDraining || (::transport.isInitialized && transport.isDraining) ||
        (::stream.isInitialized && !executing.get() && stream.isDraining)
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
            executing = executing.get(), preparingStream = preparingStream,
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

    private fun reserveExecution(): Boolean = executing.compareAndSet(false, true).also {
        if (it) publishMediaTaskActivity()
    }
    private fun setExecuting(value: Boolean) { executing.set(value); publishMediaTaskActivity() }

    private fun command(mediaWork: Boolean = false, onRejected: () -> Unit = {}, action: () -> Unit) {
        if (mediaWork) { pendingMediaCommands.incrementAndGet(); publishMediaTaskActivity() }
        fun released() {
            if (mediaWork) { pendingMediaCommands.decrementAndGet(); publishMediaTaskActivity() }
        }
        try {
            controls.execute {
                try { action() }
                catch (_: Exception) { main.post { notice = "保存存储暂时不可用，原有媒体未被清除。" } }
                finally { released() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { onRejected() } finally { released() }
            main.post { notice = "任务操作较多，请稍后再试。" }
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
