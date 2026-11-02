package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

internal class VaultSessionFixture(
    val policy: VaultRetryPolicy = VaultRetryPolicy(listOf(10, 20, 40), 40, 100, 80, 200),
    load: VaultLedgerLoad = VaultLedgerLoad.TrustedFirstUse,
) {
    val reducer = VaultSessionReducer(policy)
    var time = 0L
    var transition = reducer.start(11, VaultTick(11, time), load)
    val state get() = transition.state
    fun send(event: VaultEvent, at: Long = time): VaultTransition {
        time = at; transition = reducer.reduce(state, event, VaultTick(11, time)); return transition
    }
    fun cleanup() = send(VaultEvent.CleanupCompleted(state.currentCleanup!!))
    fun ready() { cleanup(); send(VaultEvent.ForegroundEntered) }
    fun stored(): VaultTransition {
        val write = state.pendingRetryWrite!!
        return send(VaultEvent.RetryWriteStored(write.identity, write.expectedLedgerRevision, write.proposedLedger.ledgerRevision))
    }
    fun authenticating() { send(VaultEvent.UnlockRequested); stored() }
    fun open() {
        ready(); authenticating()
        send(VaultEvent.AuthenticationResult(state.activeAttempt!!.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
        stored(); send(VaultEvent.SurfaceVisible)
    }
}

class VaultSessionReducerTest {
    @Test fun startupIsLockedCoveredAndRequiresCleanupAndTrustedLedger() {
        val f = VaultSessionFixture()
        assertEquals(VaultMode.LOCKED, f.state.mode)
        assertEquals(VaultVisibility.COVERED, f.state.visibility)
        assertNull(f.state.activeAttempt)
        f.send(VaultEvent.ForegroundEntered); f.send(VaultEvent.UnlockRequested)
        assertEquals(VaultTransitionOutcome.BLOCKED, f.transition.outcome)
        f.cleanup(); f.send(VaultEvent.UnlockRequested)
        assertEquals(VaultMode.RESERVING_ATTEMPT, f.state.mode)
        for (load in listOf(VaultLedgerLoad.Corrupt, VaultLedgerLoad.Unavailable,
            VaultLedgerLoad.Restored(VaultRetryLedger(1, 1, -1, null)))) {
            assertEquals(VaultMode.UNAVAILABLE, VaultSessionFixture(load = load).state.mode)
        }
    }

    @Test fun fourPoliciesUseExactExplicitWindowDeadlinesAndBoundaryChecks() {
        for (policy in VaultAutoLockPolicy.entries) {
            val f = VaultSessionFixture(); f.open(); f.send(VaultEvent.PolicyChanged(policy))
            f.send(VaultEvent.AutoLockWindowStarted(1, f.state.policyRevision), 10)
            if (policy == VaultAutoLockPolicy.IMMEDIATE) assertEquals(VaultMode.LOCKED, f.state.mode)
            else {
                val deadline = 10 + policy.durationMillis
                assertEquals(deadline, f.state.lockDeadline!!.elapsedMillis)
                f.send(VaultEvent.ClockObserved, deadline - 1)
                assertEquals(VaultMode.SESSION_OPEN, f.state.mode)
                assertFalse(f.state.permitsPresentation(VaultTick(11, deadline), f.state.authEpoch, f.state.presentationEpoch))
                f.send(VaultEvent.ForegroundEntered, deadline)
                assertEquals(VaultMode.LOCKED, f.state.mode)
                assertEquals(VaultVisibility.COVERED, f.state.visibility)
            }
        }
    }

    @Test fun backgroundAndForegroundDoNotInventOrCancelWindowAndNeverAutomaticallyReplay() {
        val f = VaultSessionFixture(); f.open()
        val auth = f.state.authEpoch; val presentation = f.state.presentationEpoch
        f.send(VaultEvent.BackgroundEntered)
        assertNull(f.state.lockDeadline)
        assertEquals(VaultMode.SESSION_OPEN, f.state.mode)
        assertFalse(f.state.permitsPresentation(VaultTick(11, 0), auth, presentation))
        f.cleanup(); f.send(VaultEvent.ForegroundEntered)
        assertEquals(VaultVisibility.COVERED, f.state.visibility)
        f.send(VaultEvent.SurfaceVisible)
        assertFalse(f.state.permitsPresentation(VaultTick(11, 0), auth, presentation))
        assertTrue(f.state.permitsPresentation(VaultTick(11, 0), auth, f.state.presentationEpoch))
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_1_MINUTE))
        f.send(VaultEvent.AutoLockWindowStarted(1, f.state.policyRevision), 10)
        f.send(VaultEvent.BackgroundEntered, 20); f.cleanup(); f.send(VaultEvent.ForegroundEntered, 30)
        assertEquals(60_010, f.state.lockDeadline!!.elapsedMillis)
        assertEquals(VaultVisibility.COVERED, f.state.visibility)
    }

    @Test fun duplicateWindowAndPolicyChangesKeepOriginalAnchorAndCannotReviveExpiredSession() {
        val f = VaultSessionFixture(); f.open(); f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_1_MINUTE))
        f.send(VaultEvent.AutoLockWindowStarted(7, f.state.policyRevision), 10)
        f.send(VaultEvent.AutoLockWindowStarted(7, f.state.policyRevision), 100)
        assertEquals(10, f.state.lockWindowStartedAt!!.elapsedMillis)
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_5_MINUTES), 200)
        assertEquals(300_010, f.state.lockDeadline!!.elapsedMillis)
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.IMMEDIATE), 300)
        assertEquals(VaultMode.LOCKED, f.state.mode)
        f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_15_MINUTES)); f.send(VaultEvent.AutoLockWindowCancelled(7))
        assertEquals(VaultMode.LOCKED, f.state.mode)
        assertNull(f.state.lockDeadline)
    }

    @Test fun cancellationMustMatchWindowAndCannotReuseOldWindowId() {
        val f = VaultSessionFixture(); f.open(); f.send(VaultEvent.PolicyChanged(VaultAutoLockPolicy.AFTER_1_MINUTE))
        f.send(VaultEvent.AutoLockWindowStarted(4, f.state.policyRevision), 10)
        f.send(VaultEvent.AutoLockWindowCancelled(3)); assertNotNull(f.state.lockDeadline)
        f.send(VaultEvent.AutoLockWindowCancelled(4)); assertNull(f.state.lockDeadline)
        f.send(VaultEvent.AutoLockWindowStarted(4, f.state.policyRevision), 20); assertNull(f.state.lockDeadline)
    }

    @Test fun ignoredEventsAdvanceMonotonicWatermarkButBadTicksDoNot() {
        val f = VaultSessionFixture(); f.open()
        f.send(VaultEvent.AuthenticationResult(VaultAttemptIdentity(11, 999, 1), VaultAuthenticationOutcome.SESSION_ESTABLISHED), 50)
        assertEquals(50, f.state.lastObservedTick.elapsedMillis)
        val stale = f.reducer.reduce(f.state, VaultEvent.SurfaceVisible, VaultTick(11, 49))
        assertEquals(VaultMode.UNAVAILABLE, stale.state.mode)
        assertEquals(50, stale.state.lastObservedTick.elapsedMillis)
        for (tick in listOf(VaultTick(12, 60), VaultTick(11, -1))) {
            val bad = f.reducer.reduce(f.state, VaultEvent.SurfaceVisible, tick)
            assertEquals(VaultReason.CLOCK_INVALID, bad.state.reason)
            assertEquals(f.state.lastObservedTick, bad.state.lastObservedTick)
        }
    }

    @Test fun immediateManualAndDeviceLocksFenceEveryPolicy() {
        for (policy in VaultAutoLockPolicy.entries) for (event in listOf(VaultEvent.ExplicitLock, VaultEvent.DeviceLocked)) {
            val f = VaultSessionFixture(); f.open(); f.send(VaultEvent.PolicyChanged(policy))
            val epoch = f.state.authEpoch
            f.send(event)
            assertEquals(VaultMode.LOCKED, f.state.mode)
            assertTrue(f.state.authEpoch > epoch)
            assertEquals(VaultVisibility.COVERED, f.state.visibility)
        }
    }

    @Test fun reservationMustBeAcknowledgedExactlyBeforeOneAuthenticationBegins() {
        val f = VaultSessionFixture(); f.ready(); f.send(VaultEvent.UnlockRequested)
        val pending = f.state.pendingRetryWrite!!
        assertEquals(0, f.state.retryLedger.reservedAttemptCount)
        assertEquals(1, pending.proposedLedger.reservedAttemptCount)
        assertTrue(f.transition.effects.none { it is VaultEffect.BeginExternalAuthentication })
        f.send(VaultEvent.UnlockRequested); assertEquals(pending, f.state.pendingRetryWrite)
        f.send(VaultEvent.RetryWriteStored(pending.identity.copy(writeId = 99), 0, 1))
        assertEquals(VaultMode.RESERVING_ATTEMPT, f.state.mode)
        f.stored()
        assertEquals(1, f.transition.effects.count { it is VaultEffect.BeginExternalAuthentication })
        assertEquals(1, f.state.retryLedger.reservedAttemptCount)
        f.send(VaultEvent.RetryWriteStored(pending.identity, 0, 1))
        assertTrue(f.transition.effects.none { it is VaultEffect.BeginExternalAuthentication })
    }

    @Test fun retriesReserveBeforeEveryAttemptAndFailuresCancellationsKeepCountAndTier() {
        val f = VaultSessionFixture(); f.ready()
        for (count in 1L..5L) {
            f.authenticating()
            assertEquals(count, f.state.retryLedger.reservedAttemptCount)
            assertEquals(minOf(count - 1, 2).toInt(), f.state.retryLedger.delayTier)
            val result = if (count % 2L == 0L) VaultAuthenticationOutcome.CANCELLED else VaultAuthenticationOutcome.REJECTED
            f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, result))
            val end = f.state.retryDeadline!!.elapsedMillis
            f.cleanup(); f.send(VaultEvent.UnlockRequested, end - 1)
            assertEquals(VaultMode.LOCKED, f.state.mode)
            f.send(VaultEvent.ClockObserved, end)
            assertEquals(VaultRetryStatus.READY, f.state.retryStatus)
        }
        val restored = VaultSessionFixture(load = VaultLedgerLoad.Restored(f.state.retryLedger))
        assertEquals(40, restored.state.retryDeadline!!.elapsedMillis)
        assertEquals(5, restored.state.retryLedger.reservedAttemptCount)
    }

    @Test fun failureClassificationsRemainDistinctAndPlatformDelayCannotWeakenLocalDelay() {
        val cases = mapOf(VaultAuthenticationOutcome.REJECTED to VaultReason.REJECTED_CREDENTIAL,
            VaultAuthenticationOutcome.CANCELLED to VaultReason.ATTEMPT_CANCELLED,
            VaultAuthenticationOutcome.TEMPORARILY_UNAVAILABLE to VaultReason.PLATFORM_THROTTLED,
            VaultAuthenticationOutcome.KEY_INVALIDATED to VaultReason.KEY_INVALIDATED)
        for ((outcome, reason) in cases) {
            val f = VaultSessionFixture(); f.ready(); f.authenticating()
            f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, outcome, 50))
            assertEquals(reason, f.state.reason); assertEquals(1, f.state.retryLedger.reservedAttemptCount)
            if (outcome == VaultAuthenticationOutcome.TEMPORARILY_UNAVAILABLE) assertEquals(50, f.state.retryDeadline!!.elapsedMillis)
        }
    }

    @Test fun protectingEventsCannotShortenAnExistingPlatformCooldown() {
        for (event in listOf(VaultEvent.ExplicitLock, VaultEvent.DeviceLocked, VaultEvent.BackgroundEntered)) {
            val f = VaultSessionFixture(); f.ready(); f.authenticating()
            f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.TEMPORARILY_UNAVAILABLE, 60_000))
            val strictest = f.state.retryDeadline
            f.send(event, 1)
            assertEquals(strictest, f.state.retryDeadline)
        }
    }

    @Test fun successfulAuthenticationWaitsForCasResetAndUnknownWriteKeepsConfirmedLedger() {
        val f = VaultSessionFixture(); f.ready(); f.authenticating()
        val reserved = f.state.retryLedger
        f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
        assertEquals(VaultMode.ACCEPTED_PENDING_RETRY_RESET, f.state.mode)
        assertEquals(reserved, f.state.retryLedger)
        val reset = f.state.pendingRetryWrite!!
        assertEquals(VaultRetryWriteKind.RESET, reset.kind)
        assertEquals(reserved.ledgerRevision, reset.expectedLedgerRevision)
        f.send(VaultEvent.RetryWriteFailed(reset.identity))
        assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
        assertEquals(reserved, f.state.retryLedger)
        assertEquals(reset, f.state.pendingRetryWrite)
        f.send(VaultEvent.UnlockRequested)
        assertEquals(reset, f.state.pendingRetryWrite)
        f.stored()
        assertEquals(0, f.state.retryLedger.reservedAttemptCount)
        assertNotEquals(VaultMode.SESSION_OPEN, f.state.mode)
    }

    @Test fun lockDuringPendingResetCanReconcileLedgerWithoutReopeningAndOldCasIsZeroWrite() {
        val f = VaultSessionFixture(); f.ready(); f.authenticating()
        f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
        val reset = f.state.pendingRetryWrite!!
        f.send(VaultEvent.ExplicitLock); assertEquals(reset, f.state.pendingRetryWrite)
        f.stored(); assertEquals(VaultMode.LOCKED, f.state.mode)
        assertEquals(0, f.state.retryLedger.reservedAttemptCount)
        f.cleanup(); f.send(VaultEvent.UnlockRequested); f.stored()
        val newerLedger = f.state.retryLedger
        var stored = newerLedger
        var writes = 0
        // Pure CAS contract fixture; this is not an actual durable storage test.
        if (reset.matchesStoredRevision(stored.ledgerRevision)) { stored = reset.proposedLedger; writes++ }
        assertEquals(0, writes); assertEquals(newerLedger, stored)
        f.send(VaultEvent.RetryWriteStored(reset.identity, reset.expectedLedgerRevision, reset.proposedLedger.ledgerRevision))
        assertEquals(newerLedger, f.state.retryLedger)
        assertEquals(VaultMode.AUTHENTICATING, f.state.mode)
    }

    @Test fun reservationResetAndAuthenticationHaveBoundedTimeoutsAndLateAcksCannotOpen() {
        for (stage in 0..2) {
            val f = VaultSessionFixture(); f.ready(); f.send(VaultEvent.UnlockRequested)
            if (stage > 0) f.stored()
            if (stage == 2) f.send(VaultEvent.AuthenticationResult(f.state.activeAttempt!!.identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
            val deadline = f.state.activeAttempt!!.deadline.elapsedMillis
            val pending = f.state.pendingRetryWrite
            f.send(VaultEvent.ClockObserved, deadline)
            assertTrue(f.state.mode != VaultMode.AUTHENTICATING && f.state.mode != VaultMode.ACCEPTED_PENDING_RETRY_RESET && f.state.mode != VaultMode.RESERVING_ATTEMPT)
            if (pending != null) {
                assertEquals(pending, f.state.pendingRetryWrite)
                f.stored(); assertNotEquals(VaultMode.SESSION_OPEN, f.state.mode)
                assertTrue(f.transition.effects.none { it is VaultEffect.BeginExternalAuthentication })
            }
        }
    }

    @Test fun staleAuthenticationAndBothLockSuccessOrdersNeverReopen() {
        for (lockFirst in listOf(true, false)) {
            val f = VaultSessionFixture(); f.ready(); f.authenticating()
            val attempt = f.state.activeAttempt!!.identity
            if (lockFirst) f.send(VaultEvent.ExplicitLock)
            f.send(VaultEvent.AuthenticationResult(attempt, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
            if (!lockFirst) f.send(VaultEvent.ExplicitLock)
            if (f.state.pendingRetryWrite != null) f.stored()
            assertEquals(VaultMode.LOCKED, f.state.mode)
            f.cleanup(); f.send(VaultEvent.ClockObserved, 40); f.authenticating()
            val current = f.state.activeAttempt
            for (identity in listOf(attempt, current!!.identity.copy(processInstance = 12), current.identity.copy(authEpoch = 999))) {
                f.send(VaultEvent.AuthenticationResult(identity, VaultAuthenticationOutcome.SESSION_ESTABLISHED))
                assertEquals(current, f.state.activeAttempt)
                assertTrue(f.transition.effects.any { it is VaultEffect.DiscardAttemptResult })
            }
        }
    }

    @Test fun cleanupRequiresCurrentFullBindingAndFirstTerminalResultWins() {
        val f = VaultSessionFixture(); val old = f.state.currentCleanup!!
        f.send(VaultEvent.ExplicitLock); val current = f.state.currentCleanup!!
        f.send(VaultEvent.CleanupCompleted(old)); assertEquals(VaultCleanupStatus.PENDING, f.state.cleanupStatus)
        f.send(VaultEvent.CleanupFailed(old)); assertEquals(VaultCleanupStatus.PENDING, f.state.cleanupStatus)
        f.send(VaultEvent.CleanupCompleted(current.copy(presentationEpoch = 99)))
        assertEquals(VaultCleanupStatus.PENDING, f.state.cleanupStatus)
        f.send(VaultEvent.CleanupCompleted(current)); assertEquals(VaultCleanupStatus.COMPLETE, f.state.cleanupStatus)
        f.send(VaultEvent.CleanupFailed(current)); assertEquals(VaultCleanupStatus.COMPLETE, f.state.cleanupStatus)
        f.send(VaultEvent.ExplicitLock); val failed = f.state.currentCleanup!!
        f.send(VaultEvent.CleanupFailed(failed)); f.send(VaultEvent.CleanupCompleted(failed))
        assertEquals(VaultCleanupStatus.FAILED, f.state.cleanupStatus)
        assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
    }

    @Test fun missingCleanupCallbackFailsClosedAndFreshRetryRoundGetsNewIdentity() {
        val f = VaultSessionFixture(); val old = f.state.currentCleanup!!
        f.send(VaultEvent.ClockObserved, old.deadline.elapsedMillis)
        assertEquals(VaultCleanupStatus.FAILED, f.state.cleanupStatus)
        assertEquals(VaultMode.UNAVAILABLE, f.state.mode)
        f.send(VaultEvent.ExplicitLock)
        assertNotEquals(old.cleanupId, f.state.currentCleanup!!.cleanupId)
        f.cleanup(); f.send(VaultEvent.ForegroundEntered); f.send(VaultEvent.UnlockRequested)
        assertEquals(VaultMode.RESERVING_ATTEMPT, f.state.mode)
    }

    @Test fun onlyOptInAndConfirmedMaterialOfferOfflineRecoveryAndNoRoutePromisesSuccess() {
        for (choice in VaultRecoveryChoice.entries) for (material in VaultRecoveryMaterialStatus.entries) {
            val f = VaultSessionFixture()
            f.send(VaultEvent.RecoveryChoiceRecorded(choice)); f.send(VaultEvent.RecoveryMaterialStatusObserved(material))
            f.send(VaultEvent.KeyStateObserved(true))
            val routes = f.transition.effects.filterIsInstance<VaultEffect.RequireUserRecovery>().single().allowedRoutes
            assertEquals(choice == VaultRecoveryChoice.OPTED_IN && material == VaultRecoveryMaterialStatus.CONFIRMED_AVAILABLE,
                VaultRecoveryRoute.USER_OFFLINE_RECOVERY in routes)
            assertEquals(VaultMode.RECOVERY_REQUIRED, f.state.mode)
        }
    }

    @Test fun countersAndDeadlineOverflowFailClosedWithoutWrapping() {
        val f = VaultSessionFixture(); f.open()
        for (s in listOf(f.state.copy(revision = Long.MAX_VALUE), f.state.copy(authEpoch = Long.MAX_VALUE, currentCleanup = null, cleanupStatus = VaultCleanupStatus.FAILED),
            f.state.copy(presentationEpoch = Long.MAX_VALUE, currentCleanup = null, cleanupStatus = VaultCleanupStatus.FAILED),
            f.state.copy(lastIdentifier = Long.MAX_VALUE))) {
            val result = f.reducer.reduce(s, VaultEvent.ExplicitLock, VaultTick(11, 0))
            assertEquals(VaultMode.UNAVAILABLE, result.state.mode)
            assertTrue(result.state.authEpoch >= 0); assertTrue(result.state.presentationEpoch >= 0)
        }
        val nearEnd = f.state.copy(lastObservedTick = VaultTick(11, Long.MAX_VALUE - 1), autoLockPolicy = VaultAutoLockPolicy.AFTER_1_MINUTE)
        val overflow = f.reducer.reduce(nearEnd, VaultEvent.AutoLockWindowStarted(1, nearEnd.policyRevision), VaultTick(11, Long.MAX_VALUE - 1))
        assertEquals(VaultMode.UNAVAILABLE, overflow.state.mode)
        assertEquals(VaultVisibility.COVERED, overflow.state.visibility)
    }
}
