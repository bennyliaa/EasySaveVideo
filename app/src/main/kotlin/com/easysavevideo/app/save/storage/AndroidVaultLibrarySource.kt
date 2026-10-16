package com.easysavevideo.app.save.storage

import android.os.Handler
import android.os.Looper
import com.easysavevideo.app.library.storage.AndroidLibraryRepository
import com.easysavevideo.app.vault.*
import com.easysavevideo.library.DeleteOutcome
import com.easysavevideo.library.LibraryError
import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.export.ExportInput
import com.easysavevideo.library.export.ExportRelease
import java.util.IdentityHashMap

/** One bridge, owned by AndroidSaveStorage. Neither receipts nor reservations self-authorize. */
internal class AndroidVaultLibrarySource(
    private val repository: AndroidLibraryRepository,
    private val store: VaultStorePort,
    private val open: (AndroidLibraryRepository.ReadAuthority) -> AndroidSaveStorage.LibraryMediaInput,
    private val retireUi: (MediaReadRef, () -> Unit) -> Unit,
    private val retireShares: (MediaReadRef) -> Boolean,
) : VaultMediaSourcePort {
    private val lock = Any()
    private val outstandingResources = java.util.concurrent.atomic.AtomicInteger()
    override fun observeMediaDrain(): ExportRelease = if (outstandingResources.get() == 0) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
    private val main = Handler(Looper.getMainLooper())
    private inner class Reservation(val intent: AndroidLibraryRepository.VaultMoveIntent,
        val operation: VaultMediaOperation, val absentIntent: Boolean = false) : VaultMoveReservation {
        var presentation: Retired? = null
        var retirementGeneration = 0L
        var source: Source? = null
        var deleted: Deleted? = null
        var restored = false
        override fun close() {
            if (synchronized(lock) { source }?.input?.close() == ExportRelease.UNCONFIRMED)
                throw VaultOperationException(VaultFailure.RELEASE_UNKNOWN)
        }
    }
    private class Retired(val owner: VaultMoveReservation) : VaultOrdinaryRetirementReceipt
    private class Deleted(val owner: VaultMoveReservation) : VaultSourceDeletedReceipt
    private val reservations = IdentityHashMap<VaultMoveReservation, Reservation>()
    private val deleted = IdentityHashMap<VaultSourceDeletedReceipt, Reservation>()
    private val byOperation = mutableMapOf<String, Reservation>()

    override fun reserve(ref: MediaReadRef, operation: VaultMediaOperation): VaultMoveReservation {
        require(operation.kind == VaultMediaKind.MOVE_IN)
        synchronized(lock) { byOperation[operation.operationId]?.let {
            require(it.intent.source == ref && it.operation == operation)
            return it
        } }
        synchronized(lock) {
            val retired = byOperation.values.filter { it.deleted != null || it.restored }
            retired.forEach { old ->
                byOperation.remove(old.operation.operationId); reservations.remove(old)
                old.deleted?.let(deleted::remove)
            }
            require(byOperation.size < 32)
        }
        val intent = repository.reserveVaultMove(ref, operation.operationId)
        return synchronized(lock) {
            byOperation[operation.operationId]?.also { require(it.intent == intent && it.operation == operation) }
                ?: Reservation(intent, operation).also { byOperation[operation.operationId] = it; reservations[it] = it }
        }
    }

    override fun reserveForReconciliation(ref: MediaReadRef, operation: VaultMediaOperation): VaultMoveReservation {
        require(operation.kind == VaultMediaKind.MOVE_IN)
        synchronized(lock) { byOperation[operation.operationId]?.let { require(it.operation == operation && it.intent.source == ref); return it } }
        val (intent, absent) = repository.reconcileVaultMove(ref, operation.operationId)
        return synchronized(lock) {
            require(byOperation.size < 32)
            byOperation[operation.operationId]?.also { require(it.intent == intent && it.operation == operation) }
                ?: Reservation(intent, operation, absent).also { byOperation[operation.operationId] = it; reservations[it] = it }
        }
    }

    override fun retirePresentation(reservation: VaultMoveReservation, onRetired: (VaultOrdinaryRetirementReceipt) -> Unit) {
        val owned = owned(reservation)
        require(!owned.absentIntent)
        val (known, generation) = synchronized(lock) {
            if (owned.presentation != null) owned.presentation to owned.retirementGeneration
            else { require(owned.retirementGeneration < Long.MAX_VALUE); owned.retirementGeneration++; null to owned.retirementGeneration }
        }
        if (known != null) { onRetired(known); return }
        // A replacement host may reattach the observer. An obsolete host's late ACK cannot
        // satisfy the newest request; no source, store, or native lock spans this callback.
        check(main.post {
            retireUi(owned.intent.source) {
                val receipt = synchronized(lock) {
                    if (owned.retirementGeneration != generation) null
                    else owned.presentation ?: Retired(owned).also { owned.presentation = it }
                }
                if (receipt != null) onRetired(receipt)
            }
        })
    }

    override fun acquire(reservation: VaultMoveReservation, retired: VaultOrdinaryRetirementReceipt): VaultMoveSourceCapability {
        val owned = owned(reservation)
        require(!owned.absentIntent)
        synchronized(lock) {
            require(owned.presentation === retired)
            if (owned.source?.released == false) throw LibraryFailure(LibraryError.BUSY)
        }
        val (item, authority) = repository.acquireVaultMove(owned.intent)
        outstandingResources.incrementAndGet()
        val input = try { open(authority) } catch (failure: Exception) { authority.lease.close(); throw failure }
        val source = Source(input)
        synchronized(lock) { owned.source = source }
        try {
            source.bindMetadata(VaultMediaSourceEvidence(owned.intent.source, ExportEvidence(input.sizeBytes, input.sha256),
                item.displayName, item.durationMillis, item.width, item.height))
        } catch (failure: Exception) {
            source.input.close() // The registered source remains strongly held if actual FD close is unknown.
            throw failure
        }
        // Source writer exclusion is now held. Any older share prepare has finished registering
        // its copy before relinquishing its read lease, so this exact revoke cannot miss that gap.
        if (!retireShares(owned.intent.source)) {
            source.input.close()
            throw LibraryFailure(LibraryError.BUSY)
        }
        return source
    }

    override fun cleanup(reservation: VaultMoveReservation, receipt: CommittedMoveReceipt): VaultSourceCleanupResult {
        val owned = owned(reservation)
        require(!owned.absentIntent)
        // Bounded same-store registry validation runs OUTSIDE the ordinary owner monitor.
        if (!store.validateCommittedMove(receipt, owned.intent.source, owned.operation))
            throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        synchronized(lock) {
            if (owned.presentation == null || owned.source?.released == false)
                return VaultSourceCleanupResult(DeleteOutcome.DELETE_PENDING, null)
            owned.deleted?.let { return VaultSourceCleanupResult(DeleteOutcome.DELETED, it) }
        }
        if (!retireShares(owned.intent.source)) return VaultSourceCleanupResult(DeleteOutcome.DELETE_PENDING, null)
        val outcome = repository.cleanupVaultMove(owned.intent)
        if (outcome != DeleteOutcome.DELETED) return VaultSourceCleanupResult(outcome, null)
        val proof = synchronized(lock) {
            owned.deleted ?: Deleted(owned).also { owned.deleted = it; deleted[it] = owned }
        }
        return VaultSourceCleanupResult(outcome, proof)
    }

    override fun cancelUncommitted(reservation: VaultMoveReservation, receipt: VaultUncommittedMoveReceipt): Boolean {
        val owned = owned(reservation)
        if (!store.validateUncommittedMove(receipt, owned.intent.source, owned.operation))
            throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        if (synchronized(lock) { owned.source?.released == false }) return false
        val restored = if (owned.absentIntent) repository.cancelAbsentVaultMove(owned.intent) else repository.cancelVaultMove(owned.intent)
        if (restored) synchronized(lock) { owned.restored = true }
        return restored
    }

    override fun validateSourceRestored(ref: MediaReadRef, operation: VaultMediaOperation): Boolean = synchronized(lock) {
        byOperation[operation.operationId]?.let { it.operation == operation && it.intent.source == ref && it.restored && it.source?.released != false } == true
    }

    override fun validateSourceDeleted(receipt: VaultSourceDeletedReceipt, ref: MediaReadRef, operation: VaultMediaOperation): Boolean =
        synchronized(lock) { deleted[receipt]?.let { it.intent.source == ref && it.operation == operation && it.deleted === receipt } == true }

    private fun owned(reservation: VaultMoveReservation): Reservation = synchronized(lock) {
        reservations[reservation] ?: throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
    }
    private inner class Source(private val opened: AndroidSaveStorage.LibraryMediaInput) : VaultMoveSourceCapability {
        private var evidence: VaultMediaSourceEvidence? = null
        override val metadata: VaultMediaSourceEvidence get() = requireNotNull(evidence)
        fun bindMetadata(value: VaultMediaSourceEvidence) { require(evidence == null); evidence = value }
        @Volatile var released = false; private set
        override val input: ExportInput = object : ExportInput {
            override val stream = opened.stream
            private val closeOwner = com.easysavevideo.app.vault.storage.VaultCloseOnce({ opened.close() }, { !opened.stream.fd.valid() }, allowObservationAfterCloseFailure = true)
            @Synchronized override fun close(): ExportRelease {
                val result = closeOwner.closeAndObserve()
                if (!released && result == ExportRelease.RELEASED) { released = true; outstandingResources.decrementAndGet() }
                return result
            }
        }
    }
}
