package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.normalization.MovRemuxEvidence
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.DirectNormalizationLimits
import com.easysavevideo.save.DirectNormalizationProof
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SaveLimits
import com.easysavevideo.save.SavePhase
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.sourceTransferFingerprint
import java.util.UUID

/**
 * Storage-independent factory exercised by host tests with real state transitions and fake syscalls.
 * Inactive: this does not enable MOV, acquire a native/local lane, or revoke previously escaped Files.
 */
internal class NormalizationAttemptOwner(
    private val ledger: NormalizationLedger,
    private val files: AndroidNormalizationFiles,
    private val authority: (NormalizationOwner) -> NormalizationAuthority,
    private val readSave: (String) -> SaveRecord?,
    private val ownerLock: Any,
    private val closed: () -> Boolean,
    private val ensureNoExistingHandles: (NormalizationOwner) -> Unit,
    private val checkOperation: (OperationContext) -> Unit,
) {
    private val sessions = mutableMapOf<String, OwnedMovRemuxAttempt?>()
    init { require(ownerLock === files.ownerLock) }
    fun hasActiveSession(artifactId: String): Boolean = synchronized(ownerLock) { sessions.containsKey(artifactId) }
    fun hasAnyLeases(): Boolean = synchronized(ownerLock) { sessions.isNotEmpty() || files.hasAnyLeases() }
    fun releaseUnconfirmed(): Boolean = files.releaseUnconfirmed()
    /** The supplied callback is memory-only; IO-exception handling must not query durable authority. */
    internal fun checkStop(context: OperationContext) = checkOperation(context)
    private fun available() {
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        files.requireAvailable()
    }
    private fun active(owner: NormalizationOwner, context: OperationContext, phase: SavePhase = SavePhase.NORMALIZING): SaveRecord {
        available(); checkOperation(context)
        val record = readSave(owner.taskId) ?: fail()
        val transfer = record.transfer ?: fail()
        val artifact = record.artifact ?: fail()
        val auth = authority(owner)
        if (record.task.taskId != owner.taskId || record.commitId != owner.commitId ||
            artifact.artifactId != owner.artifactId || artifact.commitId != owner.commitId ||
            record.task.state != TaskState.RUNNING || record.request.method != TaskMethod.DIRECT ||
            record.phase != phase || record.operationId != context.operationId ||
            record.request.networkResource == null || transfer.artifactId != owner.artifactId ||
            transfer.bytesOnDisk <= 0 || transfer.expectedTotalBytes?.let { it != transfer.bytesOnDisk } == true ||
            transfer.streamExpectation != null || transfer.recordingExpectation != null ||
            auth.owner != owner || auth.taskState != NormalizationTaskState.ACTIVE ||
            auth.parentState != NormalizationParentState.STAGING || auth.sourceTransferSha256 != sourceTransferFingerprint(transfer)) fail()
        return record
    }
    private fun protocol(context: OperationContext? = null, owner: NormalizationOwner? = null,
        phase: SavePhase = SavePhase.NORMALIZING, ledgerPort: NormalizationLedger = ledger) = NormalizationWorkspaceProtocol(ledgerPort, files, authority, ownerLock) {
        available(); if (context != null) { checkOperation(context); if (owner != null) active(owner, context, phase) }
    }
    fun prepare(owner: NormalizationOwner, context: OperationContext, limits: SaveLimits): OwnedMovRemuxAttempt = synchronized(ownerLock) {
        if (active(owner, context).directNormalization != null) fail()
        ensureNoExistingHandles(owner)
        if (sessions.containsKey(owner.artifactId)) fail()
        val prior = when (val found = ledger.lookup(owner)) {
            NormalizationLookup.Absent -> null
            NormalizationLookup.Unreadable -> fail()
            is NormalizationLookup.Present -> found.row.also {
                if (it.key.owner != owner || it.lifecycle != NormalizationLifecycle.RAW_COMPLETE ||
                    it.disposition != NormalizationDisposition.CONTINUE) fail()
            }
        }
        val key = prior?.key ?: NormalizationKey(owner, UUID.randomUUID().toString())
        sessions[owner.artifactId] = null // Preparation itself excludes every other cooperating writer.
        try {
            files.withOperation({ checkOperation(context) }) {
                val record = active(owner, context)
                val transfer = record.transfer ?: fail()
                val budget = context.resourceBudget.maxBytes ?: throw SaveFailure(SaveError.INVALID_REQUEST)
                if (context.resourceBudget.maxConcurrentWork != 1 || budget <= 0) throw SaveFailure(SaveError.INVALID_REQUEST)
                val maximum = minOf(limits.maxArtifactBytes, DirectNormalizationLimits.MAX_BYTES)
                if (transfer.bytesOnDisk > maximum || transfer.bytesOnDisk > budget) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                val outputMaximum = minOf(maximum, budget - transfer.bytesOnDisk)
                if (outputMaximum < 32) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
                files.requireReleased(key)
                val raw = files.fingerprint(key, NormalizationRole.PRIMARY, { checkOperation(context) }, sync = true)
                val source = sourceTransferFingerprint(transfer)
                if (raw.fingerprint.size != transfer.bytesOnDisk || (prior != null && (prior.raw != raw.fingerprint || prior.sourceTransferSha256 != source))) fail()
                active(owner, context)
                // No unlock between the pre-state observation, begin result and minting this grant.
                var acknowledgedFreshTransition = false
                val freshLedger = object : NormalizationLedger by ledger {
                    override fun compareAndSet(expected: NormalizationWorkspace, next: NormalizationWorkspace): Boolean {
                        val fresh = expected.key == key && expected.lifecycle == NormalizationLifecycle.RAW_COMPLETE &&
                            next.lifecycle == NormalizationLifecycle.WRITING && expected.revision == (prior?.revision ?: 1L)
                        val accepted = ledger.compareAndSet(expected, next)
                        // A thrown/lost CAS return may be reconciled by the protocol, but cannot
                        // mint a writer. Only this exact acknowledged transition issues the grant.
                        if (fresh && accepted) acknowledgedFreshTransition = true
                        return accepted
                    }
                }
                val row = protocol(context, owner, ledgerPort = freshLedger).begin(key, raw.fingerprint, source)
                if (!acknowledgedFreshTransition) fail()
                if (row.key != key || row.lifecycle != NormalizationLifecycle.WRITING ||
                    row.revision != (prior?.revision ?: 1L) + 1L || row.raw != raw.fingerprint || row.sourceTransferSha256 != source) fail()
                if ((ledger.lookup(owner) as? NormalizationLookup.Present)?.row != row) fail()
                active(owner, context)
                files.validate(key, NormalizationRole.PRIMARY, raw.identity)
                val empty = files.capture(key, NormalizationRole.OUTPUT, empty = true)
                if (empty.stat.size != 0L) fail()
                OwnedMovRemuxAttempt(this, files, row, context, raw, empty, outputMaximum,
                    record.request.networkResource!!.sourceIdentity, source, Thread.currentThread())
                    .also { sessions[owner.artifactId] = it }
            }
        } catch (failure: Throwable) {
            // A failed return after WRITING never reconstructs a grant. Uncertain temporary handles
            // remain in the descriptor registry even when preparation did not return an attempt.
            sessions.remove(owner.artifactId)
            throw failure
        }
    }
    internal fun check(attempt: OwnedMovRemuxAttempt) = synchronized(ownerLock) {
        if (sessions[attempt.key.owner.artifactId] !== attempt) fail()
        val record = active(attempt.key.owner, attempt.context)
        if (record.request.networkResource!!.sourceIdentity != attempt.sourceIdentity ||
            sourceTransferFingerprint(record.transfer!!) != attempt.sourceTransferSha256) fail()
        if ((ledger.lookup(attempt.key.owner) as? NormalizationLookup.Present)?.row != attempt.writingRow) fail()
    }
    internal fun retire(attempt: OwnedMovRemuxAttempt) = synchronized(ownerLock) {
        if (attempt.isRetirable() && sessions[attempt.key.owner.artifactId] === attempt) sessions.remove(attempt.key.owner.artifactId)
    }
    fun finish(attempt: OwnedMovRemuxAttempt, evidence: MovRemuxEvidence, context: OperationContext): DirectNormalizationProof = synchronized(ownerLock) {
        // A foreign owner cannot revoke a capability it does not own. Once the exact local
        // session is established, every rejected finish permanently revokes retained evidence.
        if (sessions[attempt.key.owner.artifactId] !== attempt) fail()
        try {
            if (context !== attempt.context && context != attempt.context) fail()
            check(attempt)
            attempt.consumeForFinish()
            files.withOperation({ checkOperation(context) }) {
                val raw = attempt.raw.fingerprint
                val output = attempt.finalizedIdentity ?: fail()
                if (evidence.rawSizeBytes != raw.size || evidence.rawSha256 != raw.sha256 ||
                    evidence.outputSizeBytes != output.stat.size || evidence.outputSizeBytes !in 1..attempt.outputMaximum || evidence.conservation.audio != null) fail()
                files.requireReleased(attempt.key)
                files.validate(attempt.key, NormalizationRole.PRIMARY, attempt.raw.identity)
                files.validate(attempt.key, NormalizationRole.OUTPUT, output)
                val record = active(attempt.key.owner, context)
                val proof = DirectNormalizationProof(attempt.key.normalizationId, attempt.key.owner.taskId,
                    attempt.sourceIdentity, attempt.key.owner.artifactId, attempt.key.owner.commitId,
                    raw.size, raw.sha256, attempt.sourceTransferSha256, evidence.outputSizeBytes, evidence.outputSha256, evidence.conservation)
                if (!proof.matchesTransfer(record.transfer!!)) fail()
                // Full fresh P/O hashes and output fsync precede this durable transition.
                protocol(context, attempt.key.owner).finishOutput(attempt.key, proof)
                proof
            }
        } catch (failure: Throwable) {
            attempt.rejectFinish()
            throw failure
        } finally { attempt.finishEnded(); retire(attempt) }
    }
    private fun noSession(key: NormalizationKey) {
        available()
        if (sessions.containsKey(key.owner.artifactId)) fail()
        ensureNoExistingHandles(key.owner)
    }
    fun inspect(owner: NormalizationOwner): NormalizationInspection = synchronized(ownerLock) {
        try {
            if (closed() || sessions.containsKey(owner.artifactId)) NormalizationInspection.Unreadable
            else { ensureNoExistingHandles(owner); protocol().inspect(owner) }
        } catch (_: Exception) { NormalizationInspection.Unreadable }
    }
    fun recordAdoptionIntent(key: NormalizationKey, proof: DirectNormalizationProof, context: OperationContext): NormalizationWorkspace = synchronized(ownerLock) {
        noSession(key)
        val record = active(key.owner, context, SavePhase.ADOPTING)
        if (record.directNormalization != proof || proof.normalizationId != key.normalizationId) fail()
        files.withOperation({ checkOperation(context) }) { protocol(context, key.owner, SavePhase.ADOPTING).recordAdoptionIntent(key, proof) }
    }
    fun adopt(key: NormalizationKey, context: OperationContext): NormalizationWorkspace = synchronized(ownerLock) {
        noSession(key)
        val record = active(key.owner, context, SavePhase.ADOPTING)
        if (record.directNormalization?.normalizationId != key.normalizationId) fail()
        files.withOperation({ checkOperation(context) }) { protocol(context, key.owner, SavePhase.ADOPTING).adopt(key) }
    }
    fun cleanup(key: NormalizationKey, expectedRevision: Long): NormalizationWorkspace = synchronized(ownerLock) {
        noSession(key); protocol().cleanup(key, expectedRevision)
    }
    private fun fail(): Nothing = throw NormalizationRecovery()
}
