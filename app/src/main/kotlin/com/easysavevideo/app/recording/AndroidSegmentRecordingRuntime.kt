package com.easysavevideo.app.recording

import android.content.Context
import com.easysavevideo.app.ui.RuntimeMessageText
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.easysavevideo.app.editor.errorText
import com.easysavevideo.app.editor.EditPublicationCancellation
import com.easysavevideo.app.editor.EditCancelResult
import com.easysavevideo.app.editor.EditorTimerScheduler
import com.easysavevideo.app.editor.workflow.EditRunResult
import com.easysavevideo.app.editor.workflow.EditWorkStage
import com.easysavevideo.app.library.thumbnail.OrdinaryMediaNativeResources
import com.easysavevideo.app.recording.nativeregion.*
import com.easysavevideo.app.recording.segments.*
import com.easysavevideo.app.recording.ui.*
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.core.model.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.*
import com.easysavevideo.recording.*
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Advanced capture has its own private journal and never submits a Save request or raw receipt.
 * The same bounded owner spans capture, raw verification, edit and publication. UI never owns a
 * projection grant. Cancel/deadline fences output while the actual worker retains all resources.
 */
class AndroidSegmentRecordingRuntime internal constructor(
    context: Context,
    private val storage: AndroidSaveStorage,
    private val featureAllowed: (Feature) -> Boolean,
    private val otherMediaIdle: () -> Boolean,
    private val released: (String) -> Unit,
    private val published: () -> Unit,
    private val runtimeText: RuntimeMessageText,
    private val activityChanged: () -> Unit = {},
    private val newRecordingAllowed: () -> Boolean = { true },
) {
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "esv-record-segments").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val lease = RecordingNativeLease()
    private val bridge = RecordedSegmentEditorBridge(application, storage)
    private val segmentStorage get() = storage.recordedSegments
    private val limits = RecordingLimits(maxSessionDurationUs = RecordingSegmentLimits.MAX_DURATION_US,
        maxFileBytes = RecordingSegmentLimits.MAX_TOTAL_BYTES)
    @Volatile private var foreground = false
    @Volatile private var previewHostFocused = false
    @Volatile private var capture: AndroidRecordingSession? = null
    @Volatile private var active: Job? = null
        set(value) { field = value; activityChanged() }
    private var selected: AndroidRecordedSegmentStorage.Snapshot? = null
    private var presentationOwner: Job? = null
    private var generation = 0L
    private var consentTimeout: Runnable? = null
    private var nativeTimeout: Runnable? = null
    private var finalizationTimeout: Runnable? = null
    private var preparationTimeout: Runnable? = null
    private val processingWatchdog = RecordingProcessingWatchdog(EditorTimerScheduler { delay, callback ->
        val runnable = Runnable(callback); main.postDelayed(runnable, delay)
        AutoCloseable { main.removeCallbacks(runnable) }
    }, onDeadline = {
        active?.let { job ->
            if (job.cancellation.cancel() == EditCancelResult.TOO_LATE)
                setPhase(job, RecordingSegmentsPhase.PROCESSING, "最终入库提交已开始，不能再确认取消。正在核对提交结果，私有片段保留。")
            else {
                job.fence.revoke(job.descriptor.session)
                setPhase(job, RecordingSegmentsPhase.CANCELLING, "处理达到时间预算，正在等待后台资源实际退出。私有片段保留。")
            }
        }
    }, onWaiting = {
        active?.let { job -> setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED,
            "后台处理或清理尚未确认退出，私有片段状态保留，不能开始新任务。可继续等待或完全退出应用后恢复检查。") }
    })
    var ready by mutableStateOf(false); private set
    var retainedSessionCount by mutableStateOf(0); private set
    var presentation by mutableStateOf<RecordingSegmentsPresentation?>(null); private set
    var completedReference by mutableStateOf<MediaReadRef?>(null); private set
    var previewFrame by mutableStateOf<NativeRegionPreviewFrame?>(null); private set
    var previewHostRequest by mutableStateOf<RecordingPreviewHostRequest?>(null); private set
    var previewActive by mutableStateOf(false); private set
    var previewReturnToControls by mutableStateOf(false); private set
    fun acknowledgePreviewReturn() { requireMain(); previewReturnToControls = false }
    private var previewDeadline: Runnable? = null
    private class Job(@Volatile var descriptor: CaptureDescriptor, val owner: Any = Any(), val preview: Boolean = false) {
        @Volatile var previewGate: RegionPreviewGate? = null
        @Volatile var previewNativeConfirmed = false
        @Volatile var previewFrameOwner: NativeRegionPreviewFrame? = null
        var previewRequestGeneration = 0L
        var previewConsentReady = false
        val fence = SegmentRecordingFence(descriptor.session)
        val cancellation = EditPublicationCancellation()
        @Volatile var service: RecordingForegroundService? = null
        @Volatile var journalCreated = false
        @Volatile var submitted = false
        @Volatile var workerStarted = false
        @Volatile var encodedStarted = false
        @Volatile var captureNotice: String? = null
    }
    @Volatile var recoveryActive = true; private set
    val nativeOccupied get() = lease.occupied
    val busy get() = active != null
    val requiresRecovery get() = lease.releaseUnconfirmed || bridge.requiresProcessRecovery()
    val requestId: String? get() = active?.descriptor?.sessionId

    init {
        worker.execute {
            val outcome = runCatching { segmentStorage.recover(); segmentStorage.listRecoverableSessions() }
            main.post {
                ready = outcome.isSuccess
                val snapshots = outcome.getOrNull().orEmpty()
                retainedSessionCount = snapshots.size
                generation = snapshots.maxOfOrNull { it.descriptor.generation } ?: 0
                selected = snapshots.firstOrNull()
                selected?.let { showSnapshot(it, if (it.publishedReference != null) RecordingSegmentsPhase.SUCCEEDED
                    else RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    "上次私有录制片段仍保留。不会自动恢复录屏授权；可重新尝试处理，或明确放弃片段。") }
                if (outcome.isFailure) presentation = RecordingSegmentsPresentation(RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    notice = "录制片段恢复检查未完成，暂不能开始高级录制。未验证片段不会自动入库。")
                recoveryActive = false; activityChanged()
            }
        }
    }
    fun setForeground(value: Boolean) {
        requireMain()
        val leaving = foreground && !value
        foreground = value
        active?.takeIf { leaving && it.preview && it.submitted && !it.encodedStarted }?.let {
            invalidatePreview(it, RegionPreviewInvalidation.BACKGROUNDED)
        }
    }

    fun setPreviewHostFocused(value: Boolean) {
        requireMain()
        previewHostFocused = value
        active?.takeIf { it.preview && it.submitted && !it.encodedStarted && !value &&
            recordingPreviewRequiresHostFocus(it.previewGate?.phase) }?.let {
            invalidatePreview(it, RegionPreviewInvalidation.HOST_DISAPPEARED)
        }
    }

    /** Activity destruction cannot transfer a frozen image or a pending preview to a new host. */
    fun previewHostDestroyed() {
        requireMain()
        active?.takeIf { it.preview && !it.encodedStarted }?.let {
            invalidatePreview(it, RegionPreviewInvalidation.HOST_DISAPPEARED)
        }
    }

    fun preparePreview(choice: RecordingAudioChoice, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        val geometry = DefaultDisplayCaptureGeometry.read(application) ?: return null
        val scope = DefaultDisplayCaptureGeometry.scopeEvidence()
        val placeholder = try { RegionCaptureRecipe(RegionNormalizedRect.FULL, geometry, scope) }
            catch (_: IllegalArgumentException) { return null }
        return prepareInternal(choice, placeholder, true, admission)
    }

    /** A renderer submission is a readiness edge, not proof of selected application identity. */
    fun previewHostDrawn(request: RecordingPreviewHostRequest): RecordingAudioChoice? {
        requireMain()
        val job = active?.takeIf { it.preview && it.descriptor.sessionId == request.sessionId } ?: return null
        if (!recordingPreviewHostCallbackCurrent(previewHostRequest, request, job.descriptor.sessionId)) return null
        if (!foreground || !previewHostFocused || job.cancellation.isCancelled || job.fence.isRevoked ||
            !featureAllowed(Feature.REGION_RECORDING) ||
            DefaultDisplayCaptureGeometry.read(application) != job.descriptor.regionCaptureRecipe?.source) {
            invalidatePreview(job, RegionPreviewInvalidation.HOST_DISAPPEARED); return null
        }
        previewHostRequest = null
        return when (request.stage) {
            RecordingPreviewHostStage.BEFORE_CONSENT -> {
                if (job.submitted || job.previewConsentReady) null
                else { job.previewConsentReady = true; job.descriptor.audioChoice }
            }
            RecordingPreviewHostStage.BEFORE_SNAPSHOT -> {
                if (job.previewGate?.markHostReady(monotonicUs()) != true)
                    invalidatePreview(job, RegionPreviewInvalidation.HOST_DISAPPEARED)
                null
            }
            RecordingPreviewHostStage.BEFORE_RECORDING -> {
                val gate = job.previewGate
                val snapshot = gate?.snapshot
                if (snapshot == null || !gate.markCaptureReady(snapshot, monotonicUs()))
                    invalidatePreview(job, RegionPreviewInvalidation.HOST_DISAPPEARED)
                null
            }
        }
    }

    fun confirmPreview(snapshot: RegionPreviewSnapshot, bounds: RegionNormalizedRect) {
        requireMain()
        val job = active?.takeIf { it.preview && it.descriptor.session == snapshot.session } ?: return
        val gate = job.previewGate ?: return
        val setup = RecordingRegionSetup(DefaultDisplayCaptureGeometry.read(application), DefaultDisplayCaptureGeometry.scopeEvidence())
        if (!foreground || job.cancellation.isCancelled || job.fence.isRevoked || !featureAllowed(Feature.REGION_RECORDING) ||
            setup.geometry != snapshot.observation.geometry || setup.scopeEvidence != snapshot.observation.scopeEvidence) {
            invalidatePreview(job, RegionPreviewInvalidation.GEOMETRY_CHANGED); return
        }
        if (previewFrame?.snapshot != snapshot || recordingRegionError(bounds, setup) != null ||
            !gate.confirm(snapshot, bounds, monotonicUs())) return
        // Removing the state requests UI disposal. The disposal callback and a later submitted
        // browser draw remain mandatory; this click does not permit recording resources.
        previewFrame = null
        job.previewFrameOwner?.close(); job.previewFrameOwner = null
        setPhase(job, RecordingSegmentsPhase.PREPARING, "选区已确认，正在移除预览并等待当前浏览器画面。尚未开始录制或采集声音。")
    }

    fun previewUiRetired(snapshot: RegionPreviewSnapshot) {
        requireMain()
        val job = active?.takeIf { it.preview && it.descriptor.session == snapshot.session } ?: return
        if (!foreground || job.cancellation.isCancelled || job.fence.isRevoked) return
        if (job.previewGate?.retireUi(snapshot, monotonicUs()) == true)
            requestPreviewHost(job, RecordingPreviewHostStage.BEFORE_RECORDING)
    }

    fun cancelPreview() {
        requireMain()
        active?.takeIf { it.preview && !it.encodedStarted }?.let { invalidatePreview(it, RegionPreviewInvalidation.CANCELLED) }
    }

    private fun requestPreviewHost(job: Job, stage: RecordingPreviewHostStage) {
        requireMain()
        if (active !== job || job.cancellation.isCancelled || job.fence.isRevoked) return
        previewHostRequest = RecordingPreviewHostRequest(job.descriptor.sessionId, ++job.previewRequestGeneration, stage)
    }

    private fun invalidatePreview(job: Job, reason: RegionPreviewInvalidation) {
        requireMain()
        if (active !== job || !job.preview || job.encodedStarted) return
        job.previewGate?.invalidate(reason)
        previewActive = false; previewReturnToControls = true
        previewFrame = null; previewHostRequest = null
        job.previewFrameOwner?.close(); job.previewFrameOwner = null
        if (!job.submitted) deny(job.descriptor.sessionId) else discard(job.descriptor.sessionId)
    }

    private fun monotonicUs(): Long = System.nanoTime().coerceAtLeast(0) / 1_000
    fun owns(id: String): Boolean = active?.descriptor?.sessionId == id
    fun allowsConsent(id: String): Boolean = active?.let {
        it.descriptor.sessionId == id && !it.submitted && (!it.preview || it.previewConsentReady) && !it.fence.isRevoked && !it.cancellation.isCancelled &&
            featureAllowed(Feature.REGION_RECORDING) && newRecordingAllowed() && !lease.occupied
    } == true

    fun prepare(choice: RecordingAudioChoice, recipe: RegionCaptureRecipe, admission: OrdinaryDestinationAdmission): String? = prepareInternal(choice, recipe, false, admission)

    private fun prepareInternal(choice: RecordingAudioChoice, recipe: RegionCaptureRecipe, preview: Boolean, admission: OrdinaryDestinationAdmission): String? {
        requireMain()
        if (!ready || !foreground || active != null || !otherMediaIdle() || !newRecordingAllowed() || !featureAllowed(Feature.REGION_RECORDING)) return null
        if (recipe.source != DefaultDisplayCaptureGeometry.read(application) || recipe.scopeEvidence != DefaultDisplayCaptureGeometry.scopeEvidence() ||
            (!preview && (recipe.encodedWidth > EditLimits.MAX_DIMENSION || recipe.encodedHeight > EditLimits.MAX_DIMENSION ||
            recipe.encodedWidth.toLong() * recipe.encodedHeight > EditLimits.MAX_PIXELS))) return null
        val id = admission.creationId
        val descriptor = CaptureDescriptor(id, ++generation, choice,
            RecordingProfile(recipe.encodedWidth, recipe.encodedHeight), recipe)
        val job = Job(descriptor, preview = preview)
        if (!OrdinaryMediaNativeResources.claim(job.owner)) return null
        active = job; presentationOwner = job; completedReference = null; selected = null
        previewActive = preview; previewReturnToControls = false
        if (preview) requestPreviewHost(job, RecordingPreviewHostStage.BEFORE_CONSENT)
        presentation = RecordingSegmentsPresentation(RecordingSegmentsPhase.PREPARING, recipe = recipe,
            audioStatus = if (choice == RecordingAudioChoice.NONE) RecordingAudioStatus.NOT_REQUESTED else RecordingAudioStatus.AWAITING_PERMISSION,
            notice = if (preview) "等待浏览器画面和本次系统授权；之后只取得内存中的一张画面，明确确认选区前不会录制或采集声音。"
                else "等待本次系统授权；尚未录制。比例选区将应用于稍后系统授权的完整画面。")
        val timeout = Runnable { if (active === job && !job.submitted) deny(id) }
        consentTimeout = timeout; main.postDelayed(timeout, 120_000)
        return id
    }

    /** Called only after the mediaProjection foreground service was promoted. */
    fun serviceReady(service: RecordingForegroundService, id: String): Boolean {
        requireMain()
        val job = active?.takeIf { it.descriptor.sessionId == id } ?: return false
        if (!allowsConsent(id) || !ProjectionGrantHandoff.contains(id) || !lease.tryAcquire()) return false
        job.submitted = true; job.service = service
        if (job.preview) {
            job.previewGate = RegionPreviewGate(job.descriptor.session, job.descriptor.generation, monotonicUs())
            requestPreviewHost(job, RecordingPreviewHostStage.BEFORE_SNAPSHOT)
            armPreviewDeadline(job)
        }
        armPreparationDeadline(job, captureRequired = true)
        consentTimeout?.let(main::removeCallbacks); consentTimeout = null
        try { worker.execute { runOwnedJob(job) { captureAndProcess(job) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            lease.rejectQueued(); job.submitted = false; deny(id); return false
        }
        val timeout = Runnable { if (active === job && !job.fence.isCaptureClosed) stop(id, RecordingStopReason.SESSION_LIMIT) }
        nativeTimeout = timeout; main.postDelayed(timeout, 2L * 60 * 60 * 1000)
        return true
    }
    fun deny(id: String) {
        requireMain()
        val job = active?.takeIf { it.descriptor.sessionId == id && !it.submitted } ?: return
        job.fence.discard(job.descriptor.session); job.cancellation.cancel(); ProjectionGrantHandoff.discard(id)
        consentTimeout?.let(main::removeCallbacks); consentTimeout = null
        presentation = RecordingSegmentsPresentation(RecordingSegmentsPhase.CANCELLED, recipe = job.descriptor.regionCaptureRecipe,
            notice = "本次系统授权未完成，未开始录制。")
        finishOwner(job, false)
    }
    fun pause(id: String) {
        requireMain()
        val job = active?.takeIf { it.descriptor.sessionId == id } ?: return
        if (!featureAllowed(Feature.SEGMENT_RECORDING) || job.fence.isRevoked) return
        if (capture?.pause() == true) { setPhase(job, RecordingSegmentsPhase.PAUSING); armFinalizationDeadline(job) }
    }
    fun resume(id: String) {
        requireMain()
        val job = active?.takeIf { it.descriptor.sessionId == id } ?: return
        if (!featureAllowed(Feature.SEGMENT_RECORDING) || job.fence.isRevoked) return
        if (capture?.resume() == true) setPhase(job, RecordingSegmentsPhase.RESUMING)
    }
    fun stop(id: String, reason: RecordingStopReason = RecordingStopReason.USER_STOP) {
        requireMain()
        val job = active?.takeIf { it.descriptor.sessionId == id } ?: return
        if (!job.submitted) { deny(id); return }
        if (job.preview && !job.encodedStarted) { invalidatePreview(job, RegionPreviewInvalidation.CANCELLED); return }
        if (!job.fence.stopAndSave(job.descriptor.session, reason) || job.fence.isCaptureClosed) return
        capture?.stop(reason); setPhase(job, RecordingSegmentsPhase.STOPPING)
        armFinalizationDeadline(job)
    }
    fun discard(id: String) {
        requireMain()
        val job = active?.takeIf { it.descriptor.sessionId == id } ?: return
        if (!job.submitted) { deny(id); return }
        if (job.cancellation.cancel() == EditCancelResult.TOO_LATE) {
            setPhase(job, RecordingSegmentsPhase.PROCESSING, "最终入库提交已开始，不能再确认放弃。正在核对提交结果，私有片段保留。")
            processingWatchdog.stopping(job); return
        }
        job.previewGate?.invalidate(RegionPreviewInvalidation.CANCELLED)
        if (job.preview && !job.encodedStarted) { previewActive = false; previewReturnToControls = true }
        previewFrame = null; previewHostRequest = null
        job.previewFrameOwner?.close(); job.previewFrameOwner = null
        job.fence.discard(job.descriptor.session); capture?.cancel()
        setPhase(job, RecordingSegmentsPhase.CANCELLING)
        if (!job.fence.isCaptureClosed) armFinalizationDeadline(job) else processingWatchdog.stopping(job)
    }
    fun serviceLost(id: String) { requireMain(); stop(id, RecordingStopReason.SYSTEM_STOPPED) }
    fun serviceDestroyed(service: RecordingForegroundService, id: String) {
        requireMain(); active?.takeIf { it.descriptor.sessionId == id && it.service === service }?.service = null
    }
    private fun armPreparationDeadline(job: Job, captureRequired: Boolean) {
        check(preparationTimeout == null)
        val timeout = Runnable {
            if (active === job && recordingStartStillPending(captureRequired, job.workerStarted, job.encodedStarted)) {
                job.cancellation.cancel(); job.fence.revoke(job.descriptor.session)
                if (job.preview && !job.encodedStarted) {
                    job.previewGate?.invalidate(RegionPreviewInvalidation.FIRST_FRAME_TIMEOUT)
                    previewFrame = null; previewHostRequest = null; previewActive = false; previewReturnToControls = true
                    job.previewFrameOwner?.close(); job.previewFrameOwner = null
                }
                if (captureRequired) { lease.abandon(); capture?.cancel() }
                setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    "准备操作超过时间预算，已请求停止。等待实际工作线程和资源退出，私有文件仍受本会话管理。")
            }
        }
        preparationTimeout = timeout
        main.postDelayed(timeout, if (captureRequired && job.preview) 100_000 else if (captureRequired) limits.prepareTimeoutUs / 1000 else 30_000)
    }

    private fun armPreviewDeadline(job: Job) {
        val check = object : Runnable {
            override fun run() {
                if (active !== job || !job.preview || job.encodedStarted) return
                val gate = job.previewGate ?: return
                if (gate.expire(monotonicUs())) {
                    invalidatePreview(job, gate.invalidationReason ?: RegionPreviewInvalidation.CANCELLED)
                    return
                }
                main.postDelayed(this, 200)
            }
        }
        previewDeadline = check; main.postDelayed(check, 200)
    }

    private fun previewPort(job: Job): NativeRegionPreviewPort = object : NativeRegionPreviewPort {
        override val geometryGeneration = job.descriptor.generation
        private fun authorized(): Boolean = active === job && !job.cancellation.isCancelled && !job.fence.isRevoked &&
            featureAllowed(Feature.REGION_RECORDING)
        override fun eligibility(): NativeRegionPreviewEligibility {
            val gate = job.previewGate ?: return NativeRegionPreviewEligibility.Cancelled
            if (!authorized() || gate.expire(monotonicUs())) return NativeRegionPreviewEligibility.Cancelled
            val ready = gate.hostReadyAtMonotonicUs ?: return NativeRegionPreviewEligibility.Waiting
            if (!foreground || (recordingPreviewRequiresHostFocus(gate.phase) && !previewHostFocused))
                return NativeRegionPreviewEligibility.Cancelled
            return NativeRegionPreviewEligibility.Eligible(ready + 1)
        }
        override fun offer(frame: NativeRegionPreviewFrame): Boolean {
            val gate = job.previewGate ?: return false
            if (!foreground || !previewHostFocused || !authorized() || !gate.publish(frame.snapshot, monotonicUs())) return false
            job.previewFrameOwner = frame
            main.post {
                if (active !== job || !foreground || !previewHostFocused || !authorized() || gate.expire(monotonicUs()) ||
                    gate.phase != RegionPreviewPhase.VISIBLE || gate.snapshot != frame.snapshot ||
                    DefaultDisplayCaptureGeometry.read(application) != frame.snapshot.observation.geometry) {
                    frame.close()
                    if (active === job) invalidatePreview(job, RegionPreviewInvalidation.HOST_DISAPPEARED)
                } else {
                    previewFrame = frame
                    setPhase(job, RecordingSegmentsPhase.PREPARING, "已取得系统授权流中的一张冻结画面。请检查实际内容后框选并明确确认；尚未录制或采集声音。")
                }
            }
            return true
        }
        override fun takeConfirmedSelection(nowMonotonicUs: Long): NativeRegionPreviewSelection? {
            val gate = job.previewGate ?: return null
            if (!foreground || !previewHostFocused || !authorized()) return null
            val confirmation = gate.takeReady(nowMonotonicUs) ?: return null
            return NativeRegionPreviewSelection(confirmation, confirmation.captureReadyAtMonotonicUs + 1)
        }
        override fun confirmed(descriptor: CaptureDescriptor): Boolean {
            val gate = job.previewGate ?: return false
            val confirmation = gate.confirmation ?: return false
            if (!foreground || !previewHostFocused || !authorized() || gate.isInvalidated || descriptor.session != job.descriptor.session ||
                descriptor.regionCaptureRecipe != confirmation.recipe ||
                DefaultDisplayCaptureGeometry.read(application) != confirmation.snapshot.observation.geometry) return false
            job.descriptor = descriptor
            segmentStorage.prepareSession(descriptor, confirmation.recipe.source, confirmation.recipe)
            job.journalCreated = true
            if (!foreground || !previewHostFocused || !authorized() || gate.isInvalidated) return false
            job.previewNativeConfirmed = true
            return true
        }
        override fun invalidate(error: RecordingError) {
            job.previewGate?.invalidate(when (error) {
                RecordingError.FORMAT_CHANGED -> RegionPreviewInvalidation.GEOMETRY_CHANGED
                RecordingError.DEADLINE_EXCEEDED -> RegionPreviewInvalidation.SNAPSHOT_EXPIRED
                else -> RegionPreviewInvalidation.SYSTEM_STOPPED
            })
            job.previewFrameOwner?.close()
            main.post {
                if (active === job && !job.encodedStarted) {
                    previewFrame = null; previewHostRequest = null
                }
            }
        }
    }

    private fun armFinalizationDeadline(job: Job) {
        if (finalizationTimeout != null) return
        val timeout = Runnable {
            if (active === job && !job.fence.isCaptureClosed) {
                job.fence.revoke(job.descriptor.session); job.cancellation.cancel(); lease.abandon(); capture?.cancel()
                setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED, "已请求停止，但系统资源尚未确认退出。相关文件和前台状态保留，不能开始新任务。")
            }
        }
        finalizationTimeout = timeout; main.postDelayed(timeout, limits.finalizationTimeoutUs / 1000)
    }
    private fun captureAndProcess(job: Job) {
        val descriptor = job.descriptor
        var projection: MediaProjection? = null
        var delegated = false
        var outcome: RecordingOutcome? = null
        try {
            if (!newRecordingAllowed()) throw RecordingFailure(RecordingError.CAPTURE_UNAVAILABLE)
            if (!job.fence.startNative(descriptor.session) || job.cancellation.isCancelled) return
            if (!featureAllowed(Feature.REGION_RECORDING)) throw RecordingFailure(RecordingError.ENTITLEMENT_REQUIRED)
            val recipe = requireNotNull(descriptor.regionCaptureRecipe)
            if (recipe.source != DefaultDisplayCaptureGeometry.read(application)) throw RecordingFailure(RecordingError.FORMAT_CHANGED)
            if (!job.preview) { segmentStorage.prepareSession(descriptor, recipe.source, recipe); job.journalCreated = true }
            val grant = ProjectionGrantHandoff.take(descriptor.sessionId) ?: throw RecordingFailure(RecordingError.CONSENT_DENIED)
            projection = application.getSystemService(MediaProjectionManager::class.java).getMediaProjection(grant.resultCode, grant.data)
                ?: throw RecordingFailure(RecordingError.CAPTURE_UNAVAILABLE)
            val outputs = object : NativeRecordingSegmentOutputs {
                override fun open(index: Int): NativeRecordingOutput {
                    if (job.cancellation.isCancelled) throw RecordingFailure(RecordingError.CANCELLED)
                    val output = segmentStorage.openOutput(descriptor.session, index)
                    return NativeRecordingOutput(output.openWriter, output.retainNativeWriter, output.availableBytes)
                }
                override fun closed(index: Int, expectation: RecordingArtifactExpectation, timing: RecordingFrameTimeline,
                    geometry: CaptureGeometryObservation) {
                    // Closure evidence is native-issued. It is not independent media verification.
                    RegionCapturePolicy.confirm(requireNotNull(job.descriptor.regionCaptureRecipe), geometry)
                    segmentStorage.confirmClosed(descriptor.session, index, expectation, timing, geometry)
                    val snapshot = segmentStorage.snapshot(descriptor.session)
                    main.post { if (active === job) updateRows(snapshot) }
                }
                override fun abandoned(index: Int, error: RecordingError) {
                    if (error == RecordingError.RESOURCE_RELEASE_UNCONFIRMED) segmentStorage.fence(descriptor.session)
                    else segmentStorage.abandoned(descriptor.session, index)
                }
            }
            val next = AndroidRecordingSession(application, projection, descriptor, limits, lease,
                openWriter = { error("Advanced output capability required") },
                retainNativeWriter = { error("Advanced output capability required") },
                availableBytes = { 0 }, onProgress = { progress ->
                    // Capture the native stop reason synchronously before run() returns. Posting
                    // the UI update alone can lose the reason on a fast paused safe-stop.
                    if (active === job) {
                        if (progress.state == RecordingState.RECORDING) job.encodedStarted = true
                        progress.stopReason?.takeIf { it != RecordingStopReason.USER_CANCELLED }
                            ?.let { job.fence.stopAndSave(descriptor.session, it) }
                    }
                    main.post { onProgress(job, progress) }
                },
                segmentOutputs = outputs, previewPort = if (job.preview) previewPort(job) else null)
            capture = next
            if (job.cancellation.isCancelled || job.fence.isRevoked) next.cancel()
            else job.fence.stopReason?.let(next::stop)
            delegated = true; outcome = next.run()
        } catch (_: Exception) {
            outcome = RecordingOutcome.Failed(RecordingError.CAPTURE_UNAVAILABLE)
        } finally {
            if (!delegated) {
                var confirmed = true
                try { projection?.stop() } catch (_: RuntimeException) { confirmed = false }
                if (!confirmed) projection?.let(lease::keepAlive)
                if (lease.occupied) lease.released(confirmed)
            }
            job.previewFrameOwner?.close(); job.previewFrameOwner = null
            job.previewGate?.invalidate(RegionPreviewInvalidation.RESOURCE_RELEASED)
            capture = null; ProjectionGrantHandoff.discard(descriptor.sessionId)
            val confirmed = !lease.occupied
            job.fence.nativeReleased(descriptor.session, confirmed)
            main.post {
                previewFrame = null; previewHostRequest = null
                if (job.preview && !job.encodedStarted) { previewActive = false; previewReturnToControls = true }
                previewDeadline?.let(main::removeCallbacks); previewDeadline = null
                nativeTimeout?.let(main::removeCallbacks); nativeTimeout = null
                preparationTimeout?.let(main::removeCallbacks); preparationTimeout = null
                finalizationTimeout?.let(main::removeCallbacks); finalizationTimeout = null
                if (confirmed) job.service?.finishSession(descriptor.sessionId)
            }
            finishCapture(job, outcome, confirmed)
        }
    }
    private fun finishCapture(job: Job, outcome: RecordingOutcome?, confirmed: Boolean) {
        val session = job.descriptor.session
        if (outcome is RecordingOutcome.Failed) job.captureNotice =
            "录制中断或最后一段未完成，仅尝试保存此前已关闭的有效片段。"
        else job.fence.stopReason?.takeIf { it != RecordingStopReason.USER_STOP }?.let {
            job.captureNotice = recordingStopReasonLabel(it) + "。成片仅包含安全关闭并通过验证的已录部分。"
        }
        if (!confirmed) {
            if (job.journalCreated) runCatching { segmentStorage.fence(session) }
            main.post { setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED, "系统资源未确认退出，片段保留；请完全退出应用后恢复检查。") }
            return // Retain global owner and active job; no replacement native lane.
        }
        if (!job.journalCreated) {
            main.post {
                setPhase(job, if (job.fence.isDiscarded) RecordingSegmentsPhase.CANCELLED else RecordingSegmentsPhase.FAILED,
                    "未形成可处理的录制片段。需要重新选择并申请系统授权。")
                finishOwner(job, false)
            }; return
        }
        if (job.fence.isDiscarded) { cleanupDiscard(job); return }
        val snapshot = runCatching { segmentStorage.stopped(session); segmentStorage.snapshot(session) }.getOrNull()
        if (snapshot == null || job.fence.isRevoked || !foreground || snapshot.segments.none { it.durationUs != null }) {
            runCatching { segmentStorage.retainAfterFailure(session) }
            val retained = runCatching { segmentStorage.snapshot(session) }.getOrNull()
            main.post {
                selected = retained
                if (retained != null) showSnapshot(retained, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    if (!foreground) "录制已关闭，私有片段保留。回到前台后可尝试处理。" else "录制未完成或被中断。有效私有片段保留，可尝试处理或明确放弃。")
                else setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED, "片段状态待恢复，未发布成功结果。")
                finishOwner(job, bridge.requiresProcessRecovery())
            }; return
        }
        // A failed final epoch may leave a valid closed prefix. Processing independently validates
        // that prefix and can never turn loose files or the last native result into group success.
        process(job)
    }
    private fun process(job: Job) {
        val session = job.descriptor.session
        if (!job.fence.beginProcessing(session)) {
            if (job.fence.isDiscarded && job.journalCreated && !bridge.requiresProcessRecovery()) cleanupDiscard(job)
            else {
                val snapshot = runCatching { segmentStorage.retainAfterFailure(session); segmentStorage.snapshot(session) }.getOrNull()
                main.post {
                    selected = snapshot
                    if (snapshot != null) showSnapshot(snapshot, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                        "本次处理已停止，私有片段保留，未发布新的成片。")
                    else setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED, "处理已停止，但片段清单暂不可读取。未确认清理或发布，请重新打开应用恢复检查。")
                    finishOwner(job, bridge.requiresProcessRecovery())
                }
            }
            return
        }
        val context = OperationContext(UUID.randomUUID().toString(),
            System.currentTimeMillis() + EditLimits.EXECUTION_TIMEOUT_MILLIS + EditLimits.MAX_VERIFY_TIMEOUT_MILLIS,
            job.cancellation, resourceBudget = ResourceBudget(1, EditLimits.MAX_OUTPUT_BYTES))
        main.post { if (active === job) processingWatchdog.processing(job,
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1), job.cancellation.isCancelled) }
        storage.destinations.linkPublication(context.operationId, session.sessionId)
        val result = bridge.verifyAndPublish(session, context) { stage -> main.post {
            if (active === job && !job.fence.isRevoked) setPhase(job,
                if (stage == EditWorkStage.VERIFYING) RecordingSegmentsPhase.VERIFYING else RecordingSegmentsPhase.PROCESSING)
        } }
        if (job.fence.isDiscarded && result !is EditRunResult.Published && !bridge.requiresProcessRecovery()) {
            cleanupDiscard(job); return
        }
        val snapshot = runCatching { segmentStorage.snapshot(session) }.getOrNull()
        main.post {
            selected = snapshot
            when (result) {
                is EditRunResult.Published -> {
                    // The storage commit's cancellation fence decides the exact race. A later
                    // cancel cannot erase an already-published user result as cleanup.
                    completedReference = result.reference
                    if (snapshot != null) showSnapshot(snapshot, RecordingSegmentsPhase.SUCCEEDED,
                        (job.captureNotice?.plus("\n") ?: "") + "新视频已验证并入库。私有原片段仍保留，可明确清理；成片可继续裁剪或压缩。")
                    else presentation = RecordingSegmentsPresentation(RecordingSegmentsPhase.SUCCEEDED,
                        recipe = job.descriptor.regionCaptureRecipe, verifiedOutputAvailable = true,
                        notice = "成片已验证并入库；私有片段清单暂不可读取，未宣称已清理。请稍后恢复检查。")
                    published()
                }
                is EditRunResult.Stopped -> if (snapshot != null) showSnapshot(snapshot,
                    if (result.requiresRecovery) RecordingSegmentsPhase.RECOVERY_REQUIRED else RecordingSegmentsPhase.FAILED,
                    errorText(EditFailure(result.error), runtimeText))
                else setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    "处理已停止，但片段清单和提交状态暂不可读取。文件保留，请重新打开应用恢复核对。")
            }
            finishOwner(job, bridge.requiresProcessRecovery())
        }
    }
    fun retryProcessing() {
        requireMain()
        val selected = selected ?: return
        if (!ready || !foreground || active != null || !otherMediaIdle() || selected.publishedReference != null) return
        val job = Job(selected.descriptor)
        if (!OrdinaryMediaNativeResources.claim(job.owner)) return
        active = job; job.journalCreated = true; job.submitted = true
        armPreparationDeadline(job, captureRequired = false)
        job.fence.nativeReleased(job.descriptor.session, true)
        setPhase(job, RecordingSegmentsPhase.VERIFYING)
        try { worker.execute { runOwnedJob(job) { process(job) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            showSnapshot(selected, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                "处理队列暂时繁忙，本次重试未开始。私有片段保留，请稍后重试。")
            finishOwner(job, false)
        }
    }
    fun nextRetained() {
        requireMain()
        if (!ready || !foreground || active != null || !otherMediaIdle()) return
        val prior = selected?.descriptor?.session
        try { worker.execute {
            val snapshots = runCatching { segmentStorage.listRecoverableSessions() }.getOrNull() ?: return@execute
            main.post {
                if (active != null || !foreground || selected?.descriptor?.session != prior) return@post
                retainedSessionCount = snapshots.size
                if (snapshots.isEmpty()) return@post
                val previousIndex = snapshots.indexOfFirst { it.descriptor.session == prior }
                val next = snapshots[(previousIndex + 1) % snapshots.size]
                selected = next
                showSnapshot(next, if (next.publishedReference != null) RecordingSegmentsPhase.SUCCEEDED else RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    "正在查看另一组已保留私有片段。不会恢复或复用系统录屏授权。")
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Existing owner remains authoritative. */ }
    }
    fun discardRetained() {
        requireMain()
        active?.let { discard(it.descriptor.sessionId); return }
        val selected = selected ?: return
        if (!ready || !foreground || !otherMediaIdle()) return
        val job = Job(selected.descriptor)
        if (!OrdinaryMediaNativeResources.claim(job.owner)) return
        active = job; job.journalCreated = true; job.submitted = true
        armPreparationDeadline(job, captureRequired = false)
        job.fence.nativeReleased(job.descriptor.session, true); job.fence.discard(job.descriptor.session)
        setPhase(job, RecordingSegmentsPhase.CANCELLING)
        try { worker.execute { runOwnedJob(job) { cleanupDiscard(job) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            showSnapshot(selected, if (selected.publishedReference != null) RecordingSegmentsPhase.SUCCEEDED else RecordingSegmentsPhase.RECOVERY_REQUIRED,
                "处理队列暂时繁忙，本次清理未开始。私有片段和已发布成片保留，请稍后重试。")
            finishOwner(job, false)
        }
    }
    private fun cleanupDiscard(job: Job) {
        main.post { if (active === job) processingWatchdog.stopping(job) }
        val outcome = runCatching { segmentStorage.discard(job.descriptor.session) }
        val snapshot = runCatching { segmentStorage.snapshot(job.descriptor.session) }.getOrNull()
        val retained = runCatching { segmentStorage.listRecoverableSessions().size }.getOrNull()
        main.post {
            retained?.let { retainedSessionCount = it }
            selected = snapshot
            if (outcome.getOrNull()?.complete == true) {
                val finalRef = snapshot?.publishedReference ?: completedReference
                completedReference = finalRef
                presentation = RecordingSegmentsPresentation(if (finalRef == null) RecordingSegmentsPhase.CANCELLED else RecordingSegmentsPhase.SUCCEEDED,
                    recipe = job.descriptor.regionCaptureRecipe, verifiedOutputAvailable = finalRef != null,
                    notice = "已清理本次私有片段。文件删除不代表物理安全擦除；已发布成片不会因清理片段被删除。")
            } else if (snapshot != null) showSnapshot(snapshot,
                if (snapshot.publishedReference != null) RecordingSegmentsPhase.SUCCEEDED else RecordingSegmentsPhase.RECOVERY_REQUIRED,
                "仍有私有片段未确认清理。没有宣称已经全部删除；已发布成片保留。请稍后重试或重启恢复检查。")
            else setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                "清理操作已结束，但无法核对剩余片段。没有宣称已经全部删除，请重新打开应用恢复检查。")
            finishOwner(job, bridge.requiresProcessRecovery())
        }
    }
    private fun onProgress(job: Job, progress: RecordingCaptureProgress) {
        if (active !== job || job.fence.isRevoked) return
        if (progress.state == RecordingState.RECORDING && job.preview) {
            previewActive = false
            previewDeadline?.let(main::removeCallbacks); previewDeadline = null
        }
        if (progress.state == RecordingState.PAUSED && job.fence.stopReason == null) {
            finalizationTimeout?.let(main::removeCallbacks); finalizationTimeout = null
        }
        val phase = if (job.fence.stopReason != null) RecordingSegmentsPhase.STOPPING else when (progress.state) {
            RecordingState.RECORDING -> RecordingSegmentsPhase.RECORDING
            RecordingState.PAUSED -> RecordingSegmentsPhase.PAUSED
            RecordingState.RESUMING -> RecordingSegmentsPhase.RESUMING
            RecordingState.STOPPING, RecordingState.FINALIZING -> RecordingSegmentsPhase.STOPPING
            else -> RecordingSegmentsPhase.PREPARING
        }
        val rows = presentation?.closedSegments.orEmpty()
        presentation = RecordingSegmentsPresentation(phase, rows, maxOf(progress.effectiveDurationUs, rows.sumOf { it.effectiveDurationUs }),
            progress.effectiveResumeCount, job.descriptor.regionCaptureRecipe, progress.audioStatus)
        recordingSegmentNotificationStatus(phase)?.let { job.service?.showStatus(job.descriptor.sessionId, it) }
        if (phase == RecordingSegmentsPhase.STOPPING) armFinalizationDeadline(job)
    }
    private fun updateRows(snapshot: AndroidRecordedSegmentStorage.Snapshot) {
        selected = snapshot
        val current = presentation ?: return
        val rows = snapshot.segments.filter { it.durationUs != null }.map { RecordingSegmentRow(it.ordinal, it.durationUs!!, it.independentlyValid, it.retained, it.cleanupPending) }
        presentation = RecordingSegmentsPresentation(current.phase, rows, maxOf(current.effectiveDurationUs, rows.sumOf { it.effectiveDurationUs }),
            current.effectiveResumeCount, snapshot.region, current.audioStatus, current.notice)
    }
    private fun showSnapshot(snapshot: AndroidRecordedSegmentStorage.Snapshot, phase: RecordingSegmentsPhase, notice: String?) {
        presentationOwner = active
        val rows = snapshot.segments.filter { it.durationUs != null }.map { RecordingSegmentRow(it.ordinal, it.durationUs!!, it.independentlyValid, it.retained, it.cleanupPending) }
        completedReference = snapshot.publishedReference
        presentation = RecordingSegmentsPresentation(phase, rows, effectiveResumeCount = maxOf(0, rows.size - 1), recipe = snapshot.region,
            notice = notice, canRetryProcessing = rows.isNotEmpty() && snapshot.state in setOf("RETAINED", "STOPPED") &&
                phase in setOf(RecordingSegmentsPhase.RECOVERY_REQUIRED, RecordingSegmentsPhase.FAILED),
            verifiedOutputAvailable = phase == RecordingSegmentsPhase.SUCCEEDED && snapshot.publishedReference != null)
    }
    private fun setPhase(job: Job, phase: RecordingSegmentsPhase, notice: String? = null) {
        if (active !== job) return
        val old = presentation
        val safePhase = recordingPreserveWaitingPhase(old?.phase, phase, presentationOwner === job)
        presentationOwner = job
        presentation = RecordingSegmentsPresentation(safePhase, old?.closedSegments.orEmpty(), old?.effectiveDurationUs ?: 0,
            old?.effectiveResumeCount ?: 0, job.descriptor.regionCaptureRecipe,
            old?.audioStatus ?: RecordingAudioStatus.NOT_REQUESTED, notice)
    }
    private fun finishOwner(job: Job, unknown: Boolean) {
        requireMain()
        if (active !== job) return
        processingWatchdog.completed(job)
        previewFrame = null; previewHostRequest = null; previewActive = false
        if (job.preview && !job.encodedStarted) previewReturnToControls = true
        job.previewFrameOwner?.close(); job.previewFrameOwner = null
        previewDeadline?.let(main::removeCallbacks); previewDeadline = null
        preparationTimeout?.let(main::removeCallbacks); preparationTimeout = null
        if (unknown) { OrdinaryMediaNativeResources.unconfirmed(); activityChanged(); return }
        job.fence.finish(job.descriptor.session)
        OrdinaryMediaNativeResources.released(job.owner); active = null; presentationOwner = null; job.service = null
        consentTimeout?.let(main::removeCallbacks); consentTimeout = null
        released(job.descriptor.sessionId)
        try { worker.execute {
            val count = runCatching { segmentStorage.listRecoverableSessions().size }.getOrNull()
            main.post { count?.let { retainedSessionCount = it } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Refresh may wait for next explicit recovery action. */ }
    }
    private fun runOwnedJob(job: Job, action: () -> Unit) = RecordingWorkerBoundary.run({
        job.workerStarted = true
        action()
    }) {
        // The exceptional path must not query the same failing/blocking storage again. Actual
        // catalog/native state is unknown, so keep owner and files until process recovery.
        job.cancellation.cancel()
        main.post {
            if (active === job) {
                ready = false; processingWatchdog.completed(job)
                setPhase(job, RecordingSegmentsPhase.RECOVERY_REQUIRED,
                    "存储或处理发生异常，提交 / 清理结果待核对。相关文件和任务所有权保留，请完全退出应用后恢复检查。")
                OrdinaryMediaNativeResources.unconfirmed(); activityChanged()
            }
        }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
