package com.easysavevideo.app.vault

import org.junit.Assert.*
import org.junit.Test

/** Calls the production final presenter predicate and exact atomic host token, with fixed identities. */
class VaultPlaybackPublicationTest {
    @Test fun initialViewRequiresTheExactReservedTokenAndCurrentGeneration() {
        val token = VaultPlaybackHostToken(4) { true }; val ticket = VaultPlaybackTicket(token)
        assertTrue(vaultPlaybackViewMatchesReservation(ticket, null, token, 4, VaultPlaybackStatus.PREPARING))
        assertFalse(vaultPlaybackViewMatchesReservation(ticket, null, token, 5, VaultPlaybackStatus.PREPARING))
        assertFalse(vaultPlaybackViewMatchesReservation(ticket, null, VaultPlaybackHostToken(4) { true }, 4, VaultPlaybackStatus.READY))
    }
    @Test fun retirementBeforeFirstPreparingPublicationCanStillPresentSafeWarning() {
        val token = VaultPlaybackHostToken(4) { true }; val ticket = VaultPlaybackTicket(token)
        token.revoke()
        assertTrue(vaultPlaybackViewMatchesReservation(ticket, null, token, 5, VaultPlaybackStatus.RETIRING))
        assertTrue(vaultPlaybackViewMatchesReservation(ticket, null, token, 5, VaultPlaybackStatus.UNAVAILABLE))
        assertFalse(vaultPlaybackViewMatchesReservation(ticket, null, token, 5, VaultPlaybackStatus.READY))
    }
    @Test fun anOldTerminalCallbackCannotOverwriteAReplacementReservationOrTicket() {
        val old = VaultPlaybackHostToken(4) { true }; val oldTicket = VaultPlaybackTicket(old)
        val next = VaultPlaybackHostToken(5) { true }; val nextTicket = VaultPlaybackTicket(next)
        old.revoke()
        assertFalse(vaultPlaybackViewMatchesReservation(oldTicket, null, next, 5, VaultPlaybackStatus.UNAVAILABLE))
        assertFalse(vaultPlaybackViewMatchesReservation(oldTicket, nextTicket, next, 5, VaultPlaybackStatus.RETIRING))
        assertTrue(vaultPlaybackViewMatchesReservation(nextTicket, null, next, 5, VaultPlaybackStatus.PREPARING))
        // IDLE for the retired old owner can be suppressed if the new owner already reserved.
        assertTrue(vaultPlaybackViewMatchesReservation(nextTicket, oldTicket, next, 5, VaultPlaybackStatus.PREPARING))
        assertTrue(vaultPlaybackViewMatchesReservation(nextTicket, oldTicket, next, 5, VaultPlaybackStatus.UNAVAILABLE))
    }
    @Test fun matchingFieldsDoNotReplaceAnAlreadyRenderedExactTicket() {
        val token = VaultPlaybackHostToken(4) { true }; val exact = VaultPlaybackTicket(token)
        assertFalse(vaultPlaybackViewMatchesReservation(VaultPlaybackTicket(token), exact, token, 4, VaultPlaybackStatus.UNAVAILABLE))
    }
    @Test fun revocationCannotReviveAndCannotRevokeTheNextIndependentOpen() {
        var current = true
        val old = VaultPlaybackHostToken(4) { current }; val next = VaultPlaybackHostToken(4) { current }
        old.revoke(); current = false; current = true; old.revoke()
        assertFalse(old.isCurrent()); assertTrue(next.isCurrent())
    }
}
