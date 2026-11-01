package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.discovery.DiscoverySaveEvaluationSession
import com.easysavevideo.discovery.SaveEvaluationAttempt
import com.easysavevideo.discovery.SaveEvaluationMethod
import com.easysavevideo.discovery.SaveEvaluationOutcome
import com.easysavevideo.discovery.SaveEvaluationSessionState
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingArtifactExpectation
import com.easysavevideo.recording.RecordingAudioOutcome
import com.easysavevideo.recording.RecordingStopReason
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SavePhase
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.matches

/** Native observations from this process only; neither UI state nor persisted recovery is evidence. */
internal data class RecordingSaveEvaluationNativeEvidence(
    val started: Boolean,
    val released: Boolean,
    val completed: RecordingArtifactExpectation?,
)

/** Exact, memory-only capability. No URL, page, provider, permission grant or serialization. */
internal class RecordingSaveEvaluationBinding internal constructor() {
    private var descriptor: CaptureDescriptor? = null
    private var accepted = false
    private var active = true
    private var started = false
    private var released = false
    private var completed: RecordingArtifactExpectation? = null

    @Synchronized internal fun prepare(value: CaptureDescriptor) { if (active && descriptor == null) descriptor = value }
    @Synchronized internal fun accept() { if (active) accepted = true }
    @Synchronized internal fun isPreparedFor(value: CaptureDescriptor): Boolean = active && descriptor === value
    /** Called only from native mux-progress, never controller/UI snapshots. */
    @Synchronized internal fun nativeStarted(value: CaptureDescriptor) {
        if (active && accepted && descriptor === value && !released) started = true
    }
    /** Called only after the actual native worker returned and its resource lease is empty. */
    @Synchronized internal fun nativeReleased(value: CaptureDescriptor) {
        if (active && accepted && descriptor === value) released = true
    }
    /** Called only for the actual completed output after size and publication checks. */
    @Synchronized internal fun nativeCompleted(value: CaptureDescriptor, expectation: RecordingArtifactExpectation) {
        if (active && accepted && descriptor === value && started && released && expectation.descriptor == value && completed == null)
            completed = expectation
    }
    @Synchronized internal fun evidence() = RecordingSaveEvaluationNativeEvidence(started, released, completed)
    @Synchronized internal fun retire() { active = false; descriptor = null; completed = null }
    override fun toString() = "RecordingSaveEvaluationBinding([redacted])"
}

/**
 * BASIC's complete user-stopped recorded interval, not proof of the source video's full duration.
 * Truncated system/budget prefixes and unknown/interrupted audio cannot become evaluation success.
 */
internal fun classifyEvaluationRecording(descriptor: CaptureDescriptor, record: SaveRecord?,
    native: RecordingSaveEvaluationNativeEvidence): SaveEvaluationTaskResult {
    if (descriptor.regionCaptureRecipe != null || record == null || record.task.taskId != descriptor.sessionId ||
        record.request.method != TaskMethod.RECORD || record.task.method != TaskMethod.RECORD ||
        record.request.captureDescriptor != descriptor) return SaveEvaluationTaskResult.UNKNOWN
    if (record.task.state == TaskState.SUCCEEDED) {
        if (record.error != null || record.phase != SavePhase.CLEANUP || !native.started || !native.released)
            return SaveEvaluationTaskResult.UNKNOWN
        val expectation = record.transfer?.recordingExpectation ?: return SaveEvaluationTaskResult.UNKNOWN
        // A recovery or another worker's committed output cannot replace this native result.
        if (native.completed != expectation) return SaveEvaluationTaskResult.UNKNOWN
        if (expectation.stopReason != RecordingStopReason.USER_STOP || expectation.audioOutcome in setOf(
                RecordingAudioOutcome.INTERNAL_AUDIO_INTERRUPTED, RecordingAudioOutcome.INTERNAL_AUDIO_SILENT_OR_UNKNOWN))
            return SaveEvaluationTaskResult.BLOCKED
        val artifact = record.artifact ?: return SaveEvaluationTaskResult.UNKNOWN
        val receipt = record.receipt ?: return SaveEvaluationTaskResult.UNKNOWN
        val verification = record.verification ?: return SaveEvaluationTaskResult.UNKNOWN
        val transfer = requireNotNull(record.transfer)
        return if (expectation.descriptor == descriptor && expectation.finalized && expectation.resourcesReleased &&
            expectation.resumeBoundaries != null && expectation.bytesWritten == transfer.bytesOnDisk &&
            transfer.expectedTotalBytes == transfer.bytesOnDisk && transfer.streamExpectation == null &&
            artifact.commitId == record.commitId && receipt.commitId == record.commitId &&
            receipt.artifactId == artifact.artifactId && receipt.artifactId == verification.artifactId &&
            receipt.sizeBytes == verification.sizeBytes && receipt.sha256 == verification.sha256 &&
            expectation.matches(verification) && verification.isValidFor(artifact, transfer, expectation.audio != null))
            SaveEvaluationTaskResult.VERIFIED_COMMIT else SaveEvaluationTaskResult.UNKNOWN
    }
    // Uncertain publication/recovery is never an execution failure, including a provisional receipt.
    if (record.receipt != null || record.phase == SavePhase.COMMITTING) return SaveEvaluationTaskResult.UNKNOWN
    if (record.task.state in setOf(TaskState.CANCELLED, TaskState.WAITING_USER, TaskState.INTERRUPTED))
        return SaveEvaluationTaskResult.BLOCKED
    if (record.task.state != TaskState.FAILED) return SaveEvaluationTaskResult.UNKNOWN
    if (record.error in setOf(SaveError.CANCELLED, SaveError.CONSENT_DENIED, SaveError.AUDIO_PERMISSION_DENIED,
            SaveError.CAPTURE_UNAVAILABLE, SaveError.FGS_START_REJECTED, SaveError.PROFILE_UNSUPPORTED,
            SaveError.METHOD_UNSUPPORTED, SaveError.BUSY, SaveError.POLICY_BLOCKED, SaveError.PROTECTED_MEDIA,
            SaveError.VERIFICATION_UNAVAILABLE, SaveError.STORAGE_FULL, SaveError.STORAGE_FAILURE,
            SaveError.BYTE_LIMIT_EXCEEDED, SaveError.CAPTURE_TOO_SHORT, SaveError.CAPTURE_EMPTY))
        return SaveEvaluationTaskResult.BLOCKED
    // Narrow allowlist, only after observed real capture and confirmed native cleanup. PREPARING,
    // recovery, ownership, timeout ambiguity and all future errors deliberately remain UNKNOWN.
    if (native.started && native.released && record.phase in setOf(SavePhase.EXECUTING, SavePhase.VERIFYING) &&
        record.error in setOf(SaveError.INVALID_MEDIA, SaveError.TIMESTAMP_INVALID, SaveError.AUDIO_FAILED,
            SaveError.ENCODER_FAILED, SaveError.FINALIZATION_FAILED)) return SaveEvaluationTaskResult.DEFINITIVE_FAILURE
    return SaveEvaluationTaskResult.UNKNOWN
}

/** Optional side channel: never controls recording admission, execution, Stop, Discard or cleanup. */
internal class RecordingSaveEvaluationTaskLinks(private val capacity: Int = 100) {
    private class Entry(val handle: RecordingSaveEvaluationBinding, val admission: OrdinaryDestinationAdmission,
        val session: DiscoverySaveEvaluationSession, val pageId: String, var sourceCurrent: (() -> Boolean)?,
        var descriptor: CaptureDescriptor? = null, var attempt: SaveEvaluationAttempt? = null,
        var commitId: String? = null, var operationId: String? = null)
    private val entries = linkedMapOf<RecordingSaveEvaluationBinding, Entry>()
    init { require(capacity in 1..100) }

    @Synchronized fun arm(admission: OrdinaryDestinationAdmission, session: DiscoverySaveEvaluationSession,
        pageId: String, sourceCurrent: () -> Boolean): RecordingSaveEvaluationBinding? {
        if (!pageId.matches(Regex("[a-f0-9]{64}")) || admission.source != null || entries.size >= capacity ||
            entries.values.any { it.admission.creationId == admission.creationId } || !active(session)) return null
        return RecordingSaveEvaluationBinding().also { entries[it] = Entry(it, admission, session, pageId, sourceCurrent) }
    }

    /** Browser ownership transfers exactly once, before permission UI can invalidate its page lease. */
    @Synchronized fun prepared(admission: OrdinaryDestinationAdmission, descriptor: CaptureDescriptor): RecordingSaveEvaluationBinding? {
        val entry = entries.values.firstOrNull { it.admission === admission } ?: return null
        if (entry.descriptor != null) return null // Replayed preparation cannot replace the accepted owner.
        if (descriptor.sessionId != admission.creationId || descriptor.regionCaptureRecipe != null ||
            !active(entry.session) || !(try { entry.sourceCurrent?.invoke() == true } catch (_: Exception) { false })) {
            remove(entry); return null
        }
        entry.sourceCurrent = null // Never retain a browser/view closure during capture.
        entry.descriptor = descriptor
        entry.handle.prepare(descriptor)
        return entry.handle
    }

    /** Only a fresh accepted record can issue SAVE3. The evaluation session owns mode policy. */
    @Synchronized fun accepted(binding: RecordingSaveEvaluationBinding?, descriptor: CaptureDescriptor,
        record: SaveRecord, existing: Boolean): RecordingSaveEvaluationBinding? {
        val entry = entries[binding]?.takeIf { it.descriptor === descriptor } ?: return null
        if (entry.attempt != null) return null
        if (!active(entry.session) || existing || record.task.taskId != descriptor.sessionId ||
            record.request.captureDescriptor != descriptor || record.request.method != TaskMethod.RECORD ||
            record.task.method != TaskMethod.RECORD || record.task.state != TaskState.QUEUED || record.task.revision != 0L ||
            record.phase != null || record.error != null || record.artifact != null || record.transfer != null ||
            record.verification != null || record.receipt != null) { remove(entry); return null }
        return try {
            entry.attempt = entry.session.beginMethod(entry.pageId, SaveEvaluationMethod.SAVE3)
            entry.commitId = record.commitId; entry.operationId = record.operationId
            entry.handle.accept()
            entry.handle
        } catch (_: Exception) { remove(entry); null }
    }

    /** No binding means no record read or event; remove-before-read makes completion one-shot. */
    @Synchronized fun afterExecution(binding: RecordingSaveEvaluationBinding?, descriptor: CaptureDescriptor,
        finalRecord: () -> SaveRecord?): SaveEvaluationTaskResult? {
        val entry = entries[binding]?.takeIf { it.descriptor === descriptor && it.attempt != null } ?: return null
        entries.remove(entry.handle)
        val native = entry.handle.evidence()
        entry.handle.retire()
        if (!active(entry.session)) return SaveEvaluationTaskResult.UNKNOWN
        val result = try {
            val record = finalRecord()
            if (record?.commitId != entry.commitId || record?.operationId != entry.operationId) SaveEvaluationTaskResult.UNKNOWN
            else classifyEvaluationRecording(descriptor, record, native)
        } catch (_: Exception) { SaveEvaluationTaskResult.UNKNOWN }
        val outcome = when (result) {
            SaveEvaluationTaskResult.VERIFIED_COMMIT -> SaveEvaluationOutcome.SUCCEEDED
            SaveEvaluationTaskResult.DEFINITIVE_FAILURE -> SaveEvaluationOutcome.FAILED
            SaveEvaluationTaskResult.BLOCKED -> SaveEvaluationOutcome.BLOCKED
            SaveEvaluationTaskResult.UNKNOWN -> null
        }
        if (outcome != null) try { entry.session.finishMethod(requireNotNull(entry.attempt), outcome) } catch (_: Exception) { }
        return result
    }

    /** Positive evidence this exact worker was not handed off; never a native capture failure. */
    @Synchronized fun notLaunched(binding: RecordingSaveEvaluationBinding?, descriptor: CaptureDescriptor) {
        val entry = entries[binding]?.takeIf { it.descriptor === descriptor } ?: return
        remove(entry)
        entry.attempt?.let { try { entry.session.finishMethod(it, SaveEvaluationOutcome.BLOCKED) } catch (_: Exception) { } }
    }
    @Synchronized fun abandon(binding: RecordingSaveEvaluationBinding) { entries[binding]?.let(::remove) }
    @Synchronized fun close(session: DiscoverySaveEvaluationSession) {
        entries.values.filter { it.session === session }.toList().forEach(::remove)
    }
    private fun active(session: DiscoverySaveEvaluationSession) =
        (try { session.snapshot().state } catch (_: Exception) { null }) == SaveEvaluationSessionState.ACTIVE
    private fun remove(entry: Entry) { entries.remove(entry.handle); entry.sourceCurrent = null; entry.handle.retire() }
}
