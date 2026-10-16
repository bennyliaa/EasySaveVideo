package com.easysavevideo.app.vault.storage

import android.content.Context
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
) : VaultStorePort {
    private val database = AndroidVaultDatabase(context)
    private var pending: VaultSqliteMutation? = null
    private var pendingContext: VaultOperationContext? = null
    private var closed = false
    private var confirmedReservation: VaultRetryWrite? = null
    private var completedReset: VaultCompletedResetReceipt? = null
    private var credentialWrite: VaultCredentialWriteReceipt? = null
    private var completedCredentialReset: VaultCompletedResetReceipt? = null

    @Synchronized override fun inspect(context: VaultOperationContext): VaultStoreObservation = try {
        requireOpen()
        val bounded = context.bounded(VaultRuntimeLimits.DATABASE_MILLIS)
        bounded.checkActive()
        val unknown = pending
        if (unknown != null) {
            when (database.reconcile(unknown)) {
                VaultSqliteWriteResult.DURABLE_EXACT -> { pending = null; pendingContext = null }
                VaultSqliteWriteResult.CONFIRMED_OLD -> {
                    // An exact old credential operation with a terminal original budget can be
                    // abandoned only after permanent same-owner proof revocation. No ledger reset.
                    if (credentialWrite?.retireConfirmedOld(unknown, VaultSqliteWriteResult.CONFIRMED_OLD) == true) {
                        pending = null; pendingContext = null
                    }
                    // Active, non-credential and unknown operations keep the original mutation.
                }
                else -> throw VaultOperationException(VaultFailure.INCOMPLETE)
            }
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
                      context: VaultOperationContext, credentialReceipt: VaultCredentialWriteReceipt? = null): VaultStoredSnapshot {
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
                pending = null; pendingContext = null
                return decode(actual)
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
