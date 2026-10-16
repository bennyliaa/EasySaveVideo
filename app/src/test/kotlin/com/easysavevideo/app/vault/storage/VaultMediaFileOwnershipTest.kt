package com.easysavevideo.app.vault.storage

import com.easysavevideo.app.vault.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed pure facts. No Android filesystem, SQLite, AEAD, device, or credentials. */
class VaultMediaFileOwnershipTest {
    private val operation = VaultMediaOperation("operation-one", 1, VaultMediaKind.MOVE_IN)
    private val identity = VaultMediaObjectIdentity("object-one", 1)
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
    @Test fun absentPreflightAloneDoesNotAuthorizeCreateOrCleanup() {
        val owner = VaultMediaFileOwnership()
        owner.preflight(operation, identity) { true }
        rejected { owner.requireOwned(operation, identity) }
        owner.durable(operation, identity)
        owner.requireOwned(operation, identity)
    }
    @Test fun initialCollisionCannotBecomeOwnedViaAbandonOrRestore() {
        val owner = VaultMediaFileOwnership()
        rejected { owner.preflight(operation, identity) { false } }
        rejected { owner.restoreAuthenticated(operation, identity) }
        rejected { owner.preflight(operation, identity) { true } }
    }
    @Test fun unexpectedPostReservationCollisionPermanentlyIsolatesIdentity() {
        val owner = VaultMediaFileOwnership()
        owner.preflight(operation, identity) { true }; owner.durable(operation, identity)
        owner.collision(identity)
        rejected { owner.requireOwned(operation, identity) }
        rejected { owner.retired(operation, identity) }
        rejected { owner.restoreAuthenticated(operation, identity) }
    }
    @Test fun anotherOperationCannotAdoptExactReservedObject() {
        val owner = VaultMediaFileOwnership()
        owner.preflight(operation, identity) { true }; owner.durable(operation, identity)
        val other = VaultMediaOperation("operation-two", 2, VaultMediaKind.MOVE_IN)
        rejected { owner.restoreAuthenticated(other, identity) }
        rejected { owner.requireOwned(other, identity) }
        rejected { owner.preflight(other, identity) { true } }
    }
    @Test fun explicitAuthenticatedRecoveryRestoresOnlyExactReservation() {
        val owner = VaultMediaFileOwnership()
        owner.restoreAuthenticated(operation, identity)
        owner.requireOwned(operation, identity)
        rejected { owner.requireOwned(operation, identity.copy(generation = 2)) }
        owner.retired(operation, identity)
        rejected { owner.requireOwned(operation, identity) }
    }
    @Test fun unknownOwnersAreNeverEvictedToAdmitMoreWork() {
        val owner = VaultMediaFileOwnership()
        repeat(VaultMediaFileOwnership.MAX_OWNERS) { i -> owner.restoreAuthenticated(operation, VaultMediaObjectIdentity("object-$i", 1)) }
        rejected { owner.restoreAuthenticated(operation, VaultMediaObjectIdentity("overflow", 1)) }
        owner.requireOwned(operation, VaultMediaObjectIdentity("object-0", 1))
    }
}
