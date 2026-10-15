package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*
import java.util.IdentityHashMap

/** One complete read from the same Save/catalog/normalization database. No filesystem IO. */
internal data class NormalizedCommitState(
    val record: SaveRecord,
    val workspace: NormalizationWorkspace,
    val owned: OwnedMedia,
    val indexed: MediaCommitReceipt? = null,
    val indexVisible: Boolean = false,
    val libraryLinked: Boolean = false,
)

/**
 * Every write is one transaction and compares the ENTIRE expected snapshot before changing it.
 * publish also performs Library validation/publication, destination binding and Save SUCCEEDED
 * in that transaction. No method may call generic commit, rebuild a terminal index, or do file IO.
 */
internal interface NormalizedCommitJournal {
    fun read(owner: NormalizationOwner): NormalizedCommitState?
    fun writeIntent(expected: NormalizedCommitState, intent: OwnedMedia): Boolean
    fun writeIndex(expected: NormalizedCommitState): Boolean
    fun publish(expected: NormalizedCommitState, succeeded: SaveRecord): Boolean
    /** Discard atomically revokes only the exact invisible index and marks the exact owned row DISCARDED. */
    fun planCleanup(expected: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean): Boolean
    fun finishCleanup(expected: NormalizedCommitState, cleaned: NormalizationWorkspace): Boolean
}

internal data class NormalizedFileObservation(
    val fingerprint: NormalizationFingerprint,
    val identity: NormalizationCleanupIdentity,
)
internal data class NormalizedCommitLayout(
    val primary: NormalizedFileObservation?, val raw: NormalizedFileObservation?,
    val output: NormalizedFileObservation?, val final: NormalizedFileObservation?,
    val workspaceExists: Boolean, val generationExists: Boolean,
    val parents: List<NormalizationDescriptorIo.Stat> = emptyList(),
) {
    init {
        val ids = listOfNotNull(primary, raw, output, final).map { it.identity }
        require(ids.distinct().size == ids.size) // Same-content hard links never authorize two roles.
    }
}
internal enum class NormalizedCommitRole { PRIMARY, RAW, OUTPUT, FINAL }
internal enum class NormalizedCommitDirectory { STAGING, MEDIA, WORKSPACE, GENERATION }

/** Paths/FDs never escape this owner port. inspect always independently hashes all selected roles. */
internal interface NormalizedCommitFiles {
    fun inspect(key: NormalizationKey, mediaId: String, includeFinal: Boolean,
        token: NormalizationIoReservations.Token): NormalizedCommitLayout
    fun syncCanonical(key: NormalizationKey, mediaId: String, final: Boolean,
        expected: NormalizedFileObservation, token: NormalizationIoReservations.Token)
    fun promote(key: NormalizationKey, mediaId: String, expected: NormalizedFileObservation,
        token: NormalizationIoReservations.Token)
    fun delete(key: NormalizationKey, mediaId: String, role: NormalizedCommitRole,
        expected: NormalizedFileObservation, token: NormalizationIoReservations.Token)
    fun sync(key: NormalizationKey, directory: NormalizedCommitDirectory, token: NormalizationIoReservations.Token)
    fun removeEmpty(key: NormalizationKey, directory: NormalizedCommitDirectory, token: NormalizationIoReservations.Token)
}

/**
 * Typed owner of the existing private commit/catalog protocol. All blocking IO is outside ownerLock.
 * AcceptedDirectAttempt and the actual batch bridge are required in addition to a lexical permit.
 * Handles are identity-registered results, not constructors granting authority from Save data.
 */
internal class NormalizedCommitProtocol(
    private val journal: NormalizedCommitJournal,
    private val files: NormalizedCommitFiles,
    private val reservations: NormalizationIoReservations,
    private val ownerLock: Any,
    private val gate: DirectLocalProcessingGate,
    private val closed: () -> Boolean,
    private val ensureNoHandles: (NormalizationOwner) -> Unit,
    private val checkOperation: (OperationContext) -> Unit,
    private val authorize: (AcceptedDirectAttempt, SaveRecord, DirectLocalProcessingGate.LocalPermit, OperationContext) -> Unit,
    private val maxArtifactBytes: Long = DirectNormalizationLimits.MAX_BYTES,
) {
    init { require(maxArtifactBytes > 0 && ownerLock === reservations.ownerLock) }
    private inner class Commit : NormalizedCommitHandle { override fun close() = abandon(this) }
    private class Publication(override val receipt: MediaCommitReceipt) : NormalizedPublicationHandle
    private class Entry(
        val handle: Commit,
        val token: NormalizationIoReservations.Token,
        var state: NormalizedCommitState,
        val output: ArtifactOutputDescriptor,
        val execution: AcceptedDirectAttempt,
        val permit: DirectLocalProcessingGate.LocalPermit,
        val context: OperationContext,
        var publication: Publication? = null,
        var canonical: NormalizedFileObservation? = null,
        var raw: NormalizedFileObservation? = null,
        var parents: List<NormalizationDescriptorIo.Stat>? = null,
    )
    private val commits = IdentityHashMap<NormalizedCommitHandle, Entry>()
    private val publications = IdentityHashMap<NormalizedPublicationHandle, Entry>()

    fun begin(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle = beginOwned(record, output, execution, permit, context)
    fun begin(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle = beginOwned(record, output, execution, permit, context)
    private fun beginOwned(record: SaveRecord, output: ArtifactOutputDescriptor,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle {
        val owner = owner(record)
        val entry = synchronized(ownerLock) {
            available(); live(execution, record, permit, context); ensureNoHandles(owner)
            val state = journal.read(owner) ?: reject()
            requireActive(state, record, output, context)
            val token = reservations.reserve(owner, NormalizationIoReservations.Stage.COMMIT,
                { live(execution, record, permit, context) }, state.workspace.key)
            Entry(Commit(), token, state, output, execution, permit, context).also { commits[it.handle] = it }
        }
        try {
            var layout = inspectFiles(entry)
            requireAdopted(layout, entry.state.workspace, entry.state.owned.state == OwnedState.STAGING)
            if (entry.state.owned.state == OwnedState.INDEXED) requireFinal(layout, entry.state.workspace)
            synchronized(ownerLock) {
                exact(entry)
                entry.canonical = layout.primary ?: layout.final ?: reject()
                entry.raw = layout.raw ?: reject()
                if (layout.parents.size != 5) reject()
                entry.parents = layout.parents.toList()
            }
            if (entry.state.owned.state == OwnedState.STAGING) {
                requireAdopted(layout, entry.state.workspace, requirePrimary = true)
                filesIo(entry) { files.syncCanonical(entry.state.workspace.key, entry.state.owned.mediaId, false, layout.primary!!, entry.token) }
                val intended = entry.state.owned.copy(state = OwnedState.INTENT, evidence = record.verification)
                update(entry, entry.state.copy(owned = intended)) { journal.writeIntent(entry.state, intended) }
            }
            if (entry.state.owned.state == OwnedState.INTENT) {
                layout = inspectFiles(entry)
                requireAdopted(layout, entry.state.workspace, requirePrimary = false)
                val movedSource = layout.primary
                if (movedSource != null) {
                    try { filesIo(entry) { files.promote(entry.state.workspace.key, entry.state.owned.mediaId, movedSource, entry.token) } }
                    catch (failure: Exception) {
                        // A thrown rename may have completed. Only exact P xor final reconciliation
                        // is admissible; never retry a rename when the result is unknown.
                        layout = inspectFiles(entry)
                        requireAdopted(layout, entry.state.workspace, requirePrimary = false)
                        if (layout.primary != null || layout.final != movedSource) throw failure
                    }
                }
                filesIo(entry) { files.sync(entry.state.workspace.key, NormalizedCommitDirectory.MEDIA, entry.token) }
                filesIo(entry) { files.sync(entry.state.workspace.key, NormalizedCommitDirectory.STAGING, entry.token) }
                layout = inspectFiles(entry)
                requireFinal(layout, entry.state.workspace)
                if (movedSource != null && layout.final != movedSource) reject()
                filesIo(entry) { files.syncCanonical(entry.state.workspace.key, entry.state.owned.mediaId, true, layout.final!!, entry.token) }
                val indexed = entry.state.copy(owned = entry.state.owned.copy(state = OwnedState.INDEXED), indexed = entry.state.owned.receipt())
                update(entry, indexed) { journal.writeIndex(entry.state) }
            }
            if (entry.state.owned.state != OwnedState.INDEXED || entry.state.indexed != entry.state.owned.receipt()) reject()
            requireFinal(layout, entry.state.workspace)
            return entry.handle
        } catch (failure: Throwable) { abandon(entry.handle); throw failure }
    }

    /** A second independent full digest, separate from begin and native verification. Never repairs. */
    fun inspect(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedPublicationHandle = inspectOwned(handle, record, output, execution, permit, context)
    fun inspect(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedPublicationHandle = inspectOwned(handle, record, output, execution, permit, context)
    private fun inspectOwned(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedPublicationHandle {
        val entry = synchronized(ownerLock) {
            val value = commits[handle] ?: reject()
            if (value.execution !== execution || value.permit !== permit || value.context !== context ||
                value.output !== output || value.state.record != record || value.publication != null) reject()
            exact(value); value
        }
        try {
            requireFinal(inspectFiles(entry), entry.state.workspace)
            return synchronized(ownerLock) {
                exact(entry)
                reservations.advance(entry.token, NormalizationIoReservations.Stage.COMMIT, NormalizationIoReservations.Stage.PUBLICATION)
                Publication(entry.state.owned.receipt()).also { entry.publication = it; publications[it] = entry }
            }
        } catch (failure: Throwable) { abandon(handle); throw failure }
    }

    /** One-shot atomic publication. SQL write-then-throw is resolved against the full fixed proposal. */
    fun publish(expectedRevision: Long, succeeded: SaveRecord, publication: NormalizedPublicationHandle): Boolean {
        val entry = synchronized(ownerLock) {
            val value = publications[publication] ?: return false
            exact(value)
            if (value.publication !== publication || expectedRevision != value.state.record.task.revision ||
                !validSuccess(value.state.record, succeeded, publication.receipt)) return false
            value
        }
        try {
            val proposed = entry.state.copy(record = succeeded, indexVisible = true, libraryLinked = true)
            return writeAndRead(entry.state, proposed, { exact(entry) }) { journal.publish(entry.state, succeeded) }
        } finally { abandon(entry.handle) }
    }

    /** Discards capability only. Pending/open/uncertain IO still belongs to the shared registry. */
    fun abandon(handle: NormalizedCommitHandle) = synchronized(ownerLock) {
        commits[handle]?.let { entry ->
            entry.publication?.let { publications.remove(it) }
            // A concurrent close revokes now but leaves the entry for its actual worker to retire.
            if (reservations.retire(entry.token)) commits.remove(handle)
        }
        Unit
    }

    /** Terminal-only, original permit identity plus a separate bounded cleanup IO context. */
    fun cleanup(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit,
        invocationContext: OperationContext, ioContext: OperationContext): NormalizationWorkspace {
        val owner = owner(record)
        var state: NormalizedCommitState
        val token = synchronized(ownerLock) {
            available(); gate.assertIssuedHere(permit, invocationContext); ensureNoHandles(owner)
            state = journal.read(owner) ?: reject()
            if (state.record != record) reject()
            requireTerminal(state)
            reservations.reserve(owner, NormalizationIoReservations.Stage.CLEANUP,
                { gate.assertIssuedHere(permit, invocationContext); checkOperation(ioContext) }, state.workspace.key)
        }
        fun check(): Unit = synchronized(ownerLock) {
            available(); reservations.requireExact(token); token.requireLive()
            if (journal.read(owner) != state) reject()
            requireTerminal(state)
        }
        fun <T> io(block: () -> T): T { check(); val result = reservations.withIo(token, block); check(); return result }
        fun observe() = io { files.inspect(state.workspace.key, state.owned.mediaId, state.record.task.state != TaskState.SUCCEEDED, token) }
        fun persist(next: NormalizedCommitState, write: () -> Boolean) {
            if (!writeAndRead(state, next, { check() }, write)) reject()
            state = next
        }
        try {
            var layout = observe()
            if (state.workspace.lifecycle == NormalizationLifecycle.CLEANED) {
                requireEmpty(layout); return state.workspace
            }
            if (state.workspace.cleanup == null) {
                val successful = state.record.task.state == TaskState.SUCCEEDED
                val plan = plan(state, layout, successful)
                val pending = state.workspace.copy(revision = increment(state.workspace.revision),
                    lifecycle = NormalizationLifecycle.CLEANUP_PENDING,
                    disposition = if (successful) state.workspace.disposition else NormalizationDisposition.DISCARD_ONLY,
                    cleanup = plan)
                val next = state.copy(workspace = pending,
                    owned = if (successful) state.owned else state.owned.copy(state = OwnedState.DISCARDED),
                    indexed = if (successful) state.indexed else null)
                persist(next) { journal.planCleanup(state, pending, !successful) }
            }
            val plan = state.workspace.cleanup ?: reject()
            if (plan.identities == null) reject() // v1 is readable; never invent inode or final deletion authority.
            for (role in NormalizedCommitRole.entries) {
                layout = observe(); requirePlanned(layout, plan, state)
                val expected = planned(plan, role)
                val actual = observed(layout, role)
                if (expected != null && actual != null) {
                    io { files.delete(state.workspace.key, state.owned.mediaId, role, expected, token) }
                    io { files.sync(state.workspace.key, when (role) {
                        NormalizedCommitRole.PRIMARY -> NormalizedCommitDirectory.STAGING
                        NormalizedCommitRole.FINAL -> NormalizedCommitDirectory.MEDIA
                        else -> NormalizedCommitDirectory.GENERATION
                    }, token) }
                }
            }
            layout = observe(); requirePlanned(layout, plan, state)
            if (layout.generationExists) {
                io { files.removeEmpty(state.workspace.key, NormalizedCommitDirectory.GENERATION, token) }
                io { files.sync(state.workspace.key, NormalizedCommitDirectory.WORKSPACE, token) }
            }
            layout = observe()
            if (layout.workspaceExists) io { files.removeEmpty(state.workspace.key, NormalizedCommitDirectory.WORKSPACE, token) }
            // Always repeat durability barriers after an earlier unlink/rmdir write-then-throw.
            io { files.sync(state.workspace.key, NormalizedCommitDirectory.STAGING, token) }
            if (plan.commitOutput != null) io { files.sync(state.workspace.key, NormalizedCommitDirectory.MEDIA, token) }
            requireEmpty(observe())
            val cleaned = state.workspace.copy(revision = increment(state.workspace.revision), lifecycle = NormalizationLifecycle.CLEANED)
            persist(state.copy(workspace = cleaned)) { journal.finishCleanup(state, cleaned) }
            return state.workspace
        } finally { synchronized(ownerLock) { reservations.retire(token) } }
    }

    private fun plan(state: NormalizedCommitState, layout: NormalizedCommitLayout, successful: Boolean): NormalizationCleanupPlan {
        val row = state.workspace
        if (state.record.directConversion != null) {
            requireConversionTerminal(state)
            if (!successful && (!layout.workspaceExists || !layout.generationExists || layout.parents.size != 5)) reject()
        }
        if (row.lifecycle == NormalizationLifecycle.RECOVERY_REQUIRED) reject()
        if (successful) {
            if (row.lifecycle != NormalizationLifecycle.ADOPTED || row.disposition != NormalizationDisposition.CONTINUE ||
                layout.primary != null || layout.output != null || (layout.raw != null && layout.raw.fingerprint != row.raw)) reject()
        } else when (state.owned.state) {
            OwnedState.INTENT, OwnedState.INDEXED -> requireAdopted(layout, row, requirePrimary = false)
            OwnedState.STAGING -> requireStagingLayout(row, layout)
            OwnedState.DISCARDED -> reject() // Only the existing exact plan permits missing authorized roles.
        }
        val p = if (successful) null else layout.primary
        val r = layout.raw
        val o = if (successful) null else layout.output
        val f = if (successful) null else layout.final
        return NormalizationCleanupPlan(p?.fingerprint, r?.fingerprint, o?.fingerprint,
            f?.let { NormalizationCommitOutput(state.owned.mediaId, it.fingerprint) },
            NormalizationCleanupIdentities(p?.identity, r?.identity, o?.identity, f?.identity)).also {
                if (state.record.directConversion != null) requireConversionPlan(state, it)
            }
    }
    private fun requireStagingLayout(row: NormalizationWorkspace, s: NormalizedCommitLayout) {
        if (s.final != null) reject()
        val valid = when (row.lifecycle) {
            NormalizationLifecycle.RAW_COMPLETE -> s.primary?.fingerprint == row.raw && s.raw == null && (s.output == null || s.output.fingerprint.size == 0L)
            NormalizationLifecycle.WRITING -> s.primary?.fingerprint == row.raw && s.raw == null && s.generationExists
            NormalizationLifecycle.OUTPUT_READY -> s.primary?.fingerprint == row.raw && s.raw == null && s.output?.fingerprint == row.output
            NormalizationLifecycle.ADOPT_INTENT ->
                (s.primary?.fingerprint == row.raw && s.raw == null && s.output?.fingerprint == row.output) ||
                (s.primary == null && s.raw?.fingerprint == row.raw && s.output?.fingerprint == row.output) ||
                (s.primary?.fingerprint == row.output && s.raw?.fingerprint == row.raw && s.output == null)
            NormalizationLifecycle.ADOPTED -> s.primary?.fingerprint == row.output && s.raw?.fingerprint == row.raw && s.output == null
            else -> false
        }
        if (!valid) reject()
    }
    private fun requirePlanned(layout: NormalizedCommitLayout, plan: NormalizationCleanupPlan, state: NormalizedCommitState) {
        if (plan.commitOutput?.mediaId?.let { it != state.owned.mediaId } == true) reject()
        if (state.record.task.state == TaskState.SUCCEEDED && (plan.primary != null || plan.output != null || plan.commitOutput != null)) reject()
        for (role in NormalizedCommitRole.entries) {
            val actual = observed(layout, role)
            if (actual != null && actual != planned(plan, role)) reject()
        }
    }
    private fun observed(s: NormalizedCommitLayout, role: NormalizedCommitRole) = when (role) {
        NormalizedCommitRole.PRIMARY -> s.primary; NormalizedCommitRole.RAW -> s.raw
        NormalizedCommitRole.OUTPUT -> s.output; NormalizedCommitRole.FINAL -> s.final
    }
    private fun planned(plan: NormalizationCleanupPlan, role: NormalizedCommitRole): NormalizedFileObservation? {
        val ids = plan.identities ?: reject()
        val pair = when (role) {
            NormalizedCommitRole.PRIMARY -> plan.primary to ids.primary
            NormalizedCommitRole.RAW -> plan.raw to ids.raw
            NormalizedCommitRole.OUTPUT -> plan.output to ids.output
            NormalizedCommitRole.FINAL -> plan.commitOutput?.fingerprint to ids.commitOutput
        }
        return pair.first?.let { NormalizedFileObservation(it, pair.second ?: reject()) }
    }
    private fun requireEmpty(s: NormalizedCommitLayout) {
        if (s.primary != null || s.raw != null || s.output != null || s.final != null || s.workspaceExists || s.generationExists) reject()
    }
    private fun requireTerminal(state: NormalizedCommitState) {
        val record = state.record; val row = state.workspace
        if (record.directConversion != null) { requireConversionTerminal(state); return }
        if (record.task.state !in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED) ||
            owner(record) != row.key.owner || state.owned.taskId != record.task.taskId || state.owned.artifact != record.artifact ||
            record.transfer?.let { sourceTransferFingerprint(it) } != row.sourceTransferSha256 ||
            record.transfer?.bytesOnDisk != row.raw.size) reject()
        record.directNormalization?.let { proof ->
            if (proof.normalizationId != row.key.normalizationId || proof.rawSizeBytes != row.raw.size || proof.rawSha256 != row.raw.sha256 ||
                proof.digest() != row.proofSha256 || NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) != row.output) reject()
        }
        if (row.lifecycle in setOf(NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED) &&
            record.directNormalization == null) reject()
        if (state.owned.evidence != null && (record.verification != state.owned.evidence || record.directNormalization == null)) reject()
        if (state.owned.state in setOf(OwnedState.INTENT, OwnedState.INDEXED) && state.owned.evidence == null) reject()
        if (state.owned.state == OwnedState.STAGING && (state.owned.evidence != null || state.indexed != null)) reject()
        if (state.owned.state in setOf(OwnedState.INTENT, OwnedState.DISCARDED) && state.indexed != null) reject()
        if (record.task.state != TaskState.SUCCEEDED && state.owned.state == OwnedState.INDEXED &&
            state.indexed != state.owned.receipt()) reject()
        if (record.task.state == TaskState.SUCCEEDED) {
            if (row.disposition != NormalizationDisposition.CONTINUE || row.output == null ||
                record.directNormalization?.digest() != row.proofSha256 || record.verification != state.owned.evidence ||
                record.receipt != state.owned.receipt() || state.owned.state != OwnedState.INDEXED) reject()
            // Library deletion/editing after success cannot revive the media and does not revoke raw cleanup.
        } else if (state.indexVisible || state.libraryLinked || record.receipt != null) reject()
    }

    /** Known durable conversion terminal states; historical audio grants cleanup only, never active authority. */
    private fun requireConversionTerminal(state: NormalizedCommitState) {
        val record = state.record; val row = state.workspace; val owned = state.owned
        val proof = record.directConversion ?: reject()
        val artifact = record.artifact ?: reject(); val transfer = record.transfer ?: reject()
        val source = record.request.networkSourceMetadata ?: reject()
        if (record.request.method != TaskMethod.DIRECT || record.task.method != TaskMethod.DIRECT ||
            record.task.state !in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED) ||
            record.directNormalization != null || record.checkpoint != null ||
            owner(record) != row.key.owner || artifact.commitId != record.commitId ||
            owned.taskId != record.task.taskId || owned.artifact != artifact || row.failure != null ||
            !proof.matchesSource(record.task.taskId, source.sourceIdentity, artifact, transfer) ||
            (source.expectedAudio && proof.expectation.audio == null) ||
            proof.normalizationId != row.key.normalizationId || proof.digest() != row.proofSha256 ||
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) != row.raw ||
            NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) != row.output ||
            transfer.bytesOnDisk != row.raw.size || sourceTransferFingerprint(transfer) != row.sourceTransferSha256) reject()
        normalizationId(owned.mediaId)
        val evidence = record.verification
        if (evidence != null && !evidence.isValidFor(artifact, ArtifactOutputDescriptor.ConvertedDirect(transfer, proof), source.expectedAudio)) reject()
        val successful = record.task.state == TaskState.SUCCEEDED
        if (successful) {
            if (record.phase != SavePhase.CLEANUP || evidence == null || row.disposition != NormalizationDisposition.CONTINUE ||
                owned.state != OwnedState.INDEXED || owned.evidence != evidence || record.receipt != owned.receipt()) reject()
            // A later Library deletion does not revoke exact temporary-raw cleanup or permit republishing.
        } else {
            if (record.receipt != null || state.indexVisible || state.libraryLinked ||
                (record.phase == SavePhase.COMMITTING) != (evidence != null)) reject()
            when (owned.state) {
                OwnedState.STAGING -> if (owned.evidence != null || state.indexed != null) reject()
                OwnedState.INTENT -> if (evidence == null || owned.evidence != evidence || state.indexed != null) reject()
                OwnedState.INDEXED -> if (evidence == null || owned.evidence != evidence || state.indexed != owned.receipt()) reject()
                OwnedState.DISCARDED -> if (state.indexed != null || row.cleanup == null ||
                    (owned.evidence != null && owned.evidence != evidence)) reject()
            }
        }
        when (row.lifecycle) {
            NormalizationLifecycle.OUTPUT_READY -> if (successful || record.phase !in setOf(SavePhase.NORMALIZING, SavePhase.ADOPTING) ||
                evidence != null || owned.state != OwnedState.STAGING) reject()
            NormalizationLifecycle.ADOPT_INTENT -> if (successful || record.phase != SavePhase.ADOPTING ||
                evidence != null || owned.state != OwnedState.STAGING) reject()
            NormalizationLifecycle.ADOPTED -> if (!successful && record.phase !in setOf(SavePhase.ADOPTING, SavePhase.VERIFYING, SavePhase.COMMITTING)) reject()
            NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED -> {
                if (!successful && (row.disposition != NormalizationDisposition.DISCARD_ONLY || owned.state != OwnedState.DISCARDED)) reject()
                requireConversionPlan(state, row.cleanup ?: reject())
                return
            }
            else -> reject()
        }
        if (row.disposition != NormalizationDisposition.CONTINUE || row.cleanup != null) reject()
    }
    private fun requireConversionPlan(state: NormalizedCommitState, plan: NormalizationCleanupPlan) {
        val row = state.workspace; val ids = plan.identities ?: reject()
        val all = listOfNotNull(ids.primary, ids.raw, ids.output, ids.commitOutput)
        if (all.distinct().size != all.size || all.map { it.device }.distinct().size > 1) reject()
        val ready = plan.primary == row.raw && plan.raw == null && plan.output == row.output && plan.commitOutput == null
        val moved = plan.primary == null && plan.raw == row.raw && plan.output == row.output && plan.commitOutput == null
        val adopted = plan.primary == row.output && plan.raw == row.raw && plan.output == null && plan.commitOutput == null
        val promoted = plan.primary == null && plan.raw == row.raw && plan.output == null &&
            plan.commitOutput == NormalizationCommitOutput(state.owned.mediaId, row.output ?: reject())
        val valid = when (state.record.phase) {
            SavePhase.NORMALIZING -> ready
            SavePhase.ADOPTING -> ready || moved || adopted
            SavePhase.VERIFYING -> adopted
            SavePhase.COMMITTING -> adopted || promoted
            SavePhase.CLEANUP -> state.record.task.state == TaskState.SUCCEEDED && plan.primary == null &&
                (plan.raw == null || plan.raw == row.raw) && plan.output == null && plan.commitOutput == null
            else -> false
        }
        if (!valid) reject()
    }
    private fun inspectFiles(entry: Entry): NormalizedCommitLayout = filesIo(entry) {
        files.inspect(entry.state.workspace.key, entry.state.owned.mediaId, true, entry.token).also { observed ->
            entry.canonical?.let { if ((observed.primary ?: observed.final) != it) reject() }
            entry.raw?.let { if (observed.raw != it) reject() }
            entry.parents?.let { expected ->
                if (observed.parents.size != expected.size || expected.indices.any { index ->
                    if (index < 3) !expected[index].sameInode(observed.parents[index]) else expected[index] != observed.parents[index]
                }) reject()
            }
        }
    }
    private fun <T> filesIo(entry: Entry, block: () -> T): T {
        synchronized(ownerLock) { exact(entry) }
        val result = reservations.withIo(entry.token, block)
        synchronized(ownerLock) { exact(entry) }
        return result
    }
    private fun update(entry: Entry, next: NormalizedCommitState, write: () -> Boolean) {
        if (!writeAndRead(entry.state, next, { exact(entry) }, write)) reject()
        synchronized(ownerLock) { entry.state = next }
    }
    private fun writeAndRead(expected: NormalizedCommitState, next: NormalizedCommitState,
        verify: () -> Unit, write: () -> Boolean): Boolean {
        val wrote = synchronized(ownerLock) { verify(); try { write() } catch (_: Exception) { null } }
        // Readback is outside the orchestration monitor, including write-then-throw outcomes.
        // The adapter performs one coherent SQL snapshot read; no filesystem work is involved.
        val found = journal.read(expected.workspace.key.owner)
        if (found == next) return true
        if (wrote != true && found == expected) return false
        reject()
    }
    private fun exact(entry: Entry) {
        available()
        if (commits[entry.handle] !== entry) reject()
        reservations.requireExact(entry.token)
        live(entry.execution, entry.state.record, entry.permit, entry.context)
        if (journal.read(entry.state.workspace.key.owner) != entry.state) reject()
    }
    private fun requireActive(state: NormalizedCommitState, record: SaveRecord,
        output: ArtifactOutputDescriptor, context: OperationContext) {
        val row = state.workspace
        val generation: String; val digest: String; val raw: NormalizationFingerprint; val canonical: NormalizationFingerprint
        when (output) {
            is ArtifactOutputDescriptor.NormalizedDirect -> {
                val proof = output.normalizationProof
                if (record.directConversion != null || !proof.isCurrentPolicy() ||
                    (record.request.networkSourceMetadata?.expectedAudio == true && proof.conservation.audio == null)) reject()
                generation = proof.normalizationId; digest = proof.digest()
                raw = NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256)
                canonical = NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256)
            }
            is ArtifactOutputDescriptor.ConvertedDirect -> {
                val proof = output.conversionProof
                if (record.directNormalization != null || !proof.isCurrentPolicy() ||
                    (record.request.networkSourceMetadata?.expectedAudio == true && proof.expectation.audio == null) ||
                    !proof.matchesSource(record.task.taskId, record.request.networkSourceMetadata!!.sourceIdentity,
                        record.artifact ?: reject(), output.sourceTransfer)) reject()
                generation = proof.normalizationId; digest = proof.digest()
                raw = NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256)
                canonical = NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256)
            }
            is ArtifactOutputDescriptor.Transferred -> reject()
        }
        val localMaximum = context.resourceBudget.maxBytes ?: reject()
        if (raw.size > minOf(maxArtifactBytes, DirectNormalizationLimits.MAX_BYTES) ||
            canonical.size > minOf(maxArtifactBytes, DirectNormalizationLimits.MAX_BYTES) ||
            raw.size > localMaximum || canonical.size > localMaximum - raw.size) reject()
        if (state.record != record || record.task.state != TaskState.RUNNING || record.task.method != TaskMethod.DIRECT ||
            record.phase != SavePhase.COMMITTING || record.receipt != null || record.checkpoint != null ||
            record.operationId != context.operationId || record.attempt != context.attempt || record.retryBudget != context.retryBudget ||
            context.deadlineEpochMillis > record.deadlineEpochMillis || record.selectedOutputDescriptor() != output ||
            record.verification?.isValidFor(record.artifact!!, output, record.request.networkSourceMetadata!!.expectedAudio) != true ||
            row.key.owner != owner(record) || row.key.normalizationId != generation ||
            row.lifecycle != NormalizationLifecycle.ADOPTED || row.disposition != NormalizationDisposition.CONTINUE ||
            row.cleanup != null || row.failure != null || row.raw != raw ||
            row.sourceTransferSha256 != sourceTransferFingerprint(output.sourceTransfer) ||
            row.output != canonical || row.proofSha256 != digest ||
            state.owned.taskId != record.task.taskId || state.owned.artifact != record.artifact ||
            state.indexVisible || state.libraryLinked || state.owned.state == OwnedState.DISCARDED) reject()
        normalizationId(state.owned.mediaId)
        if (state.owned.state == OwnedState.STAGING) {
            if (state.owned.evidence != null || state.indexed != null) reject()
        } else {
            if (state.owned.evidence != record.verification ||
                (state.owned.state == OwnedState.INTENT && state.indexed != null) ||
                (state.owned.state == OwnedState.INDEXED && state.indexed != state.owned.receipt())) reject()
        }
    }
    private fun requireAdopted(s: NormalizedCommitLayout, row: NormalizationWorkspace, requirePrimary: Boolean) {
        if (!s.workspaceExists || !s.generationExists || s.raw?.fingerprint != row.raw || s.output != null ||
            (s.primary == null) == (s.final == null) ||
            (s.primary ?: s.final)?.fingerprint != row.output || (requirePrimary && s.primary == null)) reject()
    }
    private fun requireFinal(s: NormalizedCommitLayout, row: NormalizationWorkspace) {
        requireAdopted(s, row, false); if (s.primary != null || s.final == null) reject()
    }
    private fun validSuccess(prior: SaveRecord, next: SaveRecord, receipt: MediaCommitReceipt): Boolean =
        prior.task.revision < Long.MAX_VALUE && next.task.state == TaskState.SUCCEEDED &&
            next.task.revision == prior.task.revision + 1 && next.task.taskId == prior.task.taskId &&
            next.task == prior.task.copy(state = TaskState.SUCCEEDED, revision = prior.task.revision + 1,
                progressPercent = 100, errorCode = null, waitingUser = null) && next.receipt == receipt && next.phase == SavePhase.CLEANUP &&
            next.error == null && next.waitReason == null && prior.copy(task = next.task, phase = SavePhase.CLEANUP,
                error = null, waitReason = null, receipt = receipt) == next
    private fun live(execution: AcceptedDirectAttempt, record: SaveRecord,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext) {
        gate.assertIssuedHere(permit, context); execution.requireLive(context)
        if (execution.taskId != record.task.taskId || execution.operationId != record.operationId || execution.attempt != record.attempt) reject()
        checkOperation(context); authorize(execution, record, permit, context)
    }
    private fun available() { if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE) }
    private fun owner(record: SaveRecord): NormalizationOwner = record.artifact?.let {
        NormalizationOwner(record.task.taskId, it.artifactId, record.commitId)
    } ?: reject()
    private fun increment(value: Long): Long { if (value == Long.MAX_VALUE) reject(); return value + 1 }
    private fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
}
