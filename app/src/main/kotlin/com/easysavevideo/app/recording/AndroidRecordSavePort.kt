package com.easysavevideo.app.recording

import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.recording.*
import com.easysavevideo.save.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The FGS authorizes this one native lane. A timed-out native call never frees its owner by itself. */
internal class AndroidRecordSavePort(
    private val context: Context,
    private val storage: AndroidSaveStorage,
    private val canRecord: () -> Boolean,
    private val canSegment: () -> Boolean,
    private val recordingLimits: RecordingLimits = RecordingLimits(),
    private val changed: (RecordingSnapshot) -> Unit,
) : RecordSavePort {
    private val controller = RecordingController(recordingLimits)
    private val controlOwner = RecordingControlOwner(controller)
    private val lease = RecordingNativeLease()
    private val native = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { job -> Thread(job, "esv-record-native").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile private var session: AndroidRecordingSession? = null
    @Volatile private var request: RecordingRequest? = null
    @Volatile private var serviceRequestId: String? = null
    val isDraining: Boolean get() = lease.releaseUnconfirmed
    val nativeOccupied: Boolean get() = lease.occupied
    override fun availability() = SaveMethodAvailability(true, null)
    override fun requiresProcessRecovery() = lease.releaseUnconfirmed
    private fun <T> control(block: () -> T): T = controlOwner.transition(block)
    private fun now() = SystemClock.elapsedRealtimeNanos() / 1_000
    private fun publish() = controller.snapshot().also(changed)

    @Synchronized fun prepare(descriptor: CaptureDescriptor, audioPermissionGranted: Boolean): Boolean {
        if (lease.occupied) return false
        val next = RecordingRequest(descriptor.sessionId, descriptor.sessionId, descriptor)
        val result = control { controller.start(next, now(), canRecord()) }
        if (!result.accepted) return false
        request = next
        control { controller.userConfirmed(descriptor.session, now(), audioPermissionGranted) }
        publish()
        return true
    }
    @Synchronized fun admitForeground(requestId: String): Boolean {
        val owned = request?.takeIf { it.requestId == requestId } ?: return false
        if (!canRecord() || !ProjectionGrantHandoff.contains(requestId)) return false
        if (controller.snapshot().state == RecordingState.AWAITING_AUDIO_PERMISSION)
            control { controller.audioPermissionResult(owned.descriptor.session, now(), true) }
        val result = control { controller.consentResult(owned.descriptor.session, now(), true, canRecord()) }
        if (!result.accepted) return false
        serviceRequestId = requestId
        publish()
        return true
    }
    @Synchronized fun denied(requestId: String, error: RecordingError = RecordingError.CONSENT_DENIED) {
        val owned = request?.takeIf { it.requestId == requestId } ?: return
        ProjectionGrantHandoff.discard(requestId)
        if (serviceRequestId == requestId) serviceRequestId = null
        if (error == RecordingError.AUDIO_PERMISSION_DENIED && controller.snapshot().state == RecordingState.AWAITING_AUDIO_PERMISSION)
            control { controller.audioPermissionResult(owned.descriptor.session, now(), false) }
        else control { controller.failed(owned.descriptor.session, now(), error) }
        session?.cancel()
        if (!lease.occupied) control { controller.releaseConfirmed(owned.descriptor.session, now()) }
        publish()
    }
    fun foregroundLost(requestId: String) {
        if (serviceRequestId == requestId) serviceRequestId = null
        stop(requestId, RecordingStopReason.SYSTEM_STOPPED)
    }
    override fun snapshot(requestId: String): RecordingSnapshot? = controller.snapshot().takeIf { it.request?.requestId == requestId }
    fun currentSnapshot(): RecordingSnapshot = controller.snapshot()
    @Synchronized override fun pause(requestId: String): RecordingSnapshot? {
        val owned = request?.takeIf { it.requestId == requestId } ?: return null
        val result = control { controller.pause(owned.descriptor.session, now(), canSegment()) }
        if (result.effects.contains(RecordingEffect.PAUSE_CAPTURE)) session?.pause()
        return publish()
    }
    @Synchronized override fun resume(requestId: String): RecordingSnapshot? {
        val owned = request?.takeIf { it.requestId == requestId } ?: return null
        val result = control { controller.resume(owned.descriptor.session, now(), canSegment()) }
        if (result.effects.contains(RecordingEffect.REQUEST_SYNC_FRAME)) session?.resume()
        if (result.effects.contains(RecordingEffect.STOP_AND_FINALIZE)) session?.stop(result.snapshot.stopReason ?: RecordingStopReason.RESUME_LIMIT)
        return publish()
    }
    override fun stopAndSave(requestId: String) = stop(requestId, RecordingStopReason.USER_STOP)
    @Synchronized fun stop(requestId: String, reason: RecordingStopReason): RecordingSnapshot? {
        val owned = request?.takeIf { it.requestId == requestId } ?: return null
        control { controller.stop(owned.descriptor.session, now(), reason) }
        session?.stop(reason)
        return publish()
    }
    @Synchronized override fun cancel(requestId: String): RecordingSnapshot? {
        val owned = request?.takeIf { it.requestId == requestId } ?: return null
        control { controller.cancel(owned.descriptor.session, now()) }
        ProjectionGrantHandoff.discard(requestId)
        session?.cancel()
        if (!lease.occupied) control { controller.releaseConfirmed(owned.descriptor.session, now()) }
        return publish()
    }
    override fun record(requestId: String, descriptor: CaptureDescriptor, artifact: StagingArtifact,
        context: OperationContext, limits: SaveLimits): TransferResult {
        val owned = request?.takeIf { it.requestId == requestId && it.descriptor == descriptor }
            ?: throw SaveFailure(SaveError.INVALID_REQUEST)
        if (serviceRequestId != requestId || !canRecord()) { denied(requestId, RecordingError.CAPTURE_UNAVAILABLE); throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE) }
        if (!lease.tryAcquire()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        val resultFence = RecordingResultFence(lease,
            markUnconfirmed = { control { controller.releaseUnconfirmed(descriptor.session, now()) }; publish() },
            markReleased = { control { controller.releaseConfirmed(descriptor.session, now()) }; publish() })
        val startedAt = now()
        var stopDeadline: Long? = null
        val future = try {
            native.submit<RecordingOutcome> {
                var projection: MediaProjection? = null
                var delegated = false
                try {
                    if (!lease.started()) throw RecordingFailure(RecordingError.CANCELLED)
                    if (resultFence.isAbandoned || context.cancelToken.isCancelled) throw RecordingFailure(RecordingError.CANCELLED)
                    if (serviceRequestId != requestId || !canRecord() || controller.snapshot().state in
                        setOf(RecordingState.CANCELLED, RecordingState.CANCELLING, RecordingState.FAILED, RecordingState.RECOVERY_REQUIRED))
                        throw RecordingFailure(RecordingError.CANCELLED)
                    val grant = ProjectionGrantHandoff.take(requestId) ?: throw RecordingFailure(RecordingError.CONSENT_DENIED)
                    projection = this.context.getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(grant.resultCode, grant.data)
                        ?: throw RecordingFailure(RecordingError.CAPTURE_UNAVAILABLE)
                    val capture = AndroidRecordingSession(this.context, projection, descriptor,
                        recordingLimits.copy(maxFileBytes = minOf(recordingLimits.maxFileBytes, limits.maxArtifactBytes)), lease,
                        openWriter = { storage.openStaging(artifact, false) },
                        retainNativeWriter = { storage.retainNativeWriter(artifact) },
                        availableBytes = { storage.availableBytes(artifact) },
                        onProgress = { progress ->
                            if (!resultFence.isAbandoned) {
                                control { controller.progress(descriptor.session, now(), progress.state, progress.audioStatus,
                                    progress.effectiveDurationUs, progress.actualProfile, progress.stopReason, progress.error, progress.effectiveResumeCount) }
                                publish()
                            }
                        })
                    session = capture
                    val current = controller.snapshot()
                    if (resultFence.isAbandoned || context.cancelToken.isCancelled || current.state == RecordingState.CANCELLING ||
                        current.state == RecordingState.CANCELLED) capture.cancel()
                    else if (current.state == RecordingState.STOPPING) capture.stop(current.stopReason ?: RecordingStopReason.USER_STOP)
                    delegated = true
                    capture.run()
                } finally {
                    if (!delegated) {
                        var released = true
                        try { projection?.stop() } catch (_: RuntimeException) { released = false }
                        if (!released) projection?.let(lease::keepAlive)
                        lease.released(released)
                    }
                    session = null
                    resultFence.ownerFinished()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            lease.rejectQueued(); denied(requestId, RecordingError.CAPTURE_UNAVAILABLE)
            throw SaveFailure(SaveError.CAPTURE_UNAVAILABLE)
        }
        try {
            while (true) {
                val outcome = try { future.get(100, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) {
                    val now = now()
                    if (context.cancelToken.isCancelled) cancel(requestId)
                    val state = controller.snapshot().state
                    if (now - startedAt >= recordingLimits.maxSessionDurationUs && stopDeadline == null)
                        stop(requestId, RecordingStopReason.SESSION_LIMIT)
                    if (state == RecordingState.PREPARING && now - startedAt >= recordingLimits.prepareTimeoutUs && stopDeadline == null)
                        stop(requestId, RecordingStopReason.DEADLINE_EXCEEDED)
                    if (controller.snapshot().state in setOf(RecordingState.STOPPING, RecordingState.FINALIZING,
                            RecordingState.CANCELLING, RecordingState.CANCELLED, RecordingState.FAILED) && stopDeadline == null)
                        stopDeadline = now + recordingLimits.finalizationTimeoutUs
                    if ((stopDeadline != null && now >= stopDeadline!!) || System.currentTimeMillis() >= context.deadlineEpochMillis) {
                        resultFence.abandon { session?.cancel() }
                        throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                    }
                    continue
                }
                if (lease.occupied) {
                    control { controller.releaseUnconfirmed(descriptor.session, now()) }; publish()
                    throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
                }
                control { controller.releaseConfirmed(descriptor.session, now()) }
                return when (outcome) {
                    is RecordingOutcome.Completed -> {
                        val bytes = storage.stagingFile(artifact).length()
                        if (bytes != outcome.expectation.bytesWritten) throw SaveFailure(SaveError.INVALID_MEDIA)
                        control { controller.captureFinished(owned.descriptor.session, now(), outcome.expectation) }
                        publish()
                        if (context.cancelToken.isCancelled || !controller.snapshot().publicationAllowed)
                            throw SaveFailure(SaveError.CANCELLED)
                        TransferResult(artifact.artifactId, bytes, "video/mp4", expectedTotalBytes = bytes,
                            recordingExpectation = outcome.expectation)
                    }
                    is RecordingOutcome.Cancelled -> { control { controller.cancel(descriptor.session, now()) }; publish(); throw SaveFailure(SaveError.CANCELLED) }
                    is RecordingOutcome.Failed -> { control { controller.failed(descriptor.session, now(), outcome.error) }; publish(); throw SaveFailure(mapError(outcome.error)) }
                }
            }
        } catch (failure: SaveFailure) {
            if (failure.code != SaveError.RESOURCE_RELEASE_UNCONFIRMED) {
                control { controller.failed(descriptor.session, now(), RecordingError.FINALIZATION_FAILED) }
                if (!lease.occupied) control { controller.releaseConfirmed(descriptor.session, now()) }
                publish()
            }
            throw failure
        } catch (failure: ExecutionException) {
            val code = (failure.cause as? RecordingFailure)?.code ?: RecordingError.CAPTURE_UNAVAILABLE
            if (lease.occupied) control { controller.releaseUnconfirmed(descriptor.session, now()) }
            else { control { controller.failed(descriptor.session, now(), code) }; control { controller.releaseConfirmed(descriptor.session, now()) } }
            publish()
            throw SaveFailure(if (lease.occupied) SaveError.RESOURCE_RELEASE_UNCONFIRMED else mapError(code))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); resultFence.abandon { session?.cancel() }
            throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        } finally { serviceRequestId = null; ProjectionGrantHandoff.discard(requestId) }
    }
    private fun mapError(error: RecordingError): SaveError = SaveErrors.classify(RecordingFailure(error))
}
