package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Lifecycle observations and virtual ticks only; no Android owner or effect execution. */
class VaultLifecyclePolicyTest {
    private fun observation(route: Boolean, foreground: Boolean) = VaultEvent.LifecycleObserved(route, foreground)
    private fun open(policy: VaultAutoLockPolicy = VaultAutoLockPolicy.AFTER_1_MINUTE): VaultSessionFixture {
        val f = VaultSessionFixture()
        f.send(observation(true, true))
        f.open()
        f.send(VaultEvent.PolicyChanged(policy))
        return f
    }
    private fun assertCovered(f: VaultSessionFixture) {
        assertEquals(VaultVisibility.COVERED, f.state.visibility)
        assertFalse(f.state.permitsPresentation(VaultTick(11, f.time), f.state.authEpoch, f.state.presentationEpoch))
    }
    private fun assertNoProtection(t: VaultTransition) {
        assertTrue(t.effects.none { it is VaultEffect.RequestOwnedPlaintextCleanup || it is VaultEffect.RevokeExternalSession ||
            it is VaultEffect.StopPrivatePlaybackAndReaders || it is VaultEffect.ClearPrivateMemory })
    }

    @Test fun fourExactPoliciesStartOnEitherDepartureAndExpireBeforeReturn() {
        for (policy in VaultAutoLockPolicy.entries) for (departure in listOf(observation(false, true), observation(true, false))) {
            val f = open(policy); val auth = f.state.authEpoch
            f.send(departure, 10)
            assertCovered(f)
            assertEquals(1, f.transition.effects.filterIsInstance<VaultEffect.RequestOwnedPlaintextCleanup>().size)
            if (policy == VaultAutoLockPolicy.IMMEDIATE) {
                assertEquals(VaultMode.LOCKED, f.state.mode)
                assertTrue(f.state.authEpoch > auth)
                assertNull(f.state.lockDeadline)
                f.send(observation(true, true), 10)
                assertEquals(VaultMode.LOCKED, f.state.mode)
            } else {
                val deadline = 10 + policy.durationMillis
                assertEquals(VaultTick(11, 10), f.state.lockWindowStartedAt)
                assertEquals(VaultTick(11, deadline), f.state.lockDeadline)
                assertEquals(auth, f.state.authEpoch)
                f.cleanup()
                f.send(VaultEvent.ClockObserved, deadline - 1)
                assertEquals(VaultMode.SESSION_OPEN, f.state.mode)
                f.send(observation(true, true), deadline)
                assertEquals(VaultMode.LOCKED, f.state.mode)
                assertTrue(f.state.authEpoch > auth)
                assertCovered(f)
            }
        }
    }

    @Test fun returnBeforeDeadlineCancelsWithoutShowingOrReplaying() {
        for (policy in VaultAutoLockPolicy.entries.filter { it != VaultAutoLockPolicy.IMMEDIATE }) {
            val f = open(policy); val oldPresentation = f.state.presentationEpoch
            f.send(observation(false, true), 10); f.cleanup()
            f.send(observation(true, true), 10 + policy.durationMillis - 1)
            assertNull(f.state.lockDeadline)
            assertEquals(VaultMode.SESSION_OPEN, f.state.mode)
            assertCovered(f); assertNoProtection(f.transition)
            f.send(VaultEvent.SurfaceVisible)
            assertTrue(f.state.permitsPresentation(VaultTick(11, f.time), f.state.authEpoch, f.state.presentationEpoch))
            assertFalse(f.state.permitsPresentation(VaultTick(11, f.time), f.state.authEpoch, oldPresentation))
        }
    }

    @Test fun bothDepartureAndReturnOrdersRequireBothPresenceConditions() {
        for (pageFirst in listOf(true, false)) for (pageReturnsFirst in listOf(true, false)) {
            val f = open()
            f.send(if (pageFirst) observation(false, true) else observation(true, false), 10)
            val window = f.state.lockWindowId; val deadline = f.state.lockDeadline; val cleanup = f.state.currentCleanup
            f.send(observation(false, false), 20)
            assertEquals(window, f.state.lockWindowId); assertEquals(deadline, f.state.lockDeadline)
            assertEquals(cleanup, f.state.currentCleanup); assertNoProtection(f.transition)
            f.send(if (pageReturnsFirst) observation(true, false) else observation(false, true), 30)
            assertEquals(deadline, f.state.lockDeadline); assertEquals(cleanup, f.state.currentCleanup)
            assertNoProtection(f.transition); assertCovered(f)
            f.send(observation(true, true), 40)
            assertNull(f.state.lockDeadline); assertEquals(cleanup, f.state.currentCleanup)
            assertCovered(f)
            f.send(VaultEvent.SurfaceVisible)
            assertEquals(VaultTransitionOutcome.BLOCKED, f.transition.outcome)
            f.cleanup(); assertCovered(f)
        }
    }

    @Test fun duplicatesAndSecondAwayConditionNeverRefreshWindowCleanupOrEpochs() {
        val f = open(); f.send(observation(false, true), 10)
        val first = f.state
        val events = listOf(observation(false, true), VaultEvent.ForegroundEntered, observation(false, false),
            VaultEvent.BackgroundEntered, observation(false, false), observation(true, false),
            VaultEvent.BackgroundEntered, observation(false, false), observation(false, true))
        for ((index, event) in events.withIndex()) {
            f.send(event, 11L + index)
            assertEquals(first.lockWindowId, f.state.lockWindowId)
            assertEquals(first.lockWindowStartedAt, f.state.lockWindowStartedAt)
            assertEquals(first.lockDeadline, f.state.lockDeadline)
            assertEquals(first.currentCleanup, f.state.currentCleanup)
            assertEquals(first.authEpoch, f.state.authEpoch)
            assertEquals(first.presentationEpoch, f.state.presentationEpoch)
            assertNoProtection(f.transition)
        }
        f.send(observation(false, true), first.currentCleanup!!.deadline.elapsedMillis)
        assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
        assertEquals(VaultCleanupStatus.FAILED, f.state.cleanupStatus)
    }

    @Test fun legacyForegroundAndBackgroundSignalsUseActiveLifecyclePolicy() {
        val f = open(); f.send(VaultEvent.BackgroundEntered, 10)
        val first = f.state
        assertEquals(VaultTick(11, 60_010), first.lockDeadline)
        f.send(VaultEvent.BackgroundEntered, 20)
        assertEquals(first.currentCleanup, f.state.currentCleanup); assertEquals(first.lockDeadline, f.state.lockDeadline)
        f.send(observation(false, false), 30)
        f.send(VaultEvent.ForegroundEntered, 40)
        assertEquals(false, f.state.inVaultRoute); assertNotNull(f.state.lockDeadline)
        f.send(observation(true, true), 50)
        assertNull(f.state.lockDeadline); assertCovered(f)
        f.cleanup(); f.send(VaultEvent.SurfaceVisible)
        val visible = f.state
        f.send(VaultEvent.ForegroundEntered, 60)
        assertEquals(visible.presentationEpoch, f.state.presentationEpoch)
        assertEquals(visible.currentCleanup, f.state.currentCleanup)
        assertNull(f.state.lockDeadline)
    }

    @Test fun firstObservedAbsenceProtectsExistingLowLevelSession() {
        for (departure in listOf(observation(false, true), observation(true, false), observation(false, false))) {
            val f = VaultSessionFixture(); f.open(); f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_5_MINUTES))
            assertNull(f.state.inVaultRoute)
            f.send(departure, 10)
            assertEquals(VaultTick(11, 300_010), f.state.lockDeadline)
            assertCovered(f)
            val cleanup = f.state.currentCleanup
            f.send(departure, 20)
            assertEquals(cleanup, f.state.currentCleanup)
        }
    }

    @Test fun explicitWindowInputsCannotBypassOrRenewEnabledPolicy() {
        val f = open()
        f.send(VaultEvent.AutoLockWindowStarted(50, f.state.policyRevision), 5)
        assertNull(f.state.lockDeadline); assertEquals(0, f.state.highestWindowId)
        f.send(observation(false, true), 10)
        val first = f.state
        val firstWindow = first.lockWindowId!!
        f.send(VaultEvent.AutoLockWindowStarted(first.highestWindowId + 1, f.state.policyRevision), 20)
        assertEquals(first.lockDeadline, f.state.lockDeadline); assertEquals(first.lockWindowId, f.state.lockWindowId)
        f.send(VaultEvent.AutoLockWindowCancelled(firstWindow), 30)
        assertEquals(first.lockDeadline, f.state.lockDeadline)
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_5_MINUTES), 40)
        f.send(VaultEvent.AutoLockWindowStarted(99, f.state.policyRevision), 50)
        assertEquals(VaultTick(11, 300_010), f.state.lockDeadline)
        assertEquals(first.lockWindowStartedAt, f.state.lockWindowStartedAt)
        assertEquals(first.currentCleanup, f.state.currentCleanup)
        f.cleanup(); f.send(observation(true, true), 60)
        f.send(observation(false, true), 70)
        assertTrue(f.state.lockWindowId!! > firstWindow)
        val newDeadline = f.state.lockDeadline
        f.send(VaultEvent.AutoLockWindowCancelled(firstWindow))
        assertEquals(newDeadline, f.state.lockDeadline)
    }

    @Test fun popupCoverageIsNotRouteDepartureAndDoesNotCancelAwayWindow() {
        val f = open(); f.send(VaultEvent.SurfaceCovered, 10)
        assertEquals(true, f.state.inVaultRoute); assertTrue(f.state.foreground)
        assertNull(f.state.lockDeadline); assertCovered(f)
        f.cleanup(); f.send(VaultEvent.SurfaceVisible)
        assertEquals(VaultVisibility.VISIBLE, f.state.visibility)
        f.send(observation(false, true), 20); f.cleanup()
        val deadline = f.state.lockDeadline
        f.send(VaultEvent.SurfaceVisible, 30)
        assertEquals(VaultTransitionOutcome.BLOCKED, f.transition.outcome)
        assertCovered(f); assertEquals(deadline, f.state.lockDeadline)
        f.send(VaultEvent.SurfaceCovered, 40)
        assertEquals(false, f.state.inVaultRoute); assertEquals(deadline, f.state.lockDeadline)
    }

    @Test fun policyChangesRetainOriginalDepartureAndExpiredWindowWinsOverRelaxation() {
        val f = open(); f.send(observation(false, true), 10); f.cleanup()
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_15_MINUTES), 100)
        assertEquals(VaultTick(11, 900_010), f.state.lockDeadline)
        assertEquals(VaultTick(11, 10), f.state.lockWindowStartedAt)
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_1_MINUTE), 60_010)
        assertEquals(VaultMode.LOCKED, f.state.mode)
        val g = open(); g.send(observation(true, false), 10); g.cleanup()
        g.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_15_MINUTES), 60_010)
        assertEquals(VaultMode.LOCKED, g.state.mode)
        g.send(observation(true, true)); assertEquals(VaultMode.LOCKED, g.state.mode)
    }

    @Test fun leavingDuringAllAuthenticationStagesRevokesAttemptAndLateCallbacksCannotOpen() {
        for (stage in 0..2) for (returnBeforeCallback in listOf(true, false)) {
            val f = VaultSessionFixture(); f.send(observation(true, true)); f.ready()
            f.send(VaultEvent.UnlockRequested)
            if (stage > 0) f.stored()
            val attempt = f.state.activeAttempt!!.identity
            if (stage == 2) f.send(VaultEvent.AuthenticationResult(attempt, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
            val pending = f.state.pendingRetryWrite; val confirmed = f.state.retryLedger
            f.send(observation(false, true), 1)
            assertNull(f.state.activeAttempt); assertEquals(VaultMode.LOCKED, f.state.mode)
            assertEquals(pending, f.state.pendingRetryWrite); assertEquals(confirmed, f.state.retryLedger)
            assertTrue(f.state.authEpoch > attempt.authEpoch); assertCovered(f)
            if (returnBeforeCallback) f.send(observation(true, true), 2)
            f.send(VaultEvent.AuthenticationResult(attempt, VaultAuthenticationOutcome.SESSION_ESTABLISHED), 3)
            assertTrue(f.transition.effects.any { it is VaultEffect.DiscardAttemptResult })
            if (pending != null) {
                f.stored()
                assertEquals(pending.proposedLedger, f.state.retryLedger)
                assertTrue(f.transition.effects.none { it is VaultEffect.BeginExternalAuthentication })
            }
            assertNotEquals(VaultMode.SESSION_OPEN, f.state.mode)
            assertNull(f.state.activeAttempt)
        }
    }

    @Test fun unlockIsBlockedWhileRouteAbsentDespiteForegroundAndCompletedCleanup() {
        val f = VaultSessionFixture(); f.send(observation(false, true)); f.cleanup()
        f.send(VaultEvent.UnlockRequested)
        assertEquals(VaultTransitionOutcome.BLOCKED, f.transition.outcome)
        assertNull(f.state.activeAttempt)
        f.send(observation(true, true)); f.send(VaultEvent.UnlockRequested)
        assertEquals(VaultMode.RESERVING_ATTEMPT, f.state.mode)
    }

    @Test fun stricterPlatformRetryDeadlineIsNotWeakenedByDeparturesOrReturns() {
        val f = VaultSessionFixture(); f.send(observation(true, true)); f.ready(); f.authenticating()
        f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.TEMPORARILY_UNAVAILABLE, 60_000))
        val retry = f.state.retryDeadline; val ledger = f.state.retryLedger
        f.cleanup(); f.send(observation(false, true), 1)
        f.send(observation(false, false), 2); f.send(observation(true, false), 3); f.send(VaultEvent.ForegroundEntered, 4)
        assertEquals(retry, f.state.retryDeadline); assertEquals(ledger, f.state.retryLedger)
        assertNull(f.state.lockDeadline)
    }

    @Test fun oldCleanupAcksCannotFinishOrUndoNewDepartureOrExpiryCleanup() {
        val f = open(); val old = f.state.currentCleanup!!
        f.send(observation(false, true), 10); val departure = f.state.currentCleanup!!
        for (event in listOf(VaultEvent.CleanupCompleted(old), VaultEvent.CleanupFailed(old))) {
            f.send(event)
            assertEquals(VaultCleanupStatus.PENDING, f.state.cleanupStatus)
            assertEquals(departure, f.state.currentCleanup)
        }
        f.cleanup(); f.send(VaultEvent.CleanupFailed(departure))
        assertEquals(VaultCleanupStatus.COMPLETE, f.state.cleanupStatus)
        f.send(observation(true, true), 60_010); val expired = f.state.currentCleanup!!
        assertNotEquals(departure, expired)
        f.send(VaultEvent.CleanupCompleted(departure))
        assertEquals(VaultCleanupStatus.PENDING, f.state.cleanupStatus)
        assertEquals(expired, f.state.currentCleanup)
        f.send(VaultEvent.CleanupFailed(expired)); f.send(VaultEvent.CleanupCompleted(expired))
        assertEquals(VaultCleanupStatus.FAILED, f.state.cleanupStatus)
        assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
    }

    @Test fun cleanupTimeoutAtReturnIsNotCancelledByReturn() {
        val f = open(); f.send(observation(false, true), 10)
        val cleanup = f.state.currentCleanup!!
        f.send(observation(true, true), cleanup.deadline.elapsedMillis)
        assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
        assertEquals(VaultCleanupStatus.FAILED, f.state.cleanupStatus)
        f.send(VaultEvent.CleanupCompleted(cleanup)); assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
        assertCovered(f)
    }

    @Test fun processRestartAlwaysLocksAndOldProcessCallbacksCannotRestoreSession() {
        val f = open(); f.send(observation(false, true), 10); f.cleanup()
        val previous = f.state
        var next = f.reducer.start(12, VaultTick(12, 0), VaultLedgerLoad.Restored(previous.retryLedger))
        assertEquals(VaultMode.LOCKED, next.state.mode); assertNull(next.state.inVaultRoute)
        assertNull(next.state.lockWindowId); assertNull(next.state.lockDeadline)
        next = f.reducer.reduce(next.state, observation(true, true), VaultTick(12, 1))
        next = f.reducer.reduce(next.state, VaultEvent.CleanupCompleted(previous.currentCleanup!!), VaultTick(12, 1))
        assertEquals(VaultCleanupStatus.PENDING, next.state.cleanupStatus)
        next = f.reducer.reduce(next.state, VaultEvent.SurfaceVisible, VaultTick(12, 1))
        assertEquals(VaultMode.LOCKED, next.state.mode)
        assertEquals(VaultVisibility.COVERED, next.state.visibility)
    }

    @Test fun badTicksAndWindowExhaustionFailClosedWithoutRemovingLifecycleKnowledge() {
        val f = open(); f.send(observation(false, true), 10)
        for (tick in listOf(VaultTick(11, 9), VaultTick(11, -1), VaultTick(12, 20))) {
            val t = f.reducer.reduce(f.state, observation(true, true), tick)
            assertEquals(VaultTransitionOutcome.FAILED_CLOSED, t.outcome)
            assertEquals(VaultReason.CLOCK_INVALID, t.state.reason)
            assertEquals(false, t.state.inVaultRoute)
            assertEquals(f.state.lastObservedTick, t.state.lastObservedTick)
            assertEquals(VaultMode.UNAVAILABLE, t.state.mode)
        }
        val g = open()
        val exhausted = g.reducer.reduce(g.state.copy(highestWindowId = Long.MAX_VALUE), observation(false, true), VaultTick(11, 10))
        assertEquals(VaultReason.COUNTER_EXHAUSTED, exhausted.state.reason)
        assertEquals(VaultMode.UNAVAILABLE, exhausted.state.mode)
        assertEquals(Long.MAX_VALUE, exhausted.state.highestWindowId)
        val overflow = g.reducer.reduce(g.state.copy(lastObservedTick = VaultTick(11, Long.MAX_VALUE - 1)),
            observation(false, true), VaultTick(11, Long.MAX_VALUE - 1))
        assertEquals(VaultReason.CLOCK_INVALID, overflow.state.reason)
        assertEquals(VaultMode.UNAVAILABLE, overflow.state.mode)
    }

    @Test fun lifecycleActivationCannotBeRemovedByAnyLegacySignal() {
        val f = open()
        val events = listOf(VaultEvent.ForegroundEntered, VaultEvent.BackgroundEntered, VaultEvent.SurfaceCovered,
            VaultEvent.SurfaceVisible, VaultEvent.ExplicitLock, VaultEvent.DeviceLocked, VaultEvent.ClockObserved,
            VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_5_MINUTES), VaultEvent.AutoLockWindowStarted(99, f.state.policyRevision),
            VaultEvent.AutoLockWindowCancelled(1), VaultEvent.UnlockRequested)
        for (event in events) {
            f.send(event, f.time + 1)
            assertNotNull(f.state.inVaultRoute)
        }
    }

    @Test fun boundedMixedSequencesPreservePresencePresentationAndOriginalAwayAnchor() {
        val events = listOf(observation(true, true), observation(false, true), observation(true, false), observation(false, false),
            VaultEvent.ForegroundEntered, VaultEvent.BackgroundEntered, VaultEvent.SurfaceCovered, VaultEvent.SurfaceVisible,
            VaultEvent.ClockObserved)
        for (a in events) for (b in events) for (c in events) {
            val f = open()
            for (event in listOf(a, b, c)) {
                val before = f.state
                f.send(event, f.time + 1)
                val after = f.state
                assertEquals(VaultMode.SESSION_OPEN, after.mode)
                assertTrue(after.authEpoch >= before.authEpoch); assertTrue(after.presentationEpoch >= before.presentationEpoch)
                assertNotNull(after.inVaultRoute)
                val present = after.inVaultRoute == true && after.foreground
                if (!present) {
                    assertNotNull(after.lockDeadline); assertCovered(f)
                    if (before.lockDeadline != null) {
                        assertEquals(before.lockWindowStartedAt, after.lockWindowStartedAt)
                        assertEquals(before.lockDeadline, after.lockDeadline)
                    }
                } else assertNull(after.lockDeadline)
                if (after.visibility == VaultVisibility.VISIBLE) {
                    assertTrue(present); assertEquals(VaultCleanupStatus.COMPLETE, after.cleanupStatus)
                }
            }
        }
    }
}
