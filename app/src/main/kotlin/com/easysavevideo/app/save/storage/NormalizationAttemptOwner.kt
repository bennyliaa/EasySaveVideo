package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.app.save.conversion.OwnedWebmInputBinding
import com.easysavevideo.app.media.transform.TransformerEngine
import com.easysavevideo.app.media.transform.TransformerFailure
import com.easysavevideo.app.media.transform.TransformerError
import com.easysavevideo.app.media.transform.TransformerSession
import com.easysavevideo.app.save.normalization.MovProbeInput
import com.easysavevideo.app.save.normalization.MovRemuxEvidence
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.ArtifactOutputDescriptor
import com.easysavevideo.save.selectedOutputDescriptor
import com.easysavevideo.save.DirectMovClock
import com.easysavevideo.save.DirectRemuxConservation
import com.easysavevideo.save.DirectNormalizationLimits
import com.easysavevideo.save.DirectNormalizationProof
import com.easysavevideo.save.DirectProofCompletion
import com.easysavevideo.save.DirectConversionProof
import com.easysavevideo.save.DirectConversionProofCompletion
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SaveLimits
import com.easysavevideo.save.SavePhase
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.hasDirectOutputProof
import com.easysavevideo.save.sourceTransferFingerprint
import java.io.IOException
import java.util.UUID

/** Storage-issued proof completion. Closed output remains reserved until the exact proof CAS. */
internal class NormalizationProofCompletion internal constructor(
    private val owner: NormalizationAttemptOwner,
    override val proof: DirectNormalizationProof,
    internal val reservation: NormalizationIoReservations.Token,
    internal val expectedRecord: SaveRecord,
    internal val workspace: NormalizationWorkspace,
) : DirectProofCompletion {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    internal fun requireOwnedBy(candidate: NormalizationAttemptOwner) {
        if (owner !== candidate || closed.get()) throw NormalizationRecovery()
        owner.requireCompletion(this)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) owner.closeCompletion(this)
    }
}

/** Exact conversion completion; the original execution Scope stays live through Save CAS/readback. */
internal class NormalizationConversionProofCompletion internal constructor(
    private val owner: NormalizationAttemptOwner,
    override val proof: DirectConversionProof,
    internal val reservation: NormalizationIoReservations.Token,
    internal val expectedRecord: SaveRecord,
    internal val workspace: NormalizationWorkspace,
    internal val scope: AndroidDirectExecutionBridge.Scope,
    internal val context: OperationContext,
) : DirectConversionProofCompletion {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    internal fun requireOwnedBy(candidate: NormalizationAttemptOwner) {
        if (owner !== candidate || closed.get()) throw NormalizationRecovery()
        owner.requireConversionCompletion(this)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) owner.closeConversionCompletion(this)
    }
}

/** Short SQL/identity sections surround off-lock IO; the shared table also protects ledger ABSENT. */
internal class NormalizationAttemptOwner(
    private val ledger: NormalizationLedger,
    private val files: AndroidNormalizationFiles,
    private val authority: (NormalizationOwner) -> NormalizationAuthority,
    private val readSave: (String) -> SaveRecord?,
    private val ownerLock: Any,
    private val closed: () -> Boolean,
    private val ensureNoExistingHandles: (NormalizationOwner) -> Unit,
    private val checkOperation: (OperationContext) -> Unit,
    private val ensureNoCanonicalReaders: (NormalizationOwner) -> Unit = {},
    private val requireTranscodeScope: (AndroidDirectExecutionBridge.Scope) -> Unit = { throw NormalizationRecovery() },
    private val directEngine: TransformerEngine? = null,
) {
    private val sessions = mutableMapOf<String, OwnedMovRemuxAttempt?>()
    private val transcodes = mutableMapOf<String, OwnedDirectTranscodeAttempt?>()
    private val retiredTranscodes = java.util.Collections.newSetFromMap(java.util.WeakHashMap<OwnedDirectTranscodeAttempt, Boolean>())
    private val inputBindings = java.util.IdentityHashMap<OwnedDirectTranscodeAttempt, OwnedWebmInputBinding>()
    private val inputSessions = java.util.IdentityHashMap<OwnedDirectTranscodeAttempt, TransformerSession>()
    private data class RawProbeWitness(val raw: NormalizationBoundFile, val workspace: NormalizationWorkspace?,
        val scope: AndroidDirectExecutionBridge.Scope?)
    private val rawProbes = java.util.IdentityHashMap<NormalizationIoReservations.Token, RawProbeWitness>()
    private val handedOffSelections = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NormalizationIoReservations.Token, Boolean>())
    private val startedProbes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NormalizationIoReservations.Token, Boolean>())
    private val completions = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NormalizationProofCompletion, Boolean>())
    private val conversionCompletions = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<NormalizationConversionProofCompletion, Boolean>())
    internal val reservations get() = files.reservations
    init { require(ownerLock === files.ownerLock) }
    fun hasActiveSession(artifactId: String): Boolean = reservations.hasReservation(artifactId)
    fun hasAnyLeases(): Boolean = reservations.hasAnyReservations() || files.hasAnyLeases()
    fun releaseUnconfirmed(): Boolean = files.releaseUnconfirmed() || reservations.requiresProcessRecovery()
    fun resourcesUnreleased(): Boolean = reservations.resourcesUnreleased() || files.releaseUnconfirmed()
    /** This path never reads SQL or acquires the storage/admission/native lock. */
    internal fun checkStop(context: OperationContext) = checkOperation(context)
    private fun available() {
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        files.requireAvailable()
    }
    private fun active(owner: NormalizationOwner, context: OperationContext, phase: SavePhase = SavePhase.NORMALIZING): SaveRecord {
        available(); ensureNoCanonicalReaders(owner); checkOperation(context)
        val record = readSave(owner.taskId) ?: fail()
        val transfer = record.transfer ?: fail()
        val artifact = record.artifact ?: fail()
        val auth = authority(owner)
        if (record.task.taskId != owner.taskId || record.commitId != owner.commitId ||
            artifact.artifactId != owner.artifactId || artifact.commitId != owner.commitId ||
            record.task.state != TaskState.RUNNING || record.request.method != TaskMethod.DIRECT ||
            record.phase != phase || record.operationId != context.operationId ||
            record.request.networkSourceMetadata == null || transfer.artifactId != owner.artifactId ||
            transfer.bytesOnDisk <= 0 || transfer.expectedTotalBytes?.let { it != transfer.bytesOnDisk } == true ||
            transfer.streamExpectation != null || transfer.recordingExpectation != null ||
            auth.owner != owner || auth.taskState != NormalizationTaskState.ACTIVE ||
            auth.parentState != NormalizationParentState.STAGING || auth.sourceTransferSha256 != sourceTransferFingerprint(transfer)) fail()
        return record
    }
    /** The returned function captures an immutable proposal, not a mutable current-operation field. */
    private fun exactCheck(token: NormalizationIoReservations.Token, record: SaveRecord,
        context: OperationContext? = null, activePhase: SavePhase? = null): () -> Unit = {
        token.requireLive()
        synchronized(ownerLock) {
            available(); reservations.requireExact(token)
            if (context != null) checkOperation(context)
            if (activePhase != null && context != null) {
                if (active(token.owner, context, activePhase) != record) fail()
            } else if (readSave(token.owner.taskId) != record) fail()
        }
    }
    private fun protocol(token: NormalizationIoReservations.Token, check: () -> Unit,
        ledgerPort: NormalizationLedger = ledger) =
        NormalizationWorkspaceProtocol(ledgerPort, files.forOperation(check, token), authority, ownerLock, check)

    /** Parent reserves RAW_PROBE (fresh) or exact RAW_COMPLETE/PREPARE (recovery) before IO. */
    fun <T> probeRaw(owner: NormalizationOwner, context: OperationContext,
        reservation: NormalizationIoReservations.Token, probe: (MovProbeInput) -> T): T {
        val (record, workspace) = synchronized(ownerLock) {
            val workspace = when (reservation.stage) {
                NormalizationIoReservations.Stage.RAW_PROBE -> {
                    reservations.requireExact(reservation, owner, key = null, stage = NormalizationIoReservations.Stage.RAW_PROBE)
                    if (ledger.lookup(owner) != NormalizationLookup.Absent) fail()
                    null
                }
                NormalizationIoReservations.Stage.PREPARE -> {
                    val row = (ledger.lookup(owner) as? NormalizationLookup.Present)?.row ?: fail()
                    reservations.requireExact(reservation, owner, row.key, NormalizationIoReservations.Stage.PREPARE)
                    if (row.lifecycle != NormalizationLifecycle.RAW_COMPLETE || row.disposition != NormalizationDisposition.CONTINUE) fail()
                    row
                }
                else -> fail()
            }
            rawProbes.keys.removeAll { !it.registered }; startedProbes.removeAll { !it.registered }
            handedOffSelections.removeAll { !it.registered }
            if (reservation in handedOffSelections || !startedProbes.add(reservation)) fail()
            ensureNoExistingHandles(owner); ensureNoCanonicalReaders(owner)
            val saved = readSave(owner.taskId) ?: fail()
            active(owner, context, saved.phase ?: fail()).also {
                if (it.hasDirectOutputProof() || (workspace != null &&
                    workspace.sourceTransferSha256 != sourceTransferFingerprint(it.transfer!!))) fail()
            } to workspace
        }
        val exactRecord = exactCheck(reservation, record, context, record.phase)
        val check: () -> Unit = {
            synchronized(ownerLock) {
                exactRecord()
                val expected = workspace?.let { NormalizationLookup.Present(it) } ?: NormalizationLookup.Absent
                if (ledger.lookup(owner) != expected) fail()
            }
        }
        val operation = files.forOperation(check, reservation)
        // A fresh selector's key selects only P; recovery uses the exact durable generation.
        val key = workspace?.key ?: NormalizationKey(owner, UUID.randomUUID().toString())
        val raw = operation.fingerprint(key, NormalizationRole.PRIMARY)
        if (raw.fingerprint.size != record.transfer!!.bytesOnDisk || (workspace != null && raw.fingerprint != workspace.raw)) fail()
        val descriptor = operation.open(key, NormalizationRole.PRIMARY, NormalizationDescriptorIo.Open.READ, raw.identity)
        var inputLive = true
        val input = object : MovProbeInput {
            override val length: Long get() = raw.fingerprint.size
            override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
                if (!inputLive || offset < 0 || offset > this.length || length < 0 || length.toLong() > this.length - offset ||
                    targetOffset < 0 || targetOffset > target.size || length > target.size - targetOffset) fail()
                descriptor.call {
                    var done = 0
                    while (done < length) {
                        check(); operation.validate(key, NormalizationRole.PRIMARY, raw.identity, descriptor)
                        val count = minOf(64 * 1024, length - done)
                        val actual = files.io.pread(descriptor.handle, target, targetOffset + done, count, offset + done)
                        if (actual <= 0 || actual > count) throw IOException("NORMALIZATION_READ_PROGRESS")
                        done += actual
                        check(); operation.validate(key, NormalizationRole.PRIMARY, raw.identity, descriptor)
                    }
                }
            }
        }
        val result = try {
            probe(input).also { check(); operation.validate(key, NormalizationRole.PRIMARY, raw.identity, descriptor) }
        } finally {
            inputLive = false
            descriptor.close()
            if (!descriptor.releaseConfirmed) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        check() // A cancellation while close was blocked cannot return a successful selection.
        val after = operation.fingerprint(key, NormalizationRole.PRIMARY)
        if (after.fingerprint != raw.fingerprint || after.identity.stat != raw.identity.stat) fail()
        operation.validate(key, NormalizationRole.PRIMARY, raw.identity)
        synchronized(ownerLock) {
            check()
            rawProbes.keys.removeAll { !it.registered }
            if (rawProbes.put(reservation, RawProbeWitness(raw, workspace, reservation.scope)) != null) fail()
        }
        return result
    }
    /** Closing a selector never revokes an owner to which its reservation has already moved. */
    fun closeRawSelection(reservation: NormalizationIoReservations.Token) = synchronized(ownerLock) {
        if (reservation.registry !== reservations) fail()
        rawProbes.remove(reservation); startedProbes.remove(reservation)
        handedOffSelections.removeAll { !it.registered }
        if (reservation.registered && reservation !in handedOffSelections &&
            reservation.stage in setOf(NormalizationIoReservations.Stage.RAW_PROBE, NormalizationIoReservations.Stage.PREPARE)) {
            reservations.revoke(reservation)
            reservations.retire(reservation)
        }
        Unit
    }

    fun prepare(owner: NormalizationOwner, context: OperationContext, limits: SaveLimits,
        reservation: NormalizationIoReservations.Token? = null): OwnedMovRemuxAttempt =
        prepareFresh(owner, context, limits, reservation, null) { record, row, raw, empty, maximum, token ->
            reservations.advance(token, NormalizationIoReservations.Stage.PREPARE, NormalizationIoReservations.Stage.REMUX)
            OwnedMovRemuxAttempt(this, files, row, context, raw, empty, maximum,
                record.request.networkSourceMetadata!!.sourceIdentity, row.sourceTransferSha256,
                Thread.currentThread(), token, record).also { sessions[owner.artifactId] = it }
        }

    /** Registered resource issuance only. No output completion or Direct runtime admission is issued. */
    fun prepareTranscode(owner: NormalizationOwner, context: OperationContext, limits: SaveLimits,
        reservation: NormalizationIoReservations.Token, scope: AndroidDirectExecutionBridge.Scope): OwnedDirectTranscodeAttempt {
        val attempt = prepareFresh(owner, context, limits, reservation, scope) { record, row, raw, empty, maximum, token ->
            reservations.advance(token, NormalizationIoReservations.Stage.PREPARE, NormalizationIoReservations.Stage.TRANSCODE)
            OwnedDirectTranscodeAttempt.createUnregistered(this, files, row, context, raw, empty,
                maximum, token, record, scope).also { transcodes[owner.artifactId] = it }
        }
        try { attempt.openAnchor(); attempt.requireRegistered(); return attempt }
        catch (failure: Throwable) {
            try { attempt.close() } catch (_: Throwable) { /* Sticky resource owner records uncertainty. */ }
            throw failure
        }
    }

    private fun <T> prepareFresh(owner: NormalizationOwner, context: OperationContext, limits: SaveLimits,
        reservation: NormalizationIoReservations.Token?, scope: AndroidDirectExecutionBridge.Scope?,
        issue: (SaveRecord, NormalizationWorkspace, NormalizationBoundFile, NormalizationFileIdentity,
            Long, NormalizationIoReservations.Token) -> T): T {
        val initial = synchronized(ownerLock) {
            if (scope != null) {
                requireTranscodeScope(scope); scope.requireLive(context)
                if (scope.context !== context || scope.execution.taskId != owner.taskId || reservation == null || reservation.scope !== scope) fail()
            }
            if (scope != null && rawProbes[reservation]?.scope !== scope) fail()
            val record = active(owner, context)
            if (record.hasDirectOutputProof()) fail()
            ensureNoExistingHandles(owner)
            if (sessions.containsKey(owner.artifactId) || transcodes.containsKey(owner.artifactId)) fail()
            val prior = when (val found = ledger.lookup(owner)) {
                NormalizationLookup.Absent -> null
                NormalizationLookup.Unreadable -> fail()
                is NormalizationLookup.Present -> found.row.also {
                    if (it.key.owner != owner || it.lifecycle != NormalizationLifecycle.RAW_COMPLETE ||
                        it.disposition != NormalizationDisposition.CONTINUE) fail()
                }
            }
            var selectedRaw: NormalizationBoundFile? = null
            val token = if (reservation == null) reservations.reserve(owner, NormalizationIoReservations.Stage.PREPARE, { checkOperation(context) })
                else reservation.also {
                    when (it.stage) {
                        NormalizationIoReservations.Stage.RAW_PROBE -> {
                            reservations.requireExact(it, owner, key = null, stage = NormalizationIoReservations.Stage.RAW_PROBE)
                            if (prior != null) fail()
                            val witness = rawProbes.remove(it) ?: fail()
                            if (witness.workspace != null) fail()
                            selectedRaw = witness.raw
                            startedProbes.remove(it)
                            reservations.advance(it, NormalizationIoReservations.Stage.RAW_PROBE, NormalizationIoReservations.Stage.PREPARE)
                        }
                        NormalizationIoReservations.Stage.PREPARE -> {
                            // Explicit recovery can bind an existing RAW_COMPLETE generation. It
                            // still cannot reconstruct any recovered WRITING writer permission.
                            val recovered = prior ?: fail()
                            reservations.requireExact(it, owner, recovered.key, NormalizationIoReservations.Stage.PREPARE)
                            val witness = rawProbes.remove(it) ?: fail()
                            if (witness.workspace != recovered) fail()
                            selectedRaw = witness.raw
                            startedProbes.remove(it)
                        }
                        else -> fail()
                    }
                }
            val key = prior?.key ?: NormalizationKey(owner, UUID.randomUUID().toString())
            reservations.bindGeneration(token, key)
            if (scope == null) sessions[owner.artifactId] = null else transcodes[owner.artifactId] = null
            if (reservation != null) handedOffSelections.add(token)
            Preparation(record, prior, token, key, selectedRaw)
        }
        val (record, prior, token, key, selectedRaw) = initial
        val check = exactCheck(token, record, context, SavePhase.NORMALIZING)
        val operation = files.forOperation(check, token)
        try {
            val transfer = record.transfer ?: fail()
            val budget = context.resourceBudget.maxBytes ?: throw SaveFailure(SaveError.INVALID_REQUEST)
            if (context.resourceBudget.maxConcurrentWork != 1 || budget <= 0) throw SaveFailure(SaveError.INVALID_REQUEST)
            val maximum = minOf(limits.maxArtifactBytes, DirectNormalizationLimits.MAX_BYTES)
            if (transfer.bytesOnDisk > maximum || transfer.bytesOnDisk > budget) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            val outputMaximum = minOf(maximum, budget - transfer.bytesOnDisk)
            if (outputMaximum < 32) throw SaveFailure(SaveError.BYTE_LIMIT_EXCEEDED)
            operation.requireReleased(key)
            val raw = operation.fingerprint(key, NormalizationRole.PRIMARY, sync = true)
            if (selectedRaw != null) {
                if (raw.fingerprint != selectedRaw.fingerprint || raw.identity.stat != selectedRaw.identity.stat) fail()
                operation.validate(key, NormalizationRole.PRIMARY, selectedRaw.identity)
            }
            val source = sourceTransferFingerprint(transfer)
            if (raw.fingerprint.size != transfer.bytesOnDisk || (prior != null && (prior.raw != raw.fingerprint || prior.sourceTransferSha256 != source))) fail()
            synchronized(ownerLock) {
                check()
                val expected = prior?.let { NormalizationLookup.Present(it) } ?: NormalizationLookup.Absent
                if (ledger.lookup(owner) != expected) fail()
            }
            var acknowledgedFreshTransition = false
            val freshLedger = object : NormalizationLedger by ledger {
                override fun compareAndSet(expected: NormalizationWorkspace, next: NormalizationWorkspace): Boolean = synchronized(ownerLock) {
                    check()
                    val fresh = expected.key == key && expected.lifecycle == NormalizationLifecycle.RAW_COMPLETE &&
                        next.lifecycle == NormalizationLifecycle.WRITING && expected.revision == (prior?.revision ?: 1L)
                    val accepted = ledger.compareAndSet(expected, next)
                    // Lost CAS return can be read back, but never issues a fresh writer grant.
                    if (fresh && accepted) acknowledgedFreshTransition = true
                    accepted
                }
            }
            val row = protocol(token, check, freshLedger).begin(key, raw.fingerprint, source)
            if (!acknowledgedFreshTransition || row.key != key || row.lifecycle != NormalizationLifecycle.WRITING ||
                row.revision != (prior?.revision ?: 1L) + 1L || row.raw != raw.fingerprint || row.sourceTransferSha256 != source) fail()
            operation.validate(key, NormalizationRole.PRIMARY, raw.identity)
            val empty = operation.capture(key, NormalizationRole.OUTPUT, empty = true)
            if (empty.stat.size != 0L) fail()
            return synchronized(ownerLock) {
                check()
                if ((ledger.lookup(owner) as? NormalizationLookup.Present)?.row != row ||
                    (if (scope == null) sessions[owner.artifactId] != null else transcodes[owner.artifactId] != null)) fail()
                if (scope != null) {
                    requireTranscodeScope(scope); scope.requireLive(context)
                    if (selectedRaw == null) fail()
                }
                issue(record, row, raw, empty, outputMaximum, token)
            }
        } catch (failure: Throwable) {
            synchronized(ownerLock) { if (reservations.retire(token)) {
                if (scope == null) sessions.remove(owner.artifactId) else transcodes.remove(owner.artifactId)
            } }
            throw failure
        }
    }
    private data class Preparation(val record: SaveRecord, val prior: NormalizationWorkspace?,
        val token: NormalizationIoReservations.Token, val key: NormalizationKey, val selectedRaw: NormalizationBoundFile?)

    /** One concrete attempt entry; no injected executor, spec, target, observations or result. */
    internal fun runOwnedTranscode(attempt: OwnedDirectTranscodeAttempt) {
        checkTranscodeSnapshot(attempt)
        attempt.runOwnedDirect()
    }
    internal fun transcodeEngine(attempt: OwnedDirectTranscodeAttempt): TransformerEngine {
        checkTranscodeSnapshot(attempt)
        return directEngine ?: throw TransformerFailure(TransformerError.NOT_ISSUED)
    }
    /** Full immutable revalidation belongs at the synchronous run boundaries, never callbacks. */
    internal fun checkTranscodeSnapshot(attempt: OwnedDirectTranscodeAttempt) = synchronized(ownerLock) {
        checkTranscode(attempt)
        requireTranscodeScope(attempt.scope)
        if (attempt.scope.context !== attempt.context || active(attempt.key.owner, attempt.context) != attempt.expectedRecord ||
            (ledger.lookup(attempt.key.owner) as? NormalizationLookup.Present)?.row != attempt.writingRow ||
            attempt.writingRow.lifecycle != NormalizationLifecycle.WRITING ||
            attempt.writingRow.sourceTransferSha256 != sourceTransferFingerprint(attempt.expectedRecord.transfer ?: fail())) fail()
    }
    /** Failure provenance only. It deliberately omits all live/stop checks and cannot grant work. */
    internal fun matchesTranscodeFailureBoundary(attempt: OwnedDirectTranscodeAttempt,
        binding: OwnedWebmInputBinding?, session: TransformerSession?): Boolean = synchronized(ownerLock) {
        transcodes[attempt.key.owner.artifactId] === attempt && attempt.reservation.scope === attempt.scope &&
            (binding == null || inputBindings[attempt] === binding) &&
            (session == null || inputSessions[attempt] === session || !inputSessions.containsKey(attempt))
    }

    /** Native callbacks use exact in-memory registries only; no Save/ledger read occurs here. */
    internal fun checkTranscode(attempt: OwnedDirectTranscodeAttempt) {
        checkStop(attempt.context)
        attempt.scope.requireLive(attempt.context)
        synchronized(ownerLock) {
            available()
            if (transcodes[attempt.key.owner.artifactId] !== attempt || attempt.reservation.scope !== attempt.scope) fail()
            reservations.requireExact(attempt.reservation, attempt.key.owner, attempt.key,
                NormalizationIoReservations.Stage.TRANSCODE)
        }
    }
    internal fun bindWebmInputOnce(attempt: OwnedDirectTranscodeAttempt): OwnedWebmInputBinding {
        attempt.requireRegistered()
        return synchronized(ownerLock) {
            checkTranscode(attempt)
            if (inputBindings.containsKey(attempt)) fail()
            OwnedWebmInputBinding.createUnregistered(attempt).also { inputBindings[attempt] = it }
        }
    }
    internal fun requireInputBinding(attempt: OwnedDirectTranscodeAttempt, binding: OwnedWebmInputBinding) {
        checkTranscode(attempt)
        synchronized(ownerLock) { if (inputBindings[attempt] !== binding) fail() }
    }
    internal fun bindInputSession(attempt: OwnedDirectTranscodeAttempt, binding: OwnedWebmInputBinding,
        session: TransformerSession) {
        requireInputBinding(attempt, binding)
        synchronized(ownerLock) {
            checkTranscode(attempt)
            if (inputBindings[attempt] !== binding || inputSessions.containsKey(attempt)) fail()
            inputSessions[attempt] = session
        }
    }
    internal fun requireInputSession(attempt: OwnedDirectTranscodeAttempt, binding: OwnedWebmInputBinding,
        session: TransformerSession) {
        requireInputBinding(attempt, binding)
        synchronized(ownerLock) { if (inputSessions[attempt] !== session) fail() }
    }
    internal fun inputSession(attempt: OwnedDirectTranscodeAttempt, binding: OwnedWebmInputBinding): TransformerSession {
        requireInputBinding(attempt, binding)
        return synchronized(ownerLock) { inputSessions[attempt] ?: fail() }
    }
    /** Existing identity maps only; no new owner or liveness authority for retained cleanup. */
    internal fun requireTranscodeSessionIdentity(attempt: OwnedDirectTranscodeAttempt,
        binding: OwnedWebmInputBinding, session: TransformerSession) = synchronized(ownerLock) {
        if (transcodes[attempt.key.owner.artifactId] !== attempt || inputBindings[attempt] !== binding ||
            inputSessions[attempt] !== session || attempt.reservation.scope !== attempt.scope) fail()
    }
    /** Exact release authority without liveness: an unregistered lookalike cannot revoke a real token. */
    internal fun requireTranscodeReleaseOwner(attempt: OwnedDirectTranscodeAttempt): Boolean = synchronized(ownerLock) {
        if (transcodes[attempt.key.owner.artifactId] === attempt) return@synchronized true
        if (attempt in retiredTranscodes) return@synchronized false
        fail()
    }
    /** Atomic against finish handoff. Physical drain and callbacks run after this monitor exits. */
    internal fun claimTranscodeClose(attempt: OwnedDirectTranscodeAttempt): Boolean = synchronized(ownerLock) {
        if (attempt in retiredTranscodes) return@synchronized false
        if (transcodes[attempt.key.owner.artifactId] !== attempt) fail()
        attempt.claimClose()
        reservations.revoke(attempt.reservation)
        true
    }
    /** Release-only: cancellation/Scope exit does not prevent draining exact owned obligations. */
    internal fun retireTranscode(attempt: OwnedDirectTranscodeAttempt) {
        synchronized(ownerLock) {
            if (transcodes[attempt.key.owner.artifactId] === attempt && attempt.isRetirable() &&
                !files.hasLeases(attempt.key) && reservations.retire(attempt.reservation)) {
                transcodes.remove(attempt.key.owner.artifactId)
                inputBindings.remove(attempt); inputSessions.remove(attempt)
                retiredTranscodes.add(attempt)
            }
        }
    }

    /** Same attempt/Scope/token, never a reconstructed result or renewed execution budget. */
    fun finishOwnedTranscode(attempt: OwnedDirectTranscodeAttempt): NormalizationConversionProofCompletion {
        // A foreign/replayed attempt cannot invalidate a handed-off completion or another owner.
        synchronized(ownerLock) {
            if (transcodes[attempt.key.owner.artifactId] !== attempt) fail()
            attempt.claimFinish() // Only the winner enters the failure-cleanup region below.
        }
        try {
            val proof = synchronized(ownerLock) {
                requireTranscodeFinishSnapshot(attempt, NormalizationIoReservations.Stage.TRANSCODE, attempt.writingRow)
                files.requireReleased(attempt.key)
                attempt.consumeForFinish().also {
                    reservations.advance(attempt.reservation, NormalizationIoReservations.Stage.TRANSCODE,
                        NormalizationIoReservations.Stage.FINISH)
                }
            }
            val proposed = attempt.writingRow.copy(revision = attempt.writingRow.revision + 1,
                lifecycle = NormalizationLifecycle.OUTPUT_READY,
                output = NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256), proofSha256 = proof.digest())
            // Only these two complete immutable ledger snapshots are possible during persist.
            val check: () -> Unit = {
                synchronized(ownerLock) {
                    val row = (ledger.lookup(attempt.key.owner) as? NormalizationLookup.Present)?.row ?: fail()
                    if (row != attempt.writingRow && row != proposed) fail()
                    requireTranscodeFinishSnapshot(attempt, NormalizationIoReservations.Stage.FINISH, row)
                    attempt.requireFinishing()
                }
            }
            val operation = files.forOperation(check, attempt.reservation)
            attempt.validateFinishResources(operation)
            val row = protocol(attempt.reservation, check).finishOutput(attempt.key, proof)
            if (row != proposed) fail()
            attempt.validateFinishResources(operation)
            return synchronized(ownerLock) {
                requireTranscodeFinishSnapshot(attempt, NormalizationIoReservations.Stage.FINISH, proposed)
                attempt.requireFinishing(); files.requireReleased(attempt.key)
                val completion = NormalizationConversionProofCompletion(this, proof, attempt.reservation,
                    attempt.expectedRecord, row, attempt.scope, attempt.context)
                reservations.advance(attempt.reservation, NormalizationIoReservations.Stage.FINISH,
                    NormalizationIoReservations.Stage.PROOF_HANDOFF)
                attempt.markHandedOff()
                conversionCompletions.add(completion)
                transcodes.remove(attempt.key.owner.artifactId)
                inputBindings.remove(attempt); inputSessions.remove(attempt); retiredTranscodes.add(attempt)
                completion
            }
        } catch (failure: Throwable) {
            // The shared file token may observe Scope revocation before the finish callback.
            // Recover only a derivative liveness reason; never replace an actual IO/native failure.
            val boundary = if (failure is NormalizationRecovery ||
                failure is SaveFailure && failure.code == SaveError.RECOVERY_REQUIRED) {
                try { checkOperation(attempt.context); failure } catch (stop: Throwable) { stop }
            } else failure
            val primary = attempt.rejectFinish(boundary)
            try { attempt.close() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
            throw primary
        }
    }
    private fun requireTranscodeFinishSnapshot(attempt: OwnedDirectTranscodeAttempt,
        stage: NormalizationIoReservations.Stage, row: NormalizationWorkspace) {
        available(); checkOperation(attempt.context)
        requireTranscodeScope(attempt.scope); attempt.scope.requireLive(attempt.context)
        if (transcodes[attempt.key.owner.artifactId] !== attempt || attempt in retiredTranscodes ||
            attempt.scope.context !== attempt.context || attempt.reservation.scope !== attempt.scope ||
            !attempt.matchesFinishRegistration(inputBindings[attempt], inputSessions[attempt])) fail()
        reservations.requireExact(attempt.reservation, attempt.key.owner, attempt.key, stage)
        if (reservations.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        val record = active(attempt.key.owner, attempt.context)
        val writing = attempt.writingRow
        if (record != attempt.expectedRecord || record.task.revision == Long.MAX_VALUE || record.hasDirectOutputProof() ||
            record.verification != null || record.receipt != null || writing.revision == Long.MAX_VALUE ||
            writing.lifecycle != NormalizationLifecycle.WRITING || writing.disposition != NormalizationDisposition.CONTINUE ||
            writing.output != null || writing.proofSha256 != null || writing.failure != null || writing.cleanup != null ||
            writing.raw.size != record.transfer?.bytesOnDisk ||
            writing.sourceTransferSha256 != sourceTransferFingerprint(record.transfer ?: fail()) ||
            (ledger.lookup(attempt.key.owner) as? NormalizationLookup.Present)?.row != row) fail()
    }
    internal fun requireConversionCompletion(completion: NormalizationConversionProofCompletion) = synchronized(ownerLock) {
        available()
        if (completion !in conversionCompletions || completion.scope.context !== completion.context ||
            completion.reservation.scope !== completion.scope) fail()
        checkOperation(completion.context)
        requireTranscodeScope(completion.scope); completion.scope.requireLive(completion.context)
        val row = completion.workspace
        val record = active(row.key.owner, completion.context)
        val proof = completion.proof
        reservations.requireExact(completion.reservation, row.key.owner, row.key, NormalizationIoReservations.Stage.PROOF_HANDOFF)
        if (reservations.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        files.requireReleased(row.key)
        if (record != completion.expectedRecord || record.task.revision == Long.MAX_VALUE || record.hasDirectOutputProof() ||
            record.verification != null || record.receipt != null ||
            (ledger.lookup(row.key.owner) as? NormalizationLookup.Present)?.row != row ||
            row.lifecycle != NormalizationLifecycle.OUTPUT_READY || row.disposition != NormalizationDisposition.CONTINUE ||
            row.cleanup != null || row.failure != null || row.key.normalizationId != proof.normalizationId ||
            row.raw != NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) ||
            row.output != NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) ||
            row.sourceTransferSha256 != proof.sourceTransferSha256 || row.proofSha256 != proof.digest() ||
            !proof.isCurrentPolicy() || !proof.matchesSource(record.task.taskId,
                record.request.networkSourceMetadata?.sourceIdentity ?: fail(), record.artifact ?: fail(), record.transfer ?: fail()) ||
            (record.request.networkSourceMetadata?.expectedAudio == true && proof.expectation.audio == null)) fail()
    }
    /** Release-only: successful CAS has deliberately replaced expectedRecord. No durable mutation. */
    internal fun closeConversionCompletion(completion: NormalizationConversionProofCompletion) = synchronized(ownerLock) {
        if (completion !in conversionCompletions) fail()
        if (reservations.retire(completion.reservation)) conversionCompletions.remove(completion)
    }

    internal fun check(attempt: OwnedMovRemuxAttempt) = synchronized(ownerLock) {
        if (sessions[attempt.key.owner.artifactId] !== attempt) fail()
        reservations.requireExact(attempt.reservation, attempt.key.owner, attempt.key, NormalizationIoReservations.Stage.REMUX)
        val record = active(attempt.key.owner, attempt.context)
        if (record != attempt.expectedRecord || record.request.networkSourceMetadata!!.sourceIdentity != attempt.sourceIdentity ||
            sourceTransferFingerprint(record.transfer!!) != attempt.sourceTransferSha256) fail()
        if ((ledger.lookup(attempt.key.owner) as? NormalizationLookup.Present)?.row != attempt.writingRow) fail()
    }
    internal fun retire(attempt: OwnedMovRemuxAttempt) = synchronized(ownerLock) {
        if (attempt.isRetirable() && sessions[attempt.key.owner.artifactId] === attempt && reservations.retire(attempt.reservation)) {
            sessions.remove(attempt.key.owner.artifactId)
        }
    }
    /** Legacy internal convenience; production uses finishOwned and the typed proof CAS. */
    fun finish(attempt: OwnedMovRemuxAttempt, evidence: MovRemuxEvidence, context: OperationContext): DirectNormalizationProof =
        finishOwned(attempt, evidence, context).use { it.proof }

    fun finishOwned(attempt: OwnedMovRemuxAttempt, evidence: MovRemuxEvidence,
        context: OperationContext): NormalizationProofCompletion {
        synchronized(ownerLock) { if (sessions[attempt.key.owner.artifactId] !== attempt) fail() }
        var handedOff = false
        try {
            synchronized(ownerLock) {
                if (context !== attempt.context && context != attempt.context) fail()
                check(attempt); attempt.consumeForFinish()
                reservations.advance(attempt.reservation, NormalizationIoReservations.Stage.REMUX, NormalizationIoReservations.Stage.FINISH)
            }
            val check = exactCheck(attempt.reservation, attempt.expectedRecord, context, SavePhase.NORMALIZING)
            val operation = files.forOperation(check, attempt.reservation)
            val raw = attempt.raw.fingerprint
            val output = attempt.finalizedIdentity ?: fail()
            if (evidence.rawSizeBytes != raw.size || evidence.rawSha256 != raw.sha256 ||
                evidence.outputSizeBytes != output.stat.size || evidence.outputSizeBytes !in 1..attempt.outputMaximum ||
                (attempt.expectedRecord.request.networkSourceMetadata?.expectedAudio == true && evidence.conservation.audio == null)) fail()
            requireFiniteAudioClock(evidence.conservation)
            operation.requireReleased(attempt.key)
            operation.validate(attempt.key, NormalizationRole.PRIMARY, attempt.raw.identity)
            operation.validate(attempt.key, NormalizationRole.OUTPUT, output)
            val proof = DirectNormalizationProof(attempt.key.normalizationId, attempt.key.owner.taskId,
                attempt.sourceIdentity, attempt.key.owner.artifactId, attempt.key.owner.commitId,
                raw.size, raw.sha256, attempt.sourceTransferSha256, evidence.outputSizeBytes, evidence.outputSha256, evidence.conservation,
                policyRevision = if (evidence.conservation.audio == null) 1 else 2)
            if (!proof.matchesTransfer(attempt.expectedRecord.transfer!!)) fail()
            val row = protocol(attempt.reservation, check).finishOutput(attempt.key, proof)
            return synchronized(ownerLock) {
                check()
                if ((ledger.lookup(attempt.key.owner) as? NormalizationLookup.Present)?.row != row) fail()
                reservations.advance(attempt.reservation, NormalizationIoReservations.Stage.FINISH, NormalizationIoReservations.Stage.PROOF_HANDOFF)
                NormalizationProofCompletion(this, proof, attempt.reservation, attempt.expectedRecord, row).also {
                    completions.add(it); sessions.remove(attempt.key.owner.artifactId); handedOff = true
                }
            }
        } catch (failure: Throwable) { attempt.rejectFinish(); throw failure }
        finally { attempt.finishEnded(); if (!handedOff) retire(attempt) }
    }
    internal fun requireCompletion(completion: NormalizationProofCompletion) = synchronized(ownerLock) {
        if (!completions.contains(completion)) fail()
        reservations.requireExact(completion.reservation, completion.workspace.key.owner, completion.workspace.key,
            NormalizationIoReservations.Stage.PROOF_HANDOFF)
        if ((ledger.lookup(completion.workspace.key.owner) as? NormalizationLookup.Present)?.row != completion.workspace ||
            completion.workspace.lifecycle != NormalizationLifecycle.OUTPUT_READY ||
            completion.workspace.proofSha256 != completion.proof.digest() || readSave(completion.workspace.key.owner.taskId) != completion.expectedRecord) fail()
    }
    internal fun closeCompletion(completion: NormalizationProofCompletion) = synchronized(ownerLock) {
        if (!completions.contains(completion)) fail()
        if (reservations.retire(completion.reservation)) completions.remove(completion)
    }
    private fun noSession(owner: NormalizationOwner) {
        available(); ensureNoCanonicalReaders(owner)
        if (sessions.containsKey(owner.artifactId) || transcodes.containsKey(owner.artifactId)) fail()
        ensureNoExistingHandles(owner)
    }
    private fun <T> reserved(owner: NormalizationOwner, key: NormalizationKey?, stage: NormalizationIoReservations.Stage,
        context: OperationContext? = null, phase: SavePhase? = null,
        authorityCheck: () -> Unit = {},
        block: (NormalizationIoReservations.Token, SaveRecord, () -> Unit) -> T): T {
        val (token, record) = synchronized(ownerLock) {
            noSession(owner)
            val record = if (context != null && phase != null) active(owner, context, phase) else readSave(owner.taskId) ?: fail()
            val token = reservations.reserve(owner, stage, { authorityCheck(); if (context != null) checkOperation(context) }, key)
            token to record
        }
        val check = exactCheck(token, record, context, phase)
        try { return block(token, record, check).also { check() } }
        finally { reservations.retire(token) }
    }
    fun inspect(owner: NormalizationOwner, context: OperationContext? = null,
        authorityCheck: () -> Unit = {}): NormalizationInspection {
        return try {
        val key = synchronized(ownerLock) {
            when (val lookup = ledger.lookup(owner)) {
                NormalizationLookup.Absent -> return NormalizationInspection.Absent
                NormalizationLookup.Unreadable -> return NormalizationInspection.Unreadable
                is NormalizationLookup.Present -> lookup.row.key
            }
        }
        reserved(owner, key, NormalizationIoReservations.Stage.INSPECT, context, authorityCheck = authorityCheck) { token, _, check -> protocol(token, check).inspect(owner) }
    } catch (_: Exception) { NormalizationInspection.Unreadable }
    }
    fun recordAdoptionIntent(key: NormalizationKey, proof: DirectNormalizationProof, context: OperationContext,
        authorityCheck: () -> Unit = {}): NormalizationWorkspace =
        reserved(key.owner, key, NormalizationIoReservations.Stage.ADOPT, context, SavePhase.ADOPTING, authorityCheck) { token, record, check ->
            if (record.directNormalization != proof || proof.normalizationId != key.normalizationId || !proof.isCurrentPolicy()) fail()
            protocol(token, check).recordAdoptionIntent(key, proof)
        }
    fun recordAdoptionIntent(key: NormalizationKey, proof: DirectConversionProof, context: OperationContext,
        authorityCheck: () -> Unit = {}): NormalizationWorkspace =
        reserved(key.owner, key, NormalizationIoReservations.Stage.ADOPT, context, SavePhase.ADOPTING, authorityCheck) { token, record, check ->
            if (record.directConversion != proof || record.directNormalization != null ||
                proof.normalizationId != key.normalizationId || !proof.isCurrentPolicy() ||
                (record.request.networkSourceMetadata?.expectedAudio == true && proof.expectation.audio == null) ||
                record.checkpoint != null ||
                record.verification != null || record.receipt != null) fail()
            protocol(token, check).recordAdoptionIntent(key, proof)
        }
    fun adopt(key: NormalizationKey, context: OperationContext, authorityCheck: () -> Unit = {}): NormalizationWorkspace =
        reserved(key.owner, key, NormalizationIoReservations.Stage.ADOPT, context, SavePhase.ADOPTING, authorityCheck) { token, record, check ->
            when (val output = record.selectedOutputDescriptor() ?: fail()) {
                is ArtifactOutputDescriptor.NormalizedDirect -> if (output.normalizationProof.normalizationId != key.normalizationId ||
                    !output.normalizationProof.isCurrentPolicy()) fail()
                is ArtifactOutputDescriptor.ConvertedDirect -> if (output.conversionProof.normalizationId != key.normalizationId ||
                    !output.conversionProof.isCurrentPolicy() ||
                    (record.request.networkSourceMetadata?.expectedAudio == true && output.conversionProof.expectation.audio == null) ||
                    record.checkpoint != null || record.verification != null || record.receipt != null) fail()
                is ArtifactOutputDescriptor.Transferred -> fail()
            }
            protocol(token, check).adopt(key)
        }
    fun cleanup(key: NormalizationKey, expectedRevision: Long, ioContext: OperationContext? = null,
        authorityCheck: () -> Unit = {}): NormalizationWorkspace =
        reserved(key.owner, key, NormalizationIoReservations.Stage.CLEANUP, ioContext, authorityCheck = authorityCheck) { token, _, check -> protocol(token, check).cleanup(key, expectedRevision) }
    /** Producer consistency only; byte conservation and native PCM are independently checked later. */
    private fun requireFiniteAudioClock(conservation: DirectRemuxConservation) {
        val audio = conservation.audio ?: return
        val clock = try { DirectMovClock.audio(conservation.audioSampleRate ?: fail(), audio.sampleCount) }
            catch (_: IllegalArgumentException) { fail() }
        if (conservation.audioChannelCount !in 1..2 || audio.firstPresentationTimeUs != 0L ||
            audio.endpoint != clock.endpoint || audio.lastSample.index != clock.sampleCount - 1 ||
            audio.lastSample.flags != 1 || audio.lastSample.presentationTimeUs != clock.presentationTimeUs(clock.sampleCount - 1)) fail()
    }
    private fun fail(): Nothing = throw NormalizationRecovery()
}
