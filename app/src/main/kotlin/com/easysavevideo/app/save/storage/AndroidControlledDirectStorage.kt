package com.easysavevideo.app.save.storage

import com.easysavevideo.app.media.transform.TransformerError
import com.easysavevideo.app.media.transform.TransformerFailure
import com.easysavevideo.app.media.transform.TransformerStopped
import com.easysavevideo.app.save.conversion.WebmCallerFailure
import com.easysavevideo.app.save.conversion.WebmProbeException
import com.easysavevideo.app.save.conversion.WebmProbeFailure
import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.app.save.normalization.MovRemuxEngine
import com.easysavevideo.app.save.normalization.MovRemuxEvidence
import com.easysavevideo.app.save.normalization.MovRemuxFiles
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The production Direct adapter. All byte work belongs to the existing storage/native owners. */
internal class AndroidControlledDirectStorage(
    private val readSave: (String) -> SaveRecord?,
    private val readOwned: (String) -> OwnedMedia?,
    private val ledger: NormalizationLedger,
    private val presence: (String, StagingArtifact?, String) -> DirectWorkspacePresence,
    private val ownerLock: Any,
    private val ensureNoHandles: (NormalizationOwner) -> Unit,
    /** Immutable first-process snapshot captured under the actual exclusive directory lease. */
    private val startupRaw: (NormalizationOwner) -> SaveRecord?,
    private val owner: NormalizationAttemptOwner,
    private val engine: MovRemuxEngine,
    private val bridge: AndroidDirectExecutionBridge,
    private val gate: DirectLocalProcessingGate,
    private val bindings: AdoptedVerificationBindings,
    private val committer: NormalizedCommitProtocol,
    private val closed: () -> Boolean,
    /** Host IO seam only; the production factory always uses this same engine's implementation. */
    private val remux: (MovRemuxFiles, OperationContext, SaveLimits) -> MovRemuxEvidence = engine::remux,
    private val terminalPage: (Long, Int) -> DirectTerminalCleanupPage = { _, _ -> DirectTerminalCleanupPage.Unreadable },
) : ControlledDirectStorage {
    init { require(owner.reservations.ownerLock === ownerLock) }
    private class RawHistory(val origin: SaveRecord) {
        val legacyEntered = AtomicBoolean()
        val uncertain = AtomicBoolean()
    }
    private enum class LegacyStage { QUEUED, ISSUED, ABANDONED, RETIRED, RELEASED, UNCERTAIN }
    private class LegacyCall(
        val artifact: StagingArtifact, val context: OperationContext,
        val history: RawHistory, val scope: AndroidDirectExecutionBridge.Scope,
    ) {
        val stage = AtomicReference(LegacyStage.QUEUED)
        fun unreleased(): Boolean = stage.get() in setOf(LegacyStage.ISSUED, LegacyStage.ABANDONED, LegacyStage.UNCERTAIN)
    }
    private val histories = ConcurrentHashMap<NormalizationOwner, RawHistory>()
    private val legacyCalls = CopyOnWriteArrayList<LegacyCall>()
    private val poisoned = AtomicBoolean()
    private val selections = IdentityHashMap<DirectRawSelection, Selection>()
    private val selectedOrdinary = java.util.WeakHashMap<AcceptedDirectAttempt, SaveRecord>()
    private val verifications = IdentityHashMap<DirectVerificationCompletion, Verification>()
    private val commits = IdentityHashMap<NormalizedCommitHandle, Commit>()

    private inner class Selection(
        override val kind: DirectRawKind,
        val expected: SaveRecord,
        val scope: AndroidDirectExecutionBridge.Scope,
        val token: NormalizationIoReservations.Token?,
        val history: RawHistory,
        val freshMov: Boolean,
    ) : DirectRawSelection {
        val ended = AtomicBoolean()
        var handedOff = false
        override fun close() {
            if (!ended.compareAndSet(false, true)) return
            try {
                synchronized(ownerLock) {
                    if (selections.remove(this) !== this) reject()
                    token?.let(owner::closeRawSelection)
                }
            } finally { scope.close() }
        }
    }
    internal data class VerificationCommitFact(
        val expectedRecord: SaveRecord,
        val evidence: VerificationEvidence,
    )
    private inner class Verification(
        val fact: VerificationCommitFact,
        val workspace: NormalizationWorkspace,
        val token: NormalizationIoReservations.Token,
        val scope: AndroidDirectExecutionBridge.Scope,
    ) : DirectVerificationCompletion {
        override val evidence: VerificationEvidence get() = fact.evidence
        val ended = AtomicBoolean()
        override fun close() {
            if (!ended.compareAndSet(false, true)) return
            try { synchronized(ownerLock) {
                if (verifications[this] === this && owner.reservations.retire(token)) verifications.remove(this)
            } } finally { scope.close() }
        }
    }
    private inner class Commit(
        val actual: NormalizedCommitHandle,
        val record: SaveRecord,
        val output: ArtifactOutputDescriptor,
        val scope: AndroidDirectExecutionBridge.Scope,
    ) : NormalizedCommitHandle {
        val ended = AtomicBoolean()
        override fun close() {
            if (!ended.compareAndSet(false, true)) return
            try { actual.close() }
            finally { synchronized(ownerLock) { commits.remove(this) }; scope.close() }
        }
    }

    /** Transfer callbacks retain only historical facts; they never issue a scope or open a file. */
    override fun terminalCleanupPage(after: Long, limit: Int): DirectTerminalCleanupPage = terminalPage(after, limit)

    override fun onTransferReleased(execution: AcceptedDirectAttempt) {
        val fact = bridge.completedTransfer(execution) ?: reject()
        if (!DirectRawRecoveryPolicy.complete(fact) || fact.phase != SavePhase.EXECUTING || fact.verification != null) reject()
        val key = key(fact)
        histories.compute(key) { _, old ->
            if (old != null && !DirectRawRecoveryPolicy.sameRawTuple(old.origin, fact)) {
                poisoned.set(true); reject()
            }
            old ?: RawHistory(fact)
        }
    }
    override fun inspectBeforeNetwork(taskId: String, artifact: StagingArtifact?, commitId: String): DirectWorkspacePresence =
        try { if (closed()) DirectWorkspacePresence.UNREADABLE else presence(taskId, artifact, commitId) }
        catch (_: Exception) { DirectWorkspacePresence.UNREADABLE }

    /** No SQL, native-gate monitor, directory lookup or upper-layer callback on this path. */
    override fun requiresProcessRecovery(): Boolean = poisoned.get() || engine.requiresProcessRecovery() ||
        owner.resourcesUnreleased() || histories.values.any { it.uncertain.get() } ||
        legacyCalls.any { it.unreleased() &&
            (it.stage.get() != LegacyStage.ISSUED || !runCatching { it.scope.requireLive(); true }.getOrDefault(false)) }

    override fun selectRaw(record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRawSelection = local {
        val scope = bridge.capture(execution, permit, context)
        var token: NormalizationIoReservations.Token? = null
        try {
            val (history, fresh) = synchronized(ownerLock) {
                requireRecord(record, scope)
                if (!DirectRawRecoveryPolicy.complete(record) || record.phase !in RAW_PHASES) reject()
                val catalog = requireCatalog(record)
                ensureNoHandles(key(record))
                val history = history(record, execution)
                if (history.uncertain.get() || legacyCalls.any { it.history === history && it.unreleased() }) reject()
                val fresh = DirectRawRecoveryPolicy.permitsFreshMov(history.origin, execution.resumedFrom,
                    record, history.legacyEntered.get())
                if (catalog.state != OwnedState.STAGING) {
                    val evidence = record.verification ?: reject()
                    val output = record.selectedOutputDescriptor() as? ArtifactOutputDescriptor.Transferred ?: reject()
                    if (record.phase != SavePhase.COMMITTING || catalog.state !in setOf(OwnedState.INTENT, OwnedState.INDEXED) ||
                        catalog.evidence != evidence ||
                        !evidence.isValidFor(record.artifact!!, output, record.request.networkSourceMetadata!!.expectedAudio) ||
                        ledger.lookup(key(record)) != NormalizationLookup.Absent ||
                        presence(record.task.taskId, record.artifact, record.commitId) != DirectWorkspacePresence.ABSENT ||
                        (!history.legacyEntered.get() && history.origin.phase != SavePhase.COMMITTING)) reject()
                    owner.reservations.requireLegacyAvailable(record.task.taskId, record.artifact!!.artifactId, record.commitId)
                    // Existing ordinary commit may have moved P to final. Preserve its original
                    // verificationFile/local verifier path; never call the MOV/raw owner on it.
                    val selection = Selection(DirectRawKind.ALREADY_MP4, record, scope, null, history, false)
                    selections[selection] = selection; selectedOrdinary[execution] = record
                    return@local selection
                }
                if (catalog.evidence != null) reject()
                when (val found = ledger.lookup(key(record))) {
                    NormalizationLookup.Absent -> {
                        if (presence(record.task.taskId, record.artifact, record.commitId) != DirectWorkspacePresence.ABSENT) reject()
                        token = owner.reservations.reserve(key(record), NormalizationIoReservations.Stage.RAW_PROBE,
                            { scope.requireLive() }, scope = scope)
                    }
                    is NormalizationLookup.Present -> {
                        val row = found.row
                        if (!fresh || row.lifecycle != NormalizationLifecycle.RAW_COMPLETE ||
                            row.disposition != NormalizationDisposition.CONTINUE || row.cleanup != null || row.failure != null ||
                            row.sourceTransferSha256 != sourceTransferFingerprint(record.transfer!!) || row.raw.size != record.transfer!!.bytesOnDisk) reject()
                        token = owner.reservations.reserve(key(record), NormalizationIoReservations.Stage.PREPARE,
                            { scope.requireLive() }, row.key, scope = scope)
                    }
                    NormalizationLookup.Unreadable -> reject()
                }
                history to fresh
            }
            val exact = token ?: reject()
            val kind = owner.probeRaw(key(record), context, exact) { input ->
                DirectRawFormatSelector.select(input, record.request.networkSourceMetadata!!.expectedAudio) { scope.requireLive(); owner.checkStop(context) }
            }
            return@local synchronized(ownerLock) {
                requireRecord(record, scope); owner.reservations.requireExact(exact)
                if (kind != DirectRawKind.ALREADY_MP4 && !fresh) reject()
                if (kind == DirectRawKind.ALREADY_MP4 && exact.key != null) reject()
                Selection(kind, record, scope, exact, history, fresh).also {
                    selections[it] = it
                    if (kind == DirectRawKind.ALREADY_MP4) selectedOrdinary[execution] = record
                }
            }
        } catch (failure: Throwable) {
            try { token?.let(owner::closeRawSelection) } finally { scope.close() }
            throw failure
        }
    }

    override fun normalize(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectProofCompletion = local {
        val selected = synchronized(ownerLock) {
            val selected = selections[selection] ?: reject()
            requireRecord(record, selected.scope)
            if (selected.ended.get() || selected.handedOff || selected.kind != DirectRawKind.STRICT_MOV || !selected.freshMov ||
                selected.scope.execution !== execution || selected.scope.permit !== permit || selected.scope.context !== context ||
                selected.history.legacyEntered.get() || selected.history.uncertain.get() ||
                selected.expected.task.revision == Long.MAX_VALUE ||
                record != selected.expected.copy(task = selected.expected.task.copy(revision = selected.expected.task.revision + 1),
                    phase = SavePhase.NORMALIZING, error = null)) reject()
            selected.handedOff = true
            selected
        }
        owner.prepare(key(record), context, limits, selected.token ?: reject()).use { attempt ->
            // remux closes its source, muxer output and independent output reader in its real finally.
            val evidence = remux(attempt, context, limits)
            selected.scope.requireLive()
            owner.finishOwned(attempt, evidence, context)
        }
    }

    override fun convert(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectConversionProofCompletion = local {
        try {
            val selected = synchronized(ownerLock) {
                val selected = selections[selection] ?: reject()
                requireRecord(record, selected.scope)
                if (selected.ended.get() || selected.handedOff || selected.kind != DirectRawKind.WEBM_CANDIDATE || !selected.freshMov ||
                    selected.scope.execution !== execution || selected.scope.permit !== permit || selected.scope.context !== context ||
                    selected.history.legacyEntered.get() || selected.history.uncertain.get() ||
                    !DirectRawRecoveryPolicy.sameRawTuple(selected.expected, record) ||
                    !DirectRawRecoveryPolicy.permitsFreshMov(selected.history.origin, execution.resumedFrom, record, false) ||
                    selected.expected.task.revision == Long.MAX_VALUE ||
                    record != selected.expected.copy(task = selected.expected.task.copy(revision = selected.expected.task.revision + 1),
                        phase = SavePhase.NORMALIZING, error = null)) reject()
                selected.handedOff = true
                selected
            }
            owner.prepareTranscode(key(record), context, limits, selected.token ?: reject(), selected.scope).use { attempt ->
                // This is the real one-shot producer. No injected outcome, second probe, lane or budget.
                owner.runOwnedTranscode(attempt)
                selected.scope.requireLive()
                owner.finishOwnedTranscode(attempt)
            }
        } catch (failure: Exception) {
            throw directConversionFailure(failure)
        }
    }

    override fun inspectRecovery(record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRecoveryAction = local {
        bridge.capture(execution, permit, context).use { scope ->
            val (row, catalog) = synchronized(ownerLock) {
                requireRecord(record, scope); ensureNoHandles(key(record))
                val row = (ledger.lookup(key(record)) as? NormalizationLookup.Present)?.row ?: reject()
                requireRawWorkspace(record, row)
                row to requireCatalog(record)
            }
            if (row.disposition != NormalizationDisposition.CONTINUE || row.cleanup != null || row.failure != null)
                return@use DirectRecoveryAction.REVIEW
            if (row.lifecycle == NormalizationLifecycle.WRITING || row.lifecycle in TERMINAL_WORKSPACES)
                return@use DirectRecoveryAction.REVIEW
            if (row.lifecycle == NormalizationLifecycle.RAW_COMPLETE) {
                if (record.hasDirectOutputProof() || record.verification != null || catalog.state != OwnedState.STAGING)
                    return@use DirectRecoveryAction.REVIEW
                synchronized(ownerLock) {
                    requireRecord(record, scope)
                    val history = history(record, execution)
                    if (!DirectRawRecoveryPolicy.permitsFreshMov(history.origin, execution.resumedFrom, record,
                            history.legacyEntered.get()) || history.uncertain.get() ||
                        legacyCalls.any { it.history === history && it.unreleased() }) reject()
                }
                val observed = owner.inspect(key(record), context) { scope.requireLive() } as? NormalizationInspection.Present
                    ?: return@use DirectRecoveryAction.REVIEW
                return@use if (observed.row == row && !observed.discardOnly && observed.layout == NormalizationLayout.RAW_ONLY)
                    DirectRecoveryAction.RAW_COMPLETE else DirectRecoveryAction.REVIEW
            }
            if (!proofMatches(record, row)) return@use DirectRecoveryAction.REVIEW
            val output = record.selectedOutputDescriptor() ?: return@use DirectRecoveryAction.REVIEW
            if (output is ArtifactOutputDescriptor.Transferred) return@use DirectRecoveryAction.REVIEW
            // Narrow active converted admission; historical data remains readable elsewhere.
            if (output is ArtifactOutputDescriptor.ConvertedDirect && (record.receipt != null ||
                when (row.lifecycle) {
                    NormalizationLifecycle.OUTPUT_READY -> record.phase !in setOf(SavePhase.NORMALIZING, SavePhase.ADOPTING) || record.verification != null
                    NormalizationLifecycle.ADOPT_INTENT -> record.phase != SavePhase.ADOPTING || record.verification != null
                    NormalizationLifecycle.ADOPTED -> if (record.verification == null) record.phase !in setOf(SavePhase.ADOPTING, SavePhase.VERIFYING)
                        else record.phase != SavePhase.COMMITTING
                    else -> true
                })) return@use DirectRecoveryAction.REVIEW
            if (row.lifecycle == NormalizationLifecycle.ADOPTED && catalog.state in setOf(OwnedState.INTENT, OwnedState.INDEXED)) {
                // This is a request to perform typed commit reconciliation, not a claim that a
                // missing P proves a valid final file. begin+inspect must hash the exact final role.
                return@use if (record.verification != null && catalog.evidence == record.verification &&
                    record.verification!!.isValidFor(record.artifact!!, output, record.request.networkSourceMetadata!!.expectedAudio))
                    DirectRecoveryAction.COMMIT else DirectRecoveryAction.REVIEW
            }
            if (catalog.state != OwnedState.STAGING || catalog.evidence != null) return@use DirectRecoveryAction.REVIEW
            val observed = owner.inspect(key(record), context) { scope.requireLive() } as? NormalizationInspection.Present
                ?: return@use DirectRecoveryAction.REVIEW
            if (observed.row != row || observed.discardOnly) return@use DirectRecoveryAction.REVIEW
            when (row.lifecycle) {
                NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT ->
                    if (record.verification == null && observed.layout in setOf(NormalizationLayout.READY, NormalizationLayout.RAW_MOVED, NormalizationLayout.ADOPTED))
                        DirectRecoveryAction.ADOPT else DirectRecoveryAction.REVIEW
                NormalizationLifecycle.ADOPTED -> if (observed.layout != NormalizationLayout.ADOPTED) DirectRecoveryAction.REVIEW
                    else if (record.verification == null) DirectRecoveryAction.VERIFY
                    else if (record.verification!!.isValidFor(record.artifact!!, output, record.request.networkSourceMetadata!!.expectedAudio))
                        DirectRecoveryAction.COMMIT else DirectRecoveryAction.REVIEW
                else -> DirectRecoveryAction.REVIEW
            }.also { synchronized(ownerLock) { requireRecord(record, scope); if (ledger.lookup(key(record)) != NormalizationLookup.Present(row)) reject() } }
        }
    }

    override fun adopt(record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext) = local {
        bridge.capture(execution, permit, context).use { scope ->
            val row = synchronized(ownerLock) {
                requireRecord(record, scope)
                val row = (ledger.lookup(key(record)) as? NormalizationLookup.Present)?.row ?: reject()
                if (record.phase != SavePhase.ADOPTING || !proofMatches(record, row)) reject()
                row
            }
            if (row.lifecycle == NormalizationLifecycle.OUTPUT_READY) {
                when (val output = record.selectedOutputDescriptor()) {
                    is ArtifactOutputDescriptor.NormalizedDirect -> owner.recordAdoptionIntent(row.key, output.normalizationProof, context) { scope.requireLive() }
                    is ArtifactOutputDescriptor.ConvertedDirect -> owner.recordAdoptionIntent(row.key, output.conversionProof, context) { scope.requireLive() }
                    else -> reject()
                }
            } else if (row.lifecycle != NormalizationLifecycle.ADOPT_INTENT) reject()
            val adopted = owner.adopt(row.key, context) { scope.requireLive() }
            if (adopted.lifecycle != NormalizationLifecycle.ADOPTED || !proofMatches(record, adopted)) reject()
        }
    }

    override fun verifyTransferred(record: SaveRecord, output: ArtifactOutputDescriptor.Transferred,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): VerificationEvidence = local {
        val scope = bridge.capture(execution, permit, context)
        var call: LegacyCall? = null
        try {
            val registered = synchronized(ownerLock) {
                requireRecord(record, scope)
                val selected = selectedOrdinary.remove(execution) ?: reject()
                if (!DirectRawRecoveryPolicy.sameRawTuple(selected, record) || record.phase != SavePhase.VERIFYING ||
                    record.hasDirectOutputProof() || record.selectedOutputDescriptor() != output ||
                    presence(record.task.taskId, record.artifact, record.commitId) != DirectWorkspacePresence.ABSENT) reject()
                owner.reservations.requireLegacyAvailable(record.task.taskId, record.artifact!!.artifactId, record.commitId)
                ensureNoHandles(key(record))
                val history = history(record, execution)
                if (history.uncertain.get() || legacyCalls.any { it.history === history && it.unreleased() }) reject()
                history.legacyEntered.set(true) // Never return to fresh MOV, even if queued work is cancelled.
                LegacyCall(record.artifact!!, context, history, scope).also { legacyCalls += it; call = it }
            }
            val result = verifier.verify(record.artifact!!, output, context)
            scope.requireLive()
            if (registered.stage.get() != LegacyStage.RELEASED || registered.history.uncertain.get()) reject()
            synchronized(ownerLock) { requireRecord(record, scope) }
            result
        } finally {
            call?.let(::abandonLegacy)
            scope.close()
        }
    }

    private fun abandonLegacy(call: LegacyCall) {
        while (true) when (val stage = call.stage.get()) {
            LegacyStage.QUEUED -> if (call.stage.compareAndSet(stage, LegacyStage.RETIRED)) {
                legacyCalls.remove(call); return
            }
            LegacyStage.ISSUED -> if (call.stage.compareAndSet(stage, LegacyStage.ABANDONED)) return
            LegacyStage.RELEASED, LegacyStage.RETIRED -> { legacyCalls.remove(call); return }
            LegacyStage.ABANDONED, LegacyStage.UNCERTAIN -> return
        }
    }

    /** Actual native worker hook. Unregistered Stream/Record invocations remain unaffected. */
    fun onLegacyReadIssued(artifact: StagingArtifact, context: OperationContext): (Boolean) -> Unit {
        val matching = legacyCalls.filter { it.artifact === artifact && it.context === context }
        if (matching.isEmpty()) {
            // Retiring a never-started invocation must not turn its later worker into the
            // unrelated Stream/Record no-op path. Historical Direct identity grants no IO.
            if (histories.keys.any { it.artifactId == artifact.artifactId && it.commitId == artifact.commitId }) reject()
            return { _ -> }
        }
        if (matching.size != 1) { poisoned.set(true); reject() }
        val call = matching.single()
        call.scope.requireLive(context)
        // Atomic with caller abandonment: only the winning worker may issue a legacy File.
        if (!call.stage.compareAndSet(LegacyStage.QUEUED, LegacyStage.ISSUED)) reject()
        try {
            call.scope.requireLive(context)
            if (call.stage.get() != LegacyStage.ISSUED) reject()
        } catch (failure: Throwable) {
            // Rejected before File/RAF dispatch, so there is no outstanding IO obligation.
            call.stage.set(LegacyStage.RELEASED); legacyCalls.remove(call); throw failure
        }
        // The release callback performs only memory writes and cannot hide the worker's failure.
        return { confirmed ->
            if (!confirmed) {
                call.stage.set(LegacyStage.UNCERTAIN); call.history.uncertain.set(true); poisoned.set(true)
            } else {
                while (true) {
                    val prior = call.stage.get()
                    if (prior !in setOf(LegacyStage.ISSUED, LegacyStage.ABANDONED)) break
                    if (call.stage.compareAndSet(prior, LegacyStage.RELEASED)) {
                        if (prior == LegacyStage.ABANDONED) legacyCalls.remove(call)
                        break
                    }
                }
            }
        }
    }

    override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion =
        verifyOwned(record, output, execution, permit, context, verifier)

    override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion =
        verifyOwned(record, output, execution, permit, context, verifier)

    private fun verifyOwned(record: SaveRecord, output: ArtifactOutputDescriptor,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion = local {
        val scope = bridge.capture(execution, permit, context)
        var retained = false
        try {
            val (expectedWorkspace, expectedCatalog) = synchronized(ownerLock) {
                requireRecord(record, scope)
                if (record.phase != SavePhase.VERIFYING || record.selectedOutputDescriptor() != output ||
                    record.verification != null || record.receipt != null) reject()
                val row = (ledger.lookup(key(record)) as? NormalizationLookup.Present)?.row ?: reject()
                val catalog = requireCatalog(record)
                if (row.lifecycle != NormalizationLifecycle.ADOPTED || !proofMatches(record, row) ||
                    catalog.state != OwnedState.STAGING || catalog.evidence != null) reject()
                row to catalog
            }
            val verify: (AdoptedVerificationBindings.Binding) -> VerificationEvidence = { binding ->
                val evidence = verifier.verify(record.artifact!!, output, context)
                // The canonical owner has retired the registry entry. This checks its private
                // terminal release witness, never the revoked binding's former liveness.
                binding.requireConsumedAndReleased()
                evidence
            }
            val evidence = when (output) {
                is ArtifactOutputDescriptor.NormalizedDirect -> bindings.withExecutionBinding(scope, record.artifact!!, output, context, record.task.revision, verify)
                is ArtifactOutputDescriptor.ConvertedDirect -> bindings.withExecutionBinding(scope, record.artifact!!, output, context, record.task.revision, verify)
                is ArtifactOutputDescriptor.Transferred -> reject()
            }
            scope.requireLive()
            if (verifier.requiresProcessRecovery()) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            if (!evidence.isValidFor(record.artifact!!, output, record.request.networkSourceMetadata!!.expectedAudio))
                throw SaveFailure(SaveError.INVALID_MEDIA)
            val detached = evidence.copy(decodedPositions = java.util.Collections.unmodifiableSet(LinkedHashSet(evidence.decodedPositions)))
            synchronized(ownerLock) {
                requireRecord(record, scope); ensureNoHandles(key(record))
                val row = (ledger.lookup(key(record)) as? NormalizationLookup.Present)?.row ?: reject()
                if (row != expectedWorkspace || requireCatalog(record) != expectedCatalog ||
                    row.lifecycle != NormalizationLifecycle.ADOPTED || !proofMatches(record, row)) reject()
                val token = owner.reservations.reserve(key(record), NormalizationIoReservations.Stage.VERIFY,
                    { scope.requireLive() }, row.key, scope = scope)
                Verification(VerificationCommitFact(record, detached), row, token, scope).also {
                    verifications[it] = it; retained = true
                }
            }
        } finally { if (!retained) scope.close() }
    }

    /** Parent invokes this inside its same-storage typed Save transaction; constructors grant nothing. */
    fun verificationCompletion(completion: DirectVerificationCompletion): VerificationCommitFact? = synchronized(ownerLock) {
        val entry = verifications[completion] ?: return@synchronized null
        if (entry.ended.get()) return@synchronized null
        entry.scope.requireLive()
        owner.reservations.requireExact(entry.token, key(entry.fact.expectedRecord), entry.workspace.key,
            NormalizationIoReservations.Stage.VERIFY)
        requireRecord(entry.fact.expectedRecord, entry.scope)
        if (ledger.lookup(entry.workspace.key.owner) != NormalizationLookup.Present(entry.workspace) ||
            !proofMatches(entry.fact.expectedRecord, entry.workspace)) reject()
        entry.fact
    }

    override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle = beginOwnedCommit(record, output, execution, permit, context)
    override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord,
        output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle =
        inspectOwnedCommit(handle, record, output, execution, permit, context)

    override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle = beginOwnedCommit(record, output, execution, permit, context)
    override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord,
        output: ArtifactOutputDescriptor.ConvertedDirect, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle =
        inspectOwnedCommit(handle, record, output, execution, permit, context)

    private fun beginOwnedCommit(record: SaveRecord, output: ArtifactOutputDescriptor,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle = local {
        val scope = bridge.capture(execution, permit, context)
        try {
            synchronized(ownerLock) { requireRecord(record, scope) }
            val actual = when (output) {
                is ArtifactOutputDescriptor.NormalizedDirect -> committer.begin(record, output, execution, permit, context)
                is ArtifactOutputDescriptor.ConvertedDirect -> committer.begin(record, output, execution, permit, context)
                is ArtifactOutputDescriptor.Transferred -> reject()
            }
            try {
                scope.requireLive()
                synchronized(ownerLock) {
                    Commit(actual, record, output, scope).also { commits[it] = it }
                }
            } catch (failure: Throwable) { actual.close(); throw failure }
        } catch (failure: Throwable) { scope.close(); throw failure }
    }
    private fun inspectOwnedCommit(handle: NormalizedCommitHandle, record: SaveRecord,
        output: ArtifactOutputDescriptor, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle = local {
        val entry = synchronized(ownerLock) {
            val entry = commits[handle] ?: reject()
            if (entry.ended.get() || entry.record != record || entry.output !== output || entry.scope.execution !== execution ||
                entry.scope.permit !== permit || entry.scope.context !== context) reject()
            requireRecord(record, entry.scope); entry
        }
        when (output) {
            is ArtifactOutputDescriptor.NormalizedDirect -> committer.inspect(entry.actual, record, output, execution, permit, context)
            is ArtifactOutputDescriptor.ConvertedDirect -> committer.inspect(entry.actual, record, output, execution, permit, context)
            is ArtifactOutputDescriptor.Transferred -> reject()
        }
    }
    override fun cleanupTerminal(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit,
        invocationContext: OperationContext, ioContext: OperationContext) = local {
        gate.assertIssuedHere(permit, invocationContext)
        committer.cleanup(record, permit, invocationContext, ioContext)
        Unit
    }

    private fun history(record: SaveRecord, execution: AcceptedDirectAttempt): RawHistory {
        bridge.completedTransfer(execution)?.let { onTransferReleased(execution) }
        val key = key(record)
        val history = histories[key] ?: run {
            val prior = startupRaw(key) ?: reject()
            // A callback captures the true startup lease fact; a new Runtime/storage object or
            // priorRuntimeInProcess=false never substitutes for it.
            if (!DirectRawRecoveryPolicy.sameRawTuple(prior, record) || prior.phase !in RAW_PHASES ||
                execution.resumedFrom?.let { !DirectRawRecoveryPolicy.sameRawTuple(prior, it) || it.phase != prior.phase } != false) reject()
            histories.putIfAbsent(key, RawHistory(prior)) ?: histories[key] ?: reject()
        }
        if (!DirectRawRecoveryPolicy.sameRawTuple(history.origin, record)) reject()
        return history
    }
    private fun requireRecord(record: SaveRecord, scope: AndroidDirectExecutionBridge.Scope) {
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        scope.requireLive()
        val context = scope.context
        if (record.task.state != TaskState.RUNNING || record.request.method != TaskMethod.DIRECT ||
            record.task.taskId != scope.execution.taskId || record.operationId != context.operationId ||
            record.attempt != context.attempt || record.retryBudget != context.retryBudget ||
            context.deadlineEpochMillis > record.deadlineEpochMillis || readSave(record.task.taskId) != record) reject()
    }
    private fun requireCatalog(record: SaveRecord): OwnedMedia {
        val catalog = readOwned(record.commitId) ?: reject()
        if (catalog.taskId != record.task.taskId || catalog.artifact != record.artifact || catalog.state == OwnedState.DISCARDED) reject()
        return catalog
    }
    private fun requireRawWorkspace(record: SaveRecord, row: NormalizationWorkspace) {
        val raw = record.transfer ?: reject()
        if (row.key.owner != key(record) || row.raw.size != raw.bytesOnDisk ||
            row.sourceTransferSha256 != sourceTransferFingerprint(raw)) reject()
    }
    private fun conversionProofMatches(record: SaveRecord, row: NormalizationWorkspace): Boolean {
        val proof = record.directConversion ?: return false
        val artifact = record.artifact ?: return false
        val transfer = record.transfer ?: return false
        val source = record.request.networkSourceMetadata ?: return false
        requireRawWorkspace(record, row)
        return record.directNormalization == null && proof.isCurrentPolicy() && record.checkpoint == null &&
            proof.normalizationId == row.key.normalizationId && proof.digest() == row.proofSha256 &&
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) == row.raw &&
            NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) == row.output &&
            proof.matchesSource(record.task.taskId, source.sourceIdentity, artifact, transfer) &&
            (!source.expectedAudio || proof.expectation.audio != null)
    }
    private fun proofMatches(record: SaveRecord, row: NormalizationWorkspace): Boolean {
        if (record.directConversion != null) return conversionProofMatches(record, row)
        val proof = record.directNormalization ?: return false
        requireRawWorkspace(record, row)
        return proof.normalizationId == row.key.normalizationId && proof.digest() == row.proofSha256 &&
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) == row.raw &&
            NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) == row.output &&
            proof.isCurrentPolicy() && record.checkpoint == null &&
            (record.request.networkSourceMetadata?.expectedAudio != true || proof.conservation.audio != null) &&
            proof.matchesTransfer(record.transfer!!)
    }
    private fun key(record: SaveRecord): NormalizationOwner = record.artifact?.let {
        NormalizationOwner(record.task.taskId, it.artifactId, record.commitId)
    } ?: reject()
    private inline fun <T> local(block: () -> T): T = try { block() }
        catch (failure: SaveFailure) { throw completeRawFailure(failure) }
        catch (_: NormalizationRecovery) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
        catch (_: IOException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
        catch (_: IllegalArgumentException) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
    private fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    private companion object {
        val RAW_PHASES = setOf(SavePhase.EXECUTING, SavePhase.NORMALIZING, SavePhase.VERIFYING, SavePhase.COMMITTING)
        val TERMINAL_WORKSPACES = setOf(NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED,
            NormalizationLifecycle.RECOVERY_REQUIRED)
    }
}

/** Product boundary only; the attempt keeps the independent sticky native/FD release obligation. */
internal fun directConversionFailure(failure: Exception): SaveFailure {
    if (failure is SaveFailure) return completeRawFailure(failure)
    if (failure is WebmCallerFailure) return directConversionFailure(failure.original)
    if (failure is TransformerStopped) return SaveFailure(when (failure.reason) {
        com.easysavevideo.core.model.OperationStopReason.CANCELLED -> SaveError.CANCELLED
        com.easysavevideo.core.model.OperationStopReason.DEADLINE_EXCEEDED -> SaveError.DEADLINE_EXCEEDED
        com.easysavevideo.core.model.OperationStopReason.RETRY_EXHAUSTED -> SaveError.RETRY_EXHAUSTED
    })
    if (failure is WebmProbeException) return SaveFailure(when (failure.failure) {
        WebmProbeFailure.CANCELLED -> SaveError.CANCELLED
        WebmProbeFailure.DEADLINE -> SaveError.DEADLINE_EXCEEDED
        WebmProbeFailure.PROTECTED_MEDIA -> SaveError.PROTECTED_MEDIA
        WebmProbeFailure.IDENTITY, WebmProbeFailure.FOREIGN_INPUT -> SaveError.RESOURCE_CHANGED
        WebmProbeFailure.IO_BUDGET, WebmProbeFailure.SAMPLE_LIMIT, WebmProbeFailure.ELEMENT_LIMIT -> SaveError.BYTE_LIMIT_EXCEEDED
        else -> SaveError.UNSUPPORTED_MEDIA
    })
    if (failure is TransformerFailure) return SaveFailure(when (failure.code) {
        TransformerError.CAPACITY -> SaveError.BUSY
        TransformerError.SOURCE_LIMIT -> SaveError.BYTE_LIMIT_EXCEEDED
        TransformerError.UNSUPPORTED_PROFILE -> SaveError.UNSUPPORTED_MEDIA
        TransformerError.INSUFFICIENT_SPACE -> SaveError.STORAGE_FULL
        TransformerError.NOT_ISSUED, TransformerError.INVALID_STATE, TransformerError.INVALID_REQUEST -> SaveError.RECOVERY_REQUIRED
        TransformerError.EXECUTION_FAILED -> SaveError.ENCODER_FAILED
    })
    if (failure is IOException || failure is SecurityException || failure is NormalizationRecovery) {
        // The actual acquisition/producer boundary has already selected its primary. A later
        // cleanup cancellation must never reopen that selection in this product-only mapper.
        return SaveFailure(if (failure is NormalizationRecovery) SaveError.RECOVERY_REQUIRED else SaveError.STORAGE_FAILURE)
    }
    if (failure is InterruptedException) return SaveFailure(SaveError.CANCELLED)
    return SaveFailure(SaveError.ENCODER_FAILED)
}
