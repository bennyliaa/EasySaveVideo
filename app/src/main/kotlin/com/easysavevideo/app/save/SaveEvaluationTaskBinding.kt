package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.discovery.*
import com.easysavevideo.library.destination.OrdinaryDestinationAdmission
import com.easysavevideo.save.*

internal enum class SaveEvaluationTaskResult { VERIFIED_COMMIT, DEFINITIVE_FAILURE, BLOCKED, UNKNOWN }

/** Final-record classification only. No boolean 'not committed = failed', UI status or URL inference. */
internal fun classifyEvaluationSave(taskId: String, method: TaskMethod, record: SaveRecord?): SaveEvaluationTaskResult {
    if (method !in setOf(TaskMethod.DIRECT, TaskMethod.STREAM) || record == null ||
        record.task.taskId != taskId || record.request.method != method || record.task.method != method)
        return SaveEvaluationTaskResult.UNKNOWN
    if (record.task.state == TaskState.SUCCEEDED) return if (record.error == null && record.phase == SavePhase.CLEANUP &&
        mediaTaskHasCommittedSave(taskId, record)) SaveEvaluationTaskResult.VERIFIED_COMMIT else SaveEvaluationTaskResult.UNKNOWN
    // A provisional/ambiguous publication can never supply either a success or a download failure.
    if (record.receipt != null || record.phase == SavePhase.COMMITTING) return SaveEvaluationTaskResult.UNKNOWN
    if (record.task.state in setOf(TaskState.CANCELLED, TaskState.WAITING_USER, TaskState.INTERRUPTED))
        return SaveEvaluationTaskResult.BLOCKED
    if (record.task.state != TaskState.FAILED) return SaveEvaluationTaskResult.UNKNOWN
    if (record.error in setOf(SaveError.CANCELLED, SaveError.METHOD_UNSUPPORTED, SaveError.BUSY,
            SaveError.POLICY_BLOCKED, SaveError.PROTECTED_MEDIA, SaveError.VERIFICATION_UNAVAILABLE,
            SaveError.AUTHENTICATION_REQUIRED, SaveError.EXPIRED_RESOURCE, SaveError.WIFI_REQUIRED,
            SaveError.NETWORK_UNAVAILABLE, SaveError.RATE_LIMITED, SaveError.STORAGE_FULL,
            SaveError.STORAGE_FAILURE, SaveError.SOURCE_RESELECTION_REQUIRED)) return SaveEvaluationTaskResult.BLOCKED
    // Conservative allowlist: a definite terminal failure while actually executing/verifying.
    // PREPARING, ownership/adoption/recovery errors and new enum cases remain unknown.
    if (record.phase in setOf(SavePhase.EXECUTING, SavePhase.VERIFYING) && record.error in setOf(
            SaveError.INVALID_MEDIA, SaveError.INCOMPLETE_TRANSFER, SaveError.HTTP_NOT_FOUND,
            SaveError.NETWORK_TIMEOUT, SaveError.HTTP_FAILURE, SaveError.RESOURCE_CHANGED, SaveError.INVALID_RANGE))
        return SaveEvaluationTaskResult.DEFINITIVE_FAILURE
    return SaveEvaluationTaskResult.UNKNOWN
}

/** Identity capability, deliberately no task/page/provider fields or serialization. */
internal class SaveEvaluationTaskBinding internal constructor() {
    override fun toString(): String = "SaveEvaluationTaskBinding([redacted])"
}

/** Optional, bounded, memory-only side channel. Never controls submission, execution or media cleanup. */
internal class SaveEvaluationTaskLinks(private val capacity: Int = 100) {
    private class Entry(val handle: SaveEvaluationTaskBinding, val admission: OrdinaryDestinationAdmission,
        val method: TaskMethod, val session: DiscoverySaveEvaluationSession, val pageId: String, var sourceCurrent: () -> Boolean,
        var attempt: SaveEvaluationAttempt? = null, var commitId: String? = null, var operationId: String? = null)
    private val entries = linkedMapOf<SaveEvaluationTaskBinding, Entry>()
    init { require(capacity in 1..100) }

    /** Future explicit evaluation controller supplies the SAME destination admission object before submission. */
    @Synchronized fun arm(admission: OrdinaryDestinationAdmission, method: TaskMethod,
        session: DiscoverySaveEvaluationSession, pageId: String, sourceCurrent: () -> Boolean = { true }): SaveEvaluationTaskBinding? {
        if (!pageId.matches(Regex("[a-f0-9]{64}")) || method !in setOf(TaskMethod.DIRECT, TaskMethod.STREAM) || admission.source != null ||
            entries.size >= capacity || entries.values.any { it.admission.creationId == admission.creationId } ||
            (try { session.snapshot().state } catch (_: Exception) { null }) != SaveEvaluationSessionState.ACTIVE) return null
        return SaveEvaluationTaskBinding().also { entries[it] = Entry(it, admission, method, session, pageId, sourceCurrent) }
    }

    /** Only an exact fresh coordinator acceptance may begin the corresponding method test. */
    @Synchronized fun accepted(admission: OrdinaryDestinationAdmission, record: SaveRecord, existing: Boolean): SaveEvaluationTaskBinding? {
        val entry = entries.values.firstOrNull { it.admission === admission } ?: return null
        // A replay must not erase the real worker's already-issued ownership.
        if (entry.attempt != null) return null
        if (!(try { entry.sourceCurrent() } catch (_: Exception) { false }) || existing || record.task.taskId != entry.admission.creationId || record.request.method != entry.method ||
            record.request.isPrivateNetwork || record.task.state != TaskState.QUEUED || record.task.revision != 0L ||
            record.receipt != null || record.artifact != null) {
            entries.remove(entry.handle); return null
        }
        return try {
            entry.attempt = entry.session.beginMethod(entry.pageId,
                if (entry.method == TaskMethod.DIRECT) SaveEvaluationMethod.SAVE1 else SaveEvaluationMethod.SAVE2)
            entry.sourceCurrent = { true } // Admission-only lease; an accepted task owns its attribution.
            entry.commitId = record.commitId; entry.operationId = record.operationId
            entry.handle
        } catch (_: Exception) { entries.remove(entry.handle); null }
    }

    /** Remove-before-result is one-shot. No binding means no read, callback, or evaluation allocation. */
    @Synchronized fun afterExecution(binding: SaveEvaluationTaskBinding?, taskId: String, finalRecord: () -> SaveRecord?): SaveEvaluationTaskResult? {
        val entry = entries[binding]?.takeIf { it.admission.creationId == taskId && it.attempt != null } ?: return null
        entries.remove(entry.handle)
        if ((try { entry.session.snapshot().state } catch (_: Exception) { null }) != SaveEvaluationSessionState.ACTIVE)
            return SaveEvaluationTaskResult.UNKNOWN
        val result = try {
            val record = finalRecord()
            if (record?.commitId != entry.commitId || record?.operationId != entry.operationId) SaveEvaluationTaskResult.UNKNOWN
            else classifyEvaluationSave(taskId, entry.method, record)
        }
            catch (_: Exception) { SaveEvaluationTaskResult.UNKNOWN }
        val outcome = when (result) {
            SaveEvaluationTaskResult.VERIFIED_COMMIT -> SaveEvaluationOutcome.SUCCEEDED
            SaveEvaluationTaskResult.DEFINITIVE_FAILURE -> SaveEvaluationOutcome.FAILED
            SaveEvaluationTaskResult.BLOCKED -> SaveEvaluationOutcome.BLOCKED
            SaveEvaluationTaskResult.UNKNOWN -> null
        }
        // Unknown leaves the issued attempt unfinished; closing the report exposes partial coverage.
        if (outcome != null) try { entry.session.finishMethod(requireNotNull(entry.attempt), outcome) }
            catch (_: Exception) { /* A closed/stale evaluation never changes the media task. */ }
        return result
    }

    /** Positive evidence of no worker handoff; not an assertion that downloading failed. */
    @Synchronized fun notLaunched(binding: SaveEvaluationTaskBinding?, taskId: String) {
        val entry = entries[binding]?.takeIf { it.admission.creationId == taskId && it.attempt != null } ?: return
        entries.remove(entry.handle)
        entry.attempt?.let { try { entry.session.finishMethod(it, SaveEvaluationOutcome.BLOCKED) } catch (_: Exception) { } }
    }
    @Synchronized fun abandon(handle: SaveEvaluationTaskBinding) { entries.remove(handle) }
    @Synchronized fun close(session: DiscoverySaveEvaluationSession) { entries.entries.removeAll { it.value.session === session } }
}
