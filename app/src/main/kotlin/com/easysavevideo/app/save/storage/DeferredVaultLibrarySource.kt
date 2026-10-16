package com.easysavevideo.app.save.storage

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.MediaReadRef

/** Stable issuer at Activity construction; resolves the existing Save owner on first worker use. */
internal class DeferredVaultLibrarySource(private val resolve: () -> VaultMediaSourcePort) : VaultMediaSourcePort {
    @Volatile private var delegate: VaultMediaSourcePort? = null
    @Synchronized private fun resolved(): VaultMediaSourcePort = delegate ?: resolve().also { delegate = it }
    private fun existing(): VaultMediaSourcePort = delegate ?: throw VaultOperationException(VaultFailure.UNAVAILABLE)
    override fun observeMediaDrain() = delegate?.observeMediaDrain() ?: com.easysavevideo.library.export.ExportRelease.RELEASED
    override fun reserve(ref: MediaReadRef, operation: VaultMediaOperation) = resolved().reserve(ref, operation)
    override fun reserveForReconciliation(ref: MediaReadRef, operation: VaultMediaOperation) = resolved().reserveForReconciliation(ref, operation)
    override fun retirePresentation(reservation: VaultMoveReservation, onRetired: (VaultOrdinaryRetirementReceipt) -> Unit) =
        existing().retirePresentation(reservation, onRetired)
    override fun acquire(reservation: VaultMoveReservation, retired: VaultOrdinaryRetirementReceipt) = existing().acquire(reservation, retired)
    override fun cleanup(reservation: VaultMoveReservation, receipt: CommittedMoveReceipt) = existing().cleanup(reservation, receipt)
    override fun cancelUncommitted(reservation: VaultMoveReservation, receipt: VaultUncommittedMoveReceipt) = existing().cancelUncommitted(reservation, receipt)
    override fun validateSourceRestored(ref: MediaReadRef, operation: VaultMediaOperation): Boolean =
        delegate?.validateSourceRestored(ref, operation) == true
    override fun validateSourceDeleted(receipt: VaultSourceDeletedReceipt, ref: MediaReadRef, operation: VaultMediaOperation): Boolean =
        delegate?.validateSourceDeleted(receipt, ref, operation) == true
}
