package com.easysavevideo.app.vault.storage

import android.content.Context
import com.easysavevideo.app.vault.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportRelease
import java.util.IdentityHashMap
import com.easysavevideo.app.vault.VaultAuthenticationPort
import com.easysavevideo.app.vault.VaultCredentialMutation
import com.easysavevideo.app.vault.VaultCredentialMutationKind
import com.easysavevideo.app.vault.VaultCredentialMutationProposal
import com.easysavevideo.app.vault.VaultCiphertext
import com.easysavevideo.app.vault.VaultFailure
import com.easysavevideo.app.vault.VaultOperationContext
import com.easysavevideo.app.vault.VaultOperationException
import com.easysavevideo.app.vault.VaultPersistentIdentity
import com.easysavevideo.app.vault.VaultRuntimeLimits
import com.easysavevideo.app.vault.VaultStagedData
import com.easysavevideo.app.vault.VaultStagedVerification
import com.easysavevideo.app.vault.VaultStoredSnapshot
import com.easysavevideo.app.vault.VaultStoreObservation
import com.easysavevideo.app.vault.VaultStorePhase
import com.easysavevideo.app.vault.VaultStorePort
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.core.model.VaultRetryWrite
import com.easysavevideo.core.model.VaultRetryWriteKind

/** Encrypted empty-catalog store. Not wired into any enabled product entry in this slice. */
internal class AndroidVaultStore(
    context: Context,
    private val authentication: VaultAuthenticationPort,
    private val sourceOwner: () -> VaultMediaSourcePort? = { null },
) : VaultStorePort {
    private val database = AndroidVaultDatabase(context)
    private val mediaFiles = VaultMediaFiles(database::mediaDirectory, database::syncMediaDirectory)
    private var mediaJournal: VaultMediaJournal? = null
    private var mediaSnapshot: VaultStoredSnapshot? = null
    private var pendingMedia: VaultMediaProposal? = null
    private var pendingMediaMutation: VaultMediaMutation? = null
    private var pendingMediaKnownDurable = false
    private var frozenSourceOwner: VaultMediaSourcePort? = null
    private class Pin(val operation: VaultMediaOperation, val source: MediaReadRef,
        val identity: VaultMediaObjectIdentity, val committed: VaultStoredSnapshot) {
        var sourceDeleted = false
    }
    private class MoveReceipt(val pin: Pin) : CommittedMoveReceipt {
        override fun close() = Unit // Closing a handle does not retire a durable target pin.
        override fun toString() = "CommittedMoveReceipt(REDACTED)"
    }
    private class UncommittedReceipt(val operation: VaultMediaOperation, val source: MediaReadRef) : VaultUncommittedMoveReceipt {
        override fun close() = Unit
        override fun toString() = "VaultUncommittedMoveReceipt(REDACTED)"
    }
    private val pins = mutableMapOf<String, Pin>()
    private val moveReceipts = IdentityHashMap<CommittedMoveReceipt, Pin>()
    private val uncommittedReceipts = IdentityHashMap<VaultUncommittedMoveReceipt, UncommittedReceipt>()

    private var pending: VaultSqliteMutation? = null
    private var pendingContext: VaultOperationContext? = null
    private var closed = false
    private var confirmedReservation: VaultRetryWrite? = null
    private var completedReset: VaultCompletedResetReceipt? = null
    private var credentialWrite: VaultCredentialWriteReceipt? = null
    private var completedCredentialReset: VaultCompletedResetReceipt? = null

    override fun observeMediaDrain(): ExportRelease = mediaFiles.observeMediaDrain()

    override fun compareAndSwapMedia(expected: VaultStoredSnapshot, mutation: VaultMediaMutation,
        context: VaultOperationContext): VaultStoredSnapshot {
        val before = VaultSnapshotCodec.freeze(expected)
        val proposal = authentication.validateMediaMutation(before, mutation, context)
        val restored = if (proposal.operation.kind == VaultMediaKind.MOVE_IN && proposal.phase == VaultMediaPhase.CANCELLED)
            fixedSourceOwner().validateSourceRestored(requireNotNull(proposal.source), proposal.operation) else true
        return synchronized(this) { guard {
        requireOpen(); require(restored)
        require(proposal.phase != VaultMediaPhase.COMMITTED_SOURCE_PENDING && !proposal.durablePin)
        validateMediaShape(before, proposal, catalogChange = false)
        if (proposal.phase == VaultMediaPhase.COMPLETE && proposal.operation.kind == VaultMediaKind.MOVE_IN) {
            val pin = pins[proposal.operation.operationId] ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
            require(pin.sourceDeleted && pin.operation == proposal.operation && pin.source == proposal.source)
        }
        val committed = writeMedia(before, proposal, mutation, context)
        if (proposal.phase == VaultMediaPhase.COMPLETE && proposal.operation.kind == VaultMediaKind.MOVE_IN) {
            pins.remove(proposal.operation.operationId)?.let { pin ->
                mediaFiles.restoreAuthenticatedReservation(pin.operation, pin.identity)
                mediaFiles.retireReservation(pin.operation, pin.identity)
            }
        }
        committed
        } }
    }

    @Synchronized override fun createMediaFile(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity,
        context: VaultOperationContext): VaultOwnedCiphertext = guard {
        requireOpen(); context.checkActive()
        if (pending != null || pendingMedia != null) throw VaultOperationException(VaultFailure.INCOMPLETE)
        val journal = mediaJournal ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        require(journal.operation == operation && operation.kind == VaultMediaKind.MOVE_IN && journal.objectIdentity == identity)
        require(journal.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.SOURCE_RESERVED, VaultMediaPhase.ENCRYPTING) && !journal.cancelRequested)
        require(pins.values.none { it.identity == identity })
        requireExactSnapshot(requireNotNull(mediaSnapshot))
        mediaFiles.create(operation, identity).also { file ->
            try { context.checkActive() } catch (failure: Exception) { file.closeAndObserve(); throw failure }
        }
    }

    @Synchronized override fun openMediaFile(identity: VaultMediaObjectIdentity, context: VaultOperationContext): VaultOwnedCiphertext = guard {
        requireOpen(); context.checkActive()
        // This gives ciphertext only. Authentication still needs exact catalog/root before any plaintext.
        mediaFiles.open(identity).also { file ->
            try { context.checkActive() } catch (failure: Exception) { file.closeAndObserve(); throw failure }
        }
    }

    @Synchronized override fun commitMedia(expected: VaultStoredSnapshot, mutation: VaultMediaMutation,
        file: VaultOwnedCiphertext, proof: VaultFinalizedMediaProof, context: VaultOperationContext): VaultCommittedMedia = guard {
        requireOpen()
        val before = VaultSnapshotCodec.freeze(expected)
        val proposal = authentication.validateMediaMutation(before, mutation, context)
        validateMediaShape(before, proposal, catalogChange = true)
        require(proposal.operation.kind == VaultMediaKind.MOVE_IN && proposal.phase == VaultMediaPhase.COMMITTED_SOURCE_PENDING && proposal.durablePin)
        val source = requireNotNull(proposal.source); val identity = requireNotNull(proposal.finalObject)
        require(mediaFiles.owns(file) && file.operationId == proposal.operation.operationId && file.identity == identity)
        val verified = authentication.validateFinalizedMedia(proof, file, context)
        require(verified.identity == identity && proposal.journal.ciphertextEvidence == verified.ciphertext && proposal.journal.plaintextEvidence == verified.plaintext)
        require(mediaFiles.evidence(file, context) == verified.ciphertext)
        // Same immutable read anchor remains open across rename; no plaintext temp and no path reopen.
        mediaFiles.promote(file)
        val committed = writeMedia(before, proposal, mutation, context)
        requireExactSnapshot(committed)
        require(mediaFiles.evidence(file, context) == verified.ciphertext)
        val pin = pins[proposal.operation.operationId]?.also {
            require(it.operation == proposal.operation && it.source == source && it.identity == identity)
        } ?: Pin(proposal.operation, source, identity, committed).also { pins[proposal.operation.operationId] = it }
        val receipt = moveReceipts.entries.firstOrNull { it.value === pin }?.key
            ?: MoveReceipt(pin).also { require(moveReceipts.size < 32); moveReceipts[it] = pin }
        VaultCommittedMedia(committed, receipt)
    }

    @Synchronized override fun abandonUncommittedMedia(expected: VaultStoredSnapshot, cancelledMutation: VaultMediaMutation,
        file: VaultOwnedCiphertext?, context: VaultOperationContext): VaultUncommittedMoveReceipt = guard {
        requireOpen()
        val before = VaultSnapshotCodec.freeze(expected)
        val proposal = authentication.validateMediaMutation(before, cancelledMutation, context)
        validateMediaShape(before, proposal, catalogChange = false)
        require(proposal.phase == VaultMediaPhase.CLEANUP_PENDING && proposal.journal.cancelRequested && proposal.operation.kind == VaultMediaKind.MOVE_IN && !proposal.durablePin)
        val source = requireNotNull(proposal.source); val identity = proposal.journal.objectIdentity
        require(pins[proposal.operation.operationId] == null && pins.values.none { it.identity == identity })
        require(file == null || (mediaFiles.owns(file) && file.identity == identity))
        val committed = writeMedia(before, proposal, cancelledMutation, context)
        requireExactSnapshot(committed)
        // The auth owner validated no catalog object exists before sealing CANCELLED. Unknown CAS
        // never reaches this branch; exact owned output must really disappear before releasing source.
        if (identity != null) {
            // Same-auth cancellation validates the exact old encrypted name reservation lineage
            // and absence of any committed catalog reference. URI/path text is never that proof.
            mediaFiles.restoreAuthenticatedReservation(proposal.operation, identity)
            if (!mediaFiles.cleanup(proposal.operation, identity)) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
            mediaFiles.retireReservation(proposal.operation, identity)
        }
        context.checkActive()
        uncommittedReceipts.values.firstOrNull { it.operation == proposal.operation && it.source == source }
            ?: UncommittedReceipt(proposal.operation, source).also { require(uncommittedReceipts.size < 32); uncommittedReceipts[it] = it }
    }

    @Synchronized override fun recoverCommittedMove(expected: VaultStoredSnapshot, operation: VaultMediaOperation,
        source: MediaReadRef, file: VaultOwnedCiphertext, proof: VaultFinalizedMediaProof,
        context: VaultOperationContext): CommittedMoveReceipt = guard {
        requireOpen(); context.checkActive()
        val before = VaultSnapshotCodec.freeze(expected)
        requireExactSnapshot(before)
        require(mediaFiles.owns(file))
        val entry = authentication.validateCommittedMedia(before, operation, source, file, proof, context)
        require(entry.identity == file.identity && entry.moveOperation == operation && entry.source == source && entry.sourceCleanupPending)
        require(mediaFiles.evidence(file, context) == entry.ciphertext)
        val pin = pins[operation.operationId]?.also { require(it.operation == operation && it.source == source && it.identity == entry.identity) }
            ?: Pin(operation, source, entry.identity, before).also { pins[operation.operationId] = it }
        moveReceipts.entries.firstOrNull { it.value === pin }?.key
            ?: MoveReceipt(pin).also { require(moveReceipts.size < 32); moveReceipts[it] = pin }
    }

    @Synchronized override fun validateCommittedMove(receipt: CommittedMoveReceipt, source: MediaReadRef,
        operation: VaultMediaOperation): Boolean = !closed && moveReceipts[receipt]?.let {
        pins[operation.operationId] === it && it.operation == operation && it.source == source
    } == true
    @Synchronized override fun validateUncommittedMove(receipt: VaultUncommittedMoveReceipt, source: MediaReadRef,
        operation: VaultMediaOperation): Boolean = !closed && uncommittedReceipts[receipt]?.let {
        it.operation == operation && it.source == source && pins[operation.operationId] == null
    } == true
    override fun sourceDeleted(receipt: CommittedMoveReceipt, proof: VaultSourceDeletedReceipt) {
        val fixed = fixedSourceOwner()
        val pin = synchronized(this) {
            requireOpen()
            if (frozenSourceOwner == null) frozenSourceOwner = fixed
            require(frozenSourceOwner === fixed)
            moveReceipts[receipt]?.also { require(pins[it.operation.operationId] === it) }
                ?: throw VaultOperationException(VaultFailure.CONFLICT)
        }
        // No store monitor held across the other owner's bounded registry validation.
        if (!fixed.validateSourceDeleted(proof, pin.source, pin.operation)) throw VaultOperationException(VaultFailure.CONFLICT)
        synchronized(this) { require(pins[pin.operation.operationId] === pin); pin.sourceDeleted = true }
    }
    /** Only this owner's accepted durable facts can be consumed by the fixed phone adapter. */
    private fun phoneFact(): VaultMediaJournal? =
        if (closed || pending != null || pendingMedia != null || pendingMediaKnownDurable || mediaSnapshot == null) null else mediaJournal

    @Synchronized override fun validateBoundPhoneTarget(record: VaultMediaTargetRecord, use: VaultPhoneTargetUse): Boolean =
        VaultPhoneStoreFacts.targetUse(phoneFact(), record, use)

    @Synchronized override fun canCleanupPhoneTarget(record: VaultMediaTargetRecord): Boolean =
        VaultPhoneStoreFacts.cleanup(phoneFact(), record)

    @Synchronized override fun validateBoundPhoneTree(record: VaultPhoneTreeRecord): Boolean =
        VaultPhoneStoreFacts.tree(phoneFact(), record)

    @Synchronized override fun validateBatchChildCreate(tree: VaultPhoneTreeRecord, reservation: VaultMediaTargetRecord,
        operation: VaultMediaOperation): Boolean = VaultPhoneStoreFacts.create(phoneFact(), tree, reservation, operation)

    @Synchronized override fun validatePhoneTargetRetirement(operationId: String, target: VaultMediaTargetRecord,
        batchId: String?): Boolean = VaultPhoneStoreFacts.retirement(phoneFact(), operationId, target, batchId)

    private fun writeMedia(before: VaultStoredSnapshot, proposal: VaultMediaProposal, mutation: VaultMediaMutation,
        context: VaultOperationContext): VaultStoredSnapshot {
        val existing = pendingMedia
        if (existing != null && (existing.operation != proposal.operation ||
                !VaultSnapshotCodec.encode(existing.proposed).contentEquals(VaultSnapshotCodec.encode(proposal.proposed))))
            throw VaultOperationException(VaultFailure.INCOMPLETE)
        val currentPending = pending
        if (currentPending != null && !currentPending.proposed.payload().contentEquals(VaultSnapshotCodec.encode(proposal.proposed)))
            throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (proposal.phase == VaultMediaPhase.PREPARED && currentPending == null) {
            require(pins.isEmpty())
            moveReceipts.clear(); uncommittedReceipts.clear(); mediaFiles.retireClosedOwners()
        }
        if (proposal.phase == VaultMediaPhase.PREPARED && proposal.operation.kind == VaultMediaKind.MOVE_IN && currentPending == null) {
            mediaFiles.retireClosedOwners()
            // Real absent check precedes the initial encrypted whole-snapshot reservation CAS.
            mediaFiles.preflightReservation(proposal.operation, requireNotNull(proposal.journal.objectIdentity))
        }
        pendingMedia = proposal; pendingMediaMutation = mutation; pendingMediaKnownDurable = false
        var knownDurable = false
        val committed = try {
            write(before, VaultSnapshotCodec.freeze(proposal.proposed), proposal.operation.operationId, context, onKnownDurable = { knownDurable = true; pendingMediaKnownDurable = true })
        } catch (failure: Exception) {
            // No SQL pending means either no CAS was attempted or the DB proved conflict.
            // Permanently retire this authentication's proof; a new explicit recovery may reseal.
            // An actual UNKNOWN SQL operation is always preserved with its original budget.
            if (pending == null && !knownDurable) {
                mutation.close()
                pendingMedia = null; pendingMediaMutation = null
            }
            throw failure
        }
        acceptMediaFact(proposal, committed)
        return committed
    }
    private fun acceptMediaFact(proposal: VaultMediaProposal, committed: VaultStoredSnapshot) {
        require(VaultSnapshotCodec.encode(proposal.proposed).contentEquals(VaultSnapshotCodec.encode(committed)))
        if (proposal.phase == VaultMediaPhase.PREPARED && proposal.operation.kind == VaultMediaKind.MOVE_IN)
            mediaFiles.confirmReservation(proposal.operation, requireNotNull(proposal.journal.objectIdentity))
        mediaJournal = proposal.journal; mediaSnapshot = committed; pendingMedia = null; pendingMediaMutation = null; pendingMediaKnownDurable = false
    }
    private fun retireConfirmedOldMedia(unknown: VaultSqliteMutation): Boolean {
        val proposal = pendingMedia ?: return false
        if (!unknown.proposed.payload().contentEquals(VaultSnapshotCodec.encode(proposal.proposed))) return false
        val original = pendingContext ?: return false
        try { original.checkActive(); return false } catch (failure: VaultOperationException) {
            if (failure.category !in setOf(VaultFailure.CANCELLED, VaultFailure.DEADLINE)) return false
        }
        requireNotNull(pendingMediaMutation).close()
        pendingMediaMutation = null; pendingMedia = null
        return true
    }
    private fun fixedSourceOwner(): VaultMediaSourcePort {
        val selected = sourceOwner() ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        return synchronized(this) {
            if (frozenSourceOwner == null) frozenSourceOwner = selected
            require(frozenSourceOwner === selected)
            selected
        }
    }

    private fun validateMediaShape(before: VaultStoredSnapshot, proposal: VaultMediaProposal, catalogChange: Boolean) {
        require(before.phase == VaultStorePhase.READY && proposal.proposed.phase == VaultStorePhase.READY)
        require(before.identity == proposal.proposed.identity && proposal.proposed.storeRevision == nextRevision(before.storeRevision))
        require(proposal.operation == proposal.journal.operation && proposal.phase == proposal.journal.phase && proposal.source == proposal.journal.source)
        VaultPhoneStoreFacts.requireBatchShape(proposal.journal)
        mediaJournal?.let { previous ->
            val oldBatch = previous.batch
            if (oldBatch != null && oldBatch.batchId == proposal.journal.batch?.batchId) {
                val nextBatch = requireNotNull(proposal.journal.batch)
                require(oldBatch.planDigest == nextBatch.planDigest && oldBatch.selectedCatalogRevision == nextBatch.selectedCatalogRevision)
                require(oldBatch.tree == null || oldBatch.tree == nextBatch.tree)
                require(nextBatch.activeIndex >= oldBatch.activeIndex)
                oldBatch.items.take(oldBatch.activeIndex).forEachIndexed { index, item -> require(nextBatch.items[index] == item) }
            }
        }
        val old = requireNotNull(before.data); val next = requireNotNull(proposal.proposed.data)
        require(next.mediaJournalRevision == nextRevision(old.mediaJournalRevision) && next.mediaJournalEnvelope != null)
        require(next.copy(catalogEnvelope = old.catalogEnvelope, catalogRevision = old.catalogRevision,
            mediaJournalEnvelope = old.mediaJournalEnvelope, mediaJournalRevision = old.mediaJournalRevision) == old)
        if (catalogChange) require(next.catalogRevision == nextRevision(old.catalogRevision))
        else require(next.catalogRevision == old.catalogRevision && next.catalogEnvelope == old.catalogEnvelope)
        pins.values.forEach { pin ->
            if (proposal.operation.operationId != pin.operation.operationId && !pin.sourceDeleted)
                throw VaultOperationException(VaultFailure.INCOMPLETE)
        }
    }
    private fun requireExactSnapshot(snapshot: VaultStoredSnapshot) {
        val actual = database.readExisting()
        if (!actual.payload().contentEquals(VaultSnapshotCodec.encode(snapshot))) throw VaultOperationException(VaultFailure.CONFLICT)
    }

    @Synchronized override fun inspect(context: VaultOperationContext): VaultStoreObservation = try {
        requireOpen()
        val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
        bounded.checkActive()
        val unknown = pending
        if (unknown != null) {
            when (database.reconcile(unknown)) {
                VaultSqliteWriteResult.DURABLE_EXACT -> {
                    val media = pendingMedia
                    if (media != null && unknown.proposed.payload().contentEquals(VaultSnapshotCodec.encode(media.proposed)))
                        acceptMediaFact(media, decode(database.readExisting()))
                    pending = null; pendingContext = null
                }
                VaultSqliteWriteResult.CONFIRMED_OLD -> {
                    // An exact old credential operation with a terminal original budget can be
                    // abandoned only after permanent same-owner proof revocation. No ledger reset.
                    if (credentialWrite?.retireConfirmedOld(unknown, VaultSqliteWriteResult.CONFIRMED_OLD) == true || retireConfirmedOldMedia(unknown)) {
                        pending = null; pendingContext = null
                    }
                    // Active, non-credential and unknown operations keep the original mutation.
                }
                else -> throw VaultOperationException(VaultFailure.INCOMPLETE)
            }
        }
        if (pending == null && pendingMediaKnownDurable) {
            val media = requireNotNull(pendingMedia)
            val actual = database.readExisting()
            if (!actual.sameBytes(record(media.proposed, media.operation.operationId)))
                throw VaultOperationException(VaultFailure.INCOMPLETE)
            // An exact late durable fact is consumed without renewing the original proof,
            // writing again, or issuing any source-deletion receipt.
            acceptMediaFact(media, decode(actual))
        }
        val result = if (!database.hasFilesystemEvidence()) {
            // A missing directory with a surviving alias is not fresh setup.
            if (authentication.hasExistingVaultAliases(bounded))
                VaultStoreObservation.Unavailable(VaultFailure.INCOMPLETE)
            else VaultStoreObservation.Absent
        } else VaultStoreObservation.Present(readBounded())
        bounded.checkActive()
        result
    } catch (failure: VaultOperationException) {
        VaultStoreObservation.Unavailable(failure.category)
    } catch (_: Exception) {
        VaultStoreObservation.Unavailable(VaultFailure.CORRUPT)
    }

    @Synchronized override fun beginInitialization(identity: VaultPersistentIdentity,
                                                   context: VaultOperationContext): VaultStoredSnapshot = guard {
        requireOpen()
        VaultSnapshotCodec.validateIdentity(identity)
        val proposed = VaultStoredSnapshot(identity, VaultStorePhase.INITIALIZING, 1, null)
        val record = record(proposed, identity.initializationOperationId)
        val mutation = VaultSqliteMutation(null, record)
        // A retry of this exact unknown operation first reads it back. It never creates a new root.
        if (pending != null) return@guard finishPendingOrReject(mutation, context)
        val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
        bounded.checkActive()
        if (database.hasFilesystemEvidence() || authentication.hasExistingVaultAliases(bounded))
            throw VaultOperationException(VaultFailure.CONFLICT)
        bounded.checkActive()
        pending = mutation; pendingContext = bounded
        accept(database.initialize(mutation, bounded), mutation, bounded)
    }

    @Synchronized override fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData,
                                    context: VaultOperationContext): VaultStoredSnapshot = guard {
        val before = VaultSnapshotCodec.freeze(expected)
        require(before.phase == VaultStorePhase.INITIALIZING)
        val after = VaultSnapshotCodec.freeze(before.copy(phase = VaultStorePhase.STAGED,
            storeRevision = nextRevision(before.storeRevision), data = prepared))
        write(before, after, before.identity.initializationOperationId, context)
    }

    @Synchronized override fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification,
                                        context: VaultOperationContext): VaultStoredSnapshot = guard {
        val before = VaultSnapshotCodec.freeze(expected)
        require(before.phase == VaultStorePhase.STAGED)
        context.checkActive()
        // The injected, same crypto owner checks its private registry. Never call proof self-report.
        authentication.validateStagedVerification(before, verification)
        context.checkActive()
        val after = before.copy(phase = VaultStorePhase.READY, storeRevision = nextRevision(before.storeRevision))
        write(before, after, before.identity.initializationOperationId, context)
    }

    @Synchronized override fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite,
                                                  authenticatedLedger: VaultCiphertext,
                                                  context: VaultOperationContext): VaultStoredSnapshot = guard {
        val before = VaultSnapshotCodec.freeze(expected)
        require(before.phase == VaultStorePhase.READY)
        val data = requireNotNull(before.data)
        require(context.deadlineElapsedMillis <= write.deadline.elapsedMillis)
        context.checkActive()
        val receipt = completedReset
        if (receipt != null && receipt.matches(before, write, authenticatedLedger)) {
            val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
            bounded.checkActive()
            val actual = database.readExisting()
            if (!actual.sameBytes(receipt.proposed)) throw VaultOperationException(VaultFailure.CONFLICT)
            bounded.checkActive()
            return@guard decode(actual)
        }
        VaultRetryMutationValidation.requireValid(data.retryLedger, write, confirmedReservation)
        context.checkActive()
        // Structural SQL columns never authorize the pre-authentication reservation.
        if (authentication.authenticateLedger(before, context) != data.retryLedger)
            throw VaultOperationException(VaultFailure.CORRUPT)
        val after = VaultSnapshotCodec.freeze(before.copy(storeRevision = nextRevision(before.storeRevision),
            data = data.copy(retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger)))
        if (authentication.authenticateLedger(after, context) != write.proposedLedger)
            throw VaultOperationException(VaultFailure.CORRUPT)
        val id = write.identity
        val operation = "retry_${id.attempt.processInstance}_${id.attempt.attemptId}_${id.attempt.authEpoch}_${id.writeId}"
        val committed = write(before, after, operation, context)
        if (write.kind == VaultRetryWriteKind.RESERVATION) {
            confirmedReservation = write
            completedReset = null
            completedCredentialReset = null
        } else {
            completedReset = VaultCompletedResetReceipt(before, write, authenticatedLedger, record(committed, operation))
            confirmedReservation = null
        }
        committed
    }

    @Synchronized override fun compareAndSwapCredentials(expected: VaultStoredSnapshot, mutation: VaultCredentialMutation,
                                                        context: VaultOperationContext): VaultStoredSnapshot = guard {
        requireOpen()
        val before = VaultSnapshotCodec.freeze(expected)
        context.checkActive()
        // The same owner that created the opaque object authenticates exact snapshot and deadline.
        val proposal = authentication.validateCredentialMutation(before, mutation, context)
        require(proposal.kind != VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET)
        val after = VaultCredentialMutationValidation.requireValid(before, proposal, confirmedReservation)
        context.checkActive()
        writeCredential(before, after, proposal, mutation, null, context)
    }

    @Synchronized override fun compareAndSwapCredentialReset(expected: VaultStoredSnapshot, write: VaultRetryWrite,
                                                            authenticatedLedger: VaultCiphertext, mutation: VaultCredentialMutation,
                                                            context: VaultOperationContext): VaultStoredSnapshot = guard {
        requireOpen()
        val before = VaultSnapshotCodec.freeze(expected)
        require(before.phase == VaultStorePhase.READY && write.kind == VaultRetryWriteKind.RESET)
        require(context.deadlineElapsedMillis <= write.deadline.elapsedMillis)
        context.checkActive()
        val proposal = authentication.validateCredentialMutation(before, mutation, context)
        require(proposal.kind == VaultCredentialMutationKind.ACTIVATE_PASSWORD_RESET && proposal.attempt == write.identity.attempt)
        val receipt = completedCredentialReset
        if (receipt != null && receipt.matches(before, write, authenticatedLedger)) {
            val request = credentialWrite ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
            if (!request.matches(mutation, before, receipt.proposed, write)) throw VaultOperationException(VaultFailure.INCOMPLETE)
            request.context.checkActive()
            val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
            val actual = database.readExisting()
            if (!actual.sameBytes(receipt.proposed)) throw VaultOperationException(VaultFailure.CONFLICT)
            bounded.checkActive(); request.context.checkActive()
            return@guard decode(actual)
        }
        val data = requireNotNull(before.data)
        VaultRetryMutationValidation.requireValid(data.retryLedger, write, confirmedReservation)
        val credentialAfter = VaultCredentialMutationValidation.requireValid(before, proposal, confirmedReservation)
        if (authentication.authenticateLedger(before, context) != data.retryLedger)
            throw VaultOperationException(VaultFailure.CORRUPT)
        // The auth-owned proposal preserves the old ledger. Validate the exact ordinary RESET,
        // then freeze its sealed bytes together with those credentials before any database write.
        val after = VaultSnapshotCodec.freeze(credentialAfter.copy(data = proposal.data.copy(
            retryLedger = write.proposedLedger, retryLedgerEnvelope = authenticatedLedger)))
        if (authentication.authenticateLedger(after, context) != write.proposedLedger)
            throw VaultOperationException(VaultFailure.CORRUPT)
        context.checkActive()
        val committed = writeCredential(before, after, proposal, mutation, write, context)
        completedCredentialReset = VaultCompletedResetReceipt(before, write, authenticatedLedger,
            requireNotNull(credentialWrite).proposed)
        confirmedReservation = null
        completedReset = null
        committed
    }

    private fun writeCredential(before: VaultStoredSnapshot, after: VaultStoredSnapshot,
                                proposal: VaultCredentialMutationProposal, mutation: VaultCredentialMutation,
                                reset: VaultRetryWrite?, context: VaultOperationContext): VaultStoredSnapshot {
        val operation = if (reset == null) proposal.operationId else reset.identity.let {
            "credential_reset_${it.attempt.processInstance}_${it.attempt.attemptId}_${it.attempt.authEpoch}_${it.writeId}"
        }
        val proposed = record(after, operation)
        val request = VaultCredentialWriteReceipt.admit(credentialWrite, mutation, before, proposed, reset,
            context, pending != null).also { credentialWrite = it }
        // inspect/reconciliation may establish a durable fact, but never refresh this SQL budget.
        context.checkActive(); request.context.checkActive()
        val bounded = if (context.deadlineElapsedMillis < request.context.deadlineElapsedMillis) context else request.context
        val committed = write(before, after, operation, bounded, request)
        context.checkActive(); request.context.checkActive()
        return committed
    }

    private fun write(before: VaultStoredSnapshot, after: VaultStoredSnapshot, operation: String,
                      context: VaultOperationContext, credentialReceipt: VaultCredentialWriteReceipt? = null,
                      onKnownDurable: (() -> Unit)? = null): VaultStoredSnapshot {
        requireOpen()
        val proposed = record(after, operation)
        val unknown = pending
        if (unknown != null) {
            if (!unknown.proposed.sameBytes(proposed) || unknown.expected == null ||
                !unknown.expected.payload().contentEquals(VaultSnapshotCodec.encode(before)))
                throw VaultOperationException(VaultFailure.INCOMPLETE)
            credentialReceipt?.bindMutation(unknown)
            return finishPendingOrReject(unknown, context)
        }
        val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
        bounded.checkActive()
        val current = database.readExisting()
        val expectedBytes = VaultSnapshotCodec.encode(before)
        if (current.sameBytes(proposed)) {
            onKnownDurable?.invoke()
            // Idempotent same-operation durable replay; return the actual persisted object.
            bounded.checkActive()
            return decode(current)
        }
        if (current.revision != before.storeRevision || current.phase != before.phase.ordinal + 1 ||
            !current.payload().contentEquals(expectedBytes)) throw VaultOperationException(VaultFailure.CONFLICT)
        val mutation = VaultSqliteMutation(current, proposed)
        credentialReceipt?.bindMutation(mutation)
        pending = mutation; pendingContext = bounded
        return accept(database.compareAndSwap(mutation, bounded), mutation, bounded)
    }

    private fun finishPendingOrReject(mutation: VaultSqliteMutation, caller: VaultOperationContext): VaultStoredSnapshot {
        caller.checkActive()
        val actual = pending ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (!actual.proposed.sameBytes(mutation.proposed) ||
            (actual.expected == null) != (mutation.expected == null) ||
            (actual.expected != null && !actual.expected.sameBytes(mutation.expected!!)))
            throw VaultOperationException(VaultFailure.INCOMPLETE)
        // Reconciliation may establish a persisted fact after deadline, but never extends the write.
        val status = database.reconcile(actual)
        val original = pendingContext ?: throw VaultOperationException(VaultFailure.INCOMPLETE)
        if (status == VaultSqliteWriteResult.CONFIRMED_OLD) {
            original.checkActive()
            caller.checkActive()
            if (actual.expected == null) throw VaultOperationException(VaultFailure.INCOMPLETE)
            return accept(database.compareAndSwap(actual, original), actual, original)
        }
        return accept(status, actual, original)
    }

    private fun accept(status: VaultSqliteWriteResult, mutation: VaultSqliteMutation,
                       original: VaultOperationContext): VaultStoredSnapshot {
        when (status) {
            VaultSqliteWriteResult.DURABLE_EXACT -> {
                val actual = database.readExisting()
                if (!actual.sameBytes(mutation.proposed)) throw VaultOperationException(VaultFailure.INCOMPLETE)
                // Late/cancelled readback cannot be returned as this authentication's durable ACK.
                original.checkActive()
                val decoded = decode(actual)
                pending = null; pendingContext = null
                return decoded
            }
            VaultSqliteWriteResult.CONFIRMED_OLD -> throw VaultOperationException(VaultFailure.INCOMPLETE)
            VaultSqliteWriteResult.CONFLICT -> {
                pending = null; pendingContext = null
                throw VaultOperationException(VaultFailure.CONFLICT)
            }
            VaultSqliteWriteResult.DEADLINE -> throw VaultOperationException(VaultFailure.DEADLINE)
            VaultSqliteWriteResult.UNKNOWN -> throw VaultOperationException(VaultFailure.INCOMPLETE)
            VaultSqliteWriteResult.UNAVAILABLE -> throw VaultOperationException(VaultFailure.UNAVAILABLE)
        }
    }

    private fun readBounded(): VaultStoredSnapshot = decode(database.readExisting())
    private fun decode(record: VaultSqliteRecord): VaultStoredSnapshot = VaultSnapshotCodec.decode(record.payload()).also {
        require(it.storeRevision == record.revision && it.phase.ordinal + 1 == record.phase)
    }
    private fun record(snapshot: VaultStoredSnapshot, operation: String) =
        VaultSqliteRecord(snapshot.storeRevision, operation, snapshot.phase.ordinal + 1, VaultSnapshotCodec.encode(snapshot))
    private fun nextRevision(current: Long): Long { require(current < Long.MAX_VALUE - 1); return current + 1 }
    private fun requireOpen() { if (closed) throw VaultOperationException(VaultFailure.UNAVAILABLE) }
    private inline fun <T> guard(block: () -> T): T = try { block() }
        catch (failure: VaultOperationException) { throw failure }
        catch (_: IllegalArgumentException) { throw VaultOperationException(VaultFailure.CORRUPT) }
        catch (_: Exception) { throw VaultOperationException(VaultFailure.UNAVAILABLE) }

    @Synchronized override fun close() {
        if (closed) return
        if (mediaFiles.closeAll() != ExportRelease.RELEASED) throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
        try { credentialWrite?.retire() } catch (_: Exception) { throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN) }
        try { database.close() } catch (_: Exception) { throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN) }
        closed = true
        credentialWrite = null
        completedCredentialReset = null
    }
}

/** Pure shape gate. Device-AEAD authentication remains mandatory in the production adapter. */
internal object VaultRetryMutationValidation {
    fun requireValid(stored: VaultRetryLedger, write: VaultRetryWrite, confirmedReservation: VaultRetryWrite? = null) {
        VaultSnapshotCodec.validateLedger(stored)
        VaultSnapshotCodec.validateLedger(write.proposedLedger)
        require(write.matchesStoredRevision(stored.ledgerRevision))
        val id = write.identity
        require(id.writeId > 0 && id.attempt.processInstance > 0 && id.attempt.attemptId > 0 && id.attempt.authEpoch >= 0)
        require(write.deadline.processInstance == id.attempt.processInstance && write.deadline.elapsedMillis > 0)
        val proposed = write.proposedLedger
        when (write.kind) {
            VaultRetryWriteKind.RESERVATION -> {
                require(stored.reservedAttemptCount < Long.MAX_VALUE - 1)
                require(proposed.reservedAttemptCount == stored.reservedAttemptCount + 1)
                require(proposed.outstandingReservationId == id.writeId)
            }
            VaultRetryWriteKind.RESET -> {
                val reservation = requireNotNull(confirmedReservation)
                require(reservation.kind == VaultRetryWriteKind.RESERVATION)
                require(reservation.identity.attempt == id.attempt && reservation.proposedLedger == stored)
                require(stored.outstandingReservationId == reservation.identity.writeId)
                require(proposed.reservedAttemptCount == 0L && proposed.delayTier == -1 && proposed.outstandingReservationId == null)
            }
        }
    }
}

/** Single exact completed reset receipt permits read-only ACK replay, never a new reset write. */
internal class VaultCompletedResetReceipt(
    expected: VaultStoredSnapshot,
    private val write: VaultRetryWrite,
    private val sealedLedger: VaultCiphertext,
    val proposed: VaultSqliteRecord,
) {
    private val before = VaultSnapshotCodec.encode(expected)
    init { require(write.kind == VaultRetryWriteKind.RESET) }
    fun matches(expected: VaultStoredSnapshot, candidate: VaultRetryWrite, envelope: VaultCiphertext): Boolean =
        candidate == write && envelope == sealedLedger && VaultSnapshotCodec.encode(expected).contentEquals(before)
    override fun toString(): String = "VaultCompletedResetReceipt(redacted)"
}


/** Pure shape predicates only. Production feeds them exclusively from the store's private CAS ledger. */
internal object VaultPhoneStoreFacts {
    private val aborted = setOf(VaultCopyBatchOutcome.FAILED_NO_OUTPUT, VaultCopyBatchOutcome.FAILED_CLEANED,
        VaultCopyBatchOutcome.CANCELLED_BEFORE_START, VaultCopyBatchOutcome.CANCELLED_CLEANED,
        VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
    private fun current(journal: VaultMediaJournal): Boolean {
        if (journal.operation.kind != VaultMediaKind.COPY_OUT) return false
        val batch = journal.batch ?: return true
        val row = batch.items[batch.activeIndex]
        return row.operationId == journal.operation.operationId && row.activatedSequence == journal.operation.sequence &&
            row.identity == journal.objectIdentity && (row.target == null || row.target == journal.target)
    }
    fun tree(journal: VaultMediaJournal?, record: VaultPhoneTreeRecord): Boolean {
        val batch = journal?.batch ?: return false
        return current(journal) && batch.tree == record && batch.batchId == record.batchId &&
            batch.status !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED)
    }
    fun create(journal: VaultMediaJournal?, tree: VaultPhoneTreeRecord, reservation: VaultMediaTargetRecord,
        operation: VaultMediaOperation): Boolean {
        val active = journal ?: return false
        val batch = active.batch ?: return false
        return tree(active, tree) && current(active) && active.operation == operation && active.target == reservation &&
            active.phase == VaultMediaPhase.CHILD_CREATE_INTENT && !active.cancelRequested && reservation.uri == null &&
            reservation.flags == tree.flags && listOf(reservation.operationId, reservation.grantId, reservation.nonce)
                .all { it.matches(Regex("[a-f0-9]{32}")) } &&
            batch.status == VaultCopyBatchStatus.RUNNING && batch.stop == VaultCopyBatchStop.NONE &&
            batch.runCount in 1..VaultCopyBatchLimits.MAX_RUN_STARTS &&
            batch.items[batch.activeIndex].outcome == VaultCopyBatchOutcome.ACTIVE &&
            batch.items.take(batch.activeIndex).none { it.target?.let { target ->
                target.operationId == reservation.operationId || target.nonce == reservation.nonce || target.grantId == reservation.grantId
            } == true }
    }
    fun targetUse(journal: VaultMediaJournal?, record: VaultMediaTargetRecord, use: VaultPhoneTargetUse): Boolean {
        val active = journal ?: return false
        if (!current(active) || active.target != record || record.uri == null) return false
        val batch = active.batch
        if (batch != null) {
            val row = batch.items[batch.activeIndex]
            if (row.outcome in aborted || row.outcome == VaultCopyBatchOutcome.VERIFIED_COPY && use != VaultPhoneTargetUse.PUBLISH) return false
            if (use != VaultPhoneTargetUse.CLEANUP && (batch.stop != VaultCopyBatchStop.NONE || batch.status != VaultCopyBatchStatus.RUNNING) &&
                active.phase !in setOf(VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE)) return false
        }
        return when (use) {
            VaultPhoneTargetUse.WRITE -> active.phase == VaultMediaPhase.COPYING && !active.cancelRequested
            VaultPhoneTargetUse.READBACK -> active.phase == VaultMediaPhase.VERIFYING && !active.cancelRequested
            VaultPhoneTargetUse.PUBLISH -> active.phase in setOf(VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE) && !active.cancelRequested
            VaultPhoneTargetUse.CLEANUP -> cleanup(active, record)
        }
    }
    fun cleanup(journal: VaultMediaJournal?, record: VaultMediaTargetRecord): Boolean {
        val active = journal ?: return false
        if (!current(active) || active.operation.operationId != record.operationId ||
            active.phase in setOf(VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE, VaultMediaPhase.OUTPUT_RETAINED)) return false
        if (active.batch != null) {
            val row = active.batch.items[active.batch.activeIndex]
            if (row.outcome in aborted || row.outcome == VaultCopyBatchOutcome.VERIFIED_COPY) return false
            return active.target == record
        }
        return active.target == null || active.target == record
    }
    fun retirement(journal: VaultMediaJournal?, operationId: String, target: VaultMediaTargetRecord, batchId: String?): Boolean {
        val active = journal ?: return false
        if (target.operationId != operationId || active.operation.kind != VaultMediaKind.COPY_OUT) return false
        val batch = active.batch
        if (batch == null) return batchId == null && current(active) && active.operation.operationId == operationId &&
            active.target == target && (active.phase == VaultMediaPhase.OUTPUT_RETAINED && active.cancelRequested ||
                active.phase == VaultMediaPhase.COMPLETE && !active.cancelRequested ||
                active.phase == VaultMediaPhase.CANCELLED && active.cancelRequested)
        if (batch.batchId != batchId) return false
        val index = batch.items.indexOfFirst { it.operationId == operationId }
        if (index < 0 || index > batch.activeIndex) return false
        val row = batch.items[index]
        if (row.target != target || row.activatedSequence == null) return false
        if (index < batch.activeIndex) return row.outcome == VaultCopyBatchOutcome.VERIFIED_COPY || row.outcome in aborted
        if (!current(active) || active.target != target) return false
        return row.outcome == VaultCopyBatchOutcome.VERIFIED_COPY && active.phase == VaultMediaPhase.COMPLETE && !active.cancelRequested ||
            row.outcome in aborted && active.phase == VaultMediaPhase.CANCELLED && active.cancelRequested
    }
    fun requireBatchShape(journal: VaultMediaJournal) {
        val batch = journal.batch ?: return
        require(current(journal) && journal.source == null && journal.ciphertextEvidence == null)
        require(batch.tree == null || batch.tree.batchId == batch.batchId)
        require(batch.items.take(batch.activeIndex).all { it.outcome == VaultCopyBatchOutcome.VERIFIED_COPY || it.outcome in aborted })
        if (journal.phase == VaultMediaPhase.CHILD_CREATE_INTENT) {
            require(journal.target != null && journal.target.uri == null)
            require(batch.tree != null && batch.items[batch.activeIndex].outcome == VaultCopyBatchOutcome.ACTIVE)
        }
        if (journal.phase == VaultMediaPhase.COMPLETE) require(batch.items[batch.activeIndex].outcome == VaultCopyBatchOutcome.VERIFIED_COPY)
        if (journal.phase == VaultMediaPhase.CANCELLED) require(batch.items[batch.activeIndex].outcome in aborted)
        require(journal.phase != VaultMediaPhase.OUTPUT_RETAINED)
    }
}
