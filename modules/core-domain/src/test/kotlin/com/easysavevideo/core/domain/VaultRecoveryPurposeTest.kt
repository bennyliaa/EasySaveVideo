package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class VaultRecoveryPurposeTest {
    @Test fun recoveryPurposeSurvivesReservationAndResetAcknowledgementNeverOpensSession() {
        val f = VaultSessionFixture(); f.ready(); f.send(VaultEvent.RecoveryResetRequested)
        assertEquals(VaultAttemptPurpose.RECOVERY_RESET, f.state.activeAttempt!!.purpose)
        f.stored(); assertEquals(VaultAttemptPurpose.RECOVERY_RESET, f.state.activeAttempt!!.purpose)
        assertFalse(f.state.permitsPresentation(VaultTick(11, 0), f.state.authEpoch, f.state.presentationEpoch))
        f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.RECOVERY_RESET_VERIFIED))
        assertEquals(VaultRetryWriteKind.RESET, f.state.pendingRetryWrite!!.kind)
        assertNotEquals(VaultMode.SESSION_OPEN, f.state.mode)
        f.stored(); assertEquals(VaultMode.LOCKED, f.state.mode); assertNull(f.state.activeAttempt)
        f.cleanup(); f.send(VaultEvent.SurfaceVisible)
        assertEquals(VaultMode.LOCKED, f.state.mode)
        assertFalse(f.state.permitsPresentation(VaultTick(11, 0), f.state.authEpoch, f.state.presentationEpoch))
    }
    @Test fun bothPurposeOutcomeMismatchesCannotPrepareReset() {
        for (recovery in listOf(false, true)) {
            val f = VaultSessionFixture(); f.ready()
            f.send(if (recovery) VaultEvent.RecoveryResetRequested else VaultEvent.UnlockRequested); f.stored()
            val identity = f.state.activeAttempt!!.identity
            val outcome = if (recovery) VaultAuthenticationOutcome.SESSION_ESTABLISHED else VaultAuthenticationOutcome.RECOVERY_RESET_VERIFIED
            f.send(VaultEvent.AuthenticationResult(identity, outcome))
            assertNull(f.state.pendingRetryWrite); assertNotEquals(VaultMode.SESSION_OPEN, f.state.mode)
            assertTrue(f.transition.effects.any { it is VaultEffect.DiscardAttemptResult })
        }
    }
    @Test fun lateReservationAndResetAcknowledgementsAfterLockCannotOpenRecoverySession() {
        for (afterAuthentication in listOf(false, true)) {
            val f = VaultSessionFixture(); f.ready(); f.send(VaultEvent.RecoveryResetRequested)
            if (afterAuthentication) {
                f.stored(); f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity,
                    VaultAuthenticationOutcome.RECOVERY_RESET_VERIFIED))
            }
            val write = f.state.pendingRetryWrite!!
            f.send(VaultEvent.ExplicitLock)
            f.send(VaultEvent.RetryWriteStored(write.identity, write.expectedLedgerRevision, write.proposedLedger.ledgerRevision))
            assertEquals(VaultMode.LOCKED, f.state.mode); assertNull(f.state.activeAttempt)
            assertFalse(f.state.permitsPresentation(VaultTick(11, 0), f.state.authEpoch, f.state.presentationEpoch))
        }
    }
    @Test fun ordinaryUnlockRetainsSessionEstablishedPath() {
        val f = VaultSessionFixture(); f.ready(); f.authenticating()
        assertEquals(VaultAttemptPurpose.UNLOCK, f.state.activeAttempt!!.purpose)
        f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
        f.stored(); assertEquals(VaultMode.SESSION_OPEN, f.state.mode)
    }
}
