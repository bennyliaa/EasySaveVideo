package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class VaultContractInvariantTest {
    @Test fun allInputAndOutputCollectionsAreDefensivelyCopied() {
        val delays = mutableListOf(10L, 20L)
        val policy = VaultRetryPolicy(delays, 20, 100, 100, 100)
        delays[0] = 999; (policy.delaysMillis as MutableList<Long>)[0] = 888
        assertEquals(listOf(10L, 20L), policy.delaysMillis)
        val f = VaultRecoveryFixture(); val temps = mutableListOf(f.temp("temp-a"), f.temp("temp-b")); f.temps = temps
        val snapshot = f.snapshot(); temps.clear()
        assertEquals(2, snapshot.ownedPlaintextTemps.size)
        (snapshot.ownedPlaintextTemps as MutableList<VaultOwnedPlaintextTemp>).clear()
        assertEquals(2, snapshot.ownedPlaintextTemps.size)
        val plan = VaultRecoveryPlanner().planRecovery(snapshot)
        val expectedSteps = plan.orderedSteps
        (plan.orderedSteps as MutableList<VaultRecoveryStep>).clear()
        assertEquals(expectedSteps, plan.orderedSteps)
        val sf = VaultSessionFixture(); val effects = sf.transition.effects
        (sf.transition.effects as MutableList<VaultEffect>).clear()
        assertEquals(effects, sf.transition.effects)
        val routes = mutableListOf(VaultRecoveryRoute.TRY_EXISTING_PASSWORD, VaultRecoveryRoute.REVIEW_UNRECOVERABLE_STATE)
        val effect = VaultEffect.RequireUserRecovery(VaultReason.KEY_INVALIDATED, routes)
        routes.clear(); (effect.allowedRoutes as MutableList<VaultRecoveryRoute>).clear()
        assertEquals(2, effect.allowedRoutes.size)
    }

    @Test fun finiteEventSequencesPreservePresentationAndEpochInvariants() {
        val events = listOf(VaultEvent.ExplicitLock, VaultEvent.DeviceLocked, VaultEvent.BackgroundEntered,
            VaultEvent.ForegroundEntered, VaultEvent.SurfaceCovered, VaultEvent.SurfaceVisible,
            VaultEvent.UnlockRequested, VaultEvent.ClockObserved)
        for (a in events) for (b in events) for (c in events) {
            val f = VaultSessionFixture(); f.open()
            var previous = f.state
            for (event in listOf(a, b, c)) {
                f.send(event, f.time + 1)
                val state = f.state
                assertTrue(state.authEpoch >= previous.authEpoch)
                assertTrue(state.presentationEpoch >= previous.presentationEpoch)
                assertTrue(state.revision > previous.revision)
                assertTrue(state.lastObservedTick.elapsedMillis >= previous.lastObservedTick.elapsedMillis)
                if (state.visibility == VaultVisibility.VISIBLE) {
                    assertEquals(VaultMode.SESSION_OPEN, state.mode)
                    assertTrue(state.foreground); assertEquals(VaultCleanupStatus.COMPLETE, state.cleanupStatus)
                }
                if (state.presentationEpoch != previous.presentationEpoch || state.authEpoch != previous.authEpoch) {
                    assertFalse(state.permitsPresentation(VaultTick(11, f.time), previous.authEpoch, previous.presentationEpoch))
                }
                previous = state
            }
        }
    }

    @Test fun plansAreDeterministicAndHaveNoDuplicateSemanticSteps() {
        val f = VaultRecoveryFixture(); f.temps = listOf(f.temp("temp-a"), f.temp("temp-b"))
        for (phase in VaultJournalPhase.entries) for (key in VaultKeyAvailability.entries) {
            f.phase = phase; f.key = key
            val a = f.plan(); val b = f.plan()
            assertEquals(a.disposition, b.disposition); assertEquals(a.reason, b.reason)
            assertEquals(a.orderedSteps, b.orderedSteps)
            assertEquals(a.orderedSteps.size, a.orderedSteps.map { it.stepId }.distinct().size)
        }
    }

    @Test fun invalidInjectedRetryPoliciesFailClosedRatherThanChoosingProductionThresholds() {
        for (delays in listOf(emptyList(), listOf(-1L), listOf(20L, 10L), listOf(50L))) {
            val policy = VaultRetryPolicy(delays, 40, 100, 80, 200)
            assertEquals(VaultMode.UNAVAILABLE, VaultSessionFixture(policy).state.mode)
        }
    }
}
