package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.VaultMediaObjectIdentity
import com.easysavevideo.app.vault.VaultMediaOperation

/** Necessary ownership checks; only the store may supply durable same-auth reservation evidence. */
internal class VaultMediaFileOwnership {
    private data class Reservation(val operation: VaultMediaOperation, var durable: Boolean)
    private val reservations = mutableMapOf<VaultMediaObjectIdentity, Reservation>()
    private val collisions = mutableSetOf<VaultMediaObjectIdentity>()
    fun preflight(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity, absent: () -> Boolean) {
        require(identity !in collisions)
        val prior = reservations[identity]
        require(prior == null || (prior.operation == operation && !prior.durable))
        require(reservations.size < MAX_OWNERS || prior != null)
        if (!absent()) { collision(identity); error("VAULT_MEDIA_COLLISION") }
        reservations[identity] = Reservation(operation, false)
    }
    fun durable(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) {
        val reservation = requireNotNull(reservations[identity])
        require(reservation.operation == operation && identity !in collisions)
        reservation.durable = true
    }
    /** Call only after authentication proves the exact persisted initial name reservation lineage. */
    fun restoreAuthenticated(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) {
        require(identity !in collisions)
        val prior = reservations[identity]
        require(prior == null || prior.operation == operation)
        require(reservations.size < MAX_OWNERS || prior != null)
        reservations[identity] = Reservation(operation, true)
    }
    fun requireOwned(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) {
        val reservation = requireNotNull(reservations[identity])
        require(identity !in collisions && reservation.durable && reservation.operation == operation)
    }
    fun collision(identity: VaultMediaObjectIdentity) {
        require(collisions.size < MAX_OWNERS || identity in collisions)
        collisions.add(identity)
    }
    fun retired(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity) {
        requireOwned(operation, identity)
        reservations.remove(identity)
        // Collisions are permanent for this owner. Never turn a conflict into cleanup authority.
    }
    companion object { const val MAX_OWNERS = 32 }
}
