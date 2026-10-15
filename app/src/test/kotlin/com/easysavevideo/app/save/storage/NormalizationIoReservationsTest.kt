package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NormalizationIoReservationsTest {
    private val owner = NormalizationOwner("task", "11111111-1111-4111-8111-111111111111", "commit")
    private val key = NormalizationKey(owner, "22222222-2222-4222-8222-222222222222")

    @Test fun conversionFinishRequiresSameTokenAndZeroCallsAndObligationsAtBothHandoffs() {
        val registry = NormalizationIoReservations(Any())
        val token = registry.reserve(owner, NormalizationIoReservations.Stage.TRANSCODE, {}, key)
        for ((from, to) in listOf(NormalizationIoReservations.Stage.TRANSCODE to NormalizationIoReservations.Stage.FINISH,
            NormalizationIoReservations.Stage.FINISH to NormalizationIoReservations.Stage.PROOF_HANDOFF)) {
            val obligation = registry.retain(token)
            assertThrows(NormalizationRecovery::class.java) { registry.advance(token, from, to) }
            registry.release(obligation)
            registry.withIo(token) {
                assertThrows(NormalizationRecovery::class.java) { registry.advance(token, from, to) }
            }
            registry.advance(token, from, to)
            assertSame(registry, token.registry); assertEquals(to, token.stage)
        }
        assertThrows(NormalizationRecovery::class.java) {
            registry.advance(token, NormalizationIoReservations.Stage.PROOF_HANDOFF, NormalizationIoReservations.Stage.TRANSCODE)
        }
        assertTrue(registry.retire(token))
    }
    @Test fun conversionFinishRejectsRevokedUncertainWrongStageAndReconstructedTokens() {
        for (case in 0..3) {
            val registry = NormalizationIoReservations(Any())
            val token = registry.reserve(owner, NormalizationIoReservations.Stage.TRANSCODE, {}, key)
            val candidate = when (case) {
                0 -> token.also { registry.revoke(it) }
                1 -> token.also { registry.markUncertain(it, Any()) }
                2 -> token.also { registry.advance(it, NormalizationIoReservations.Stage.TRANSCODE, NormalizationIoReservations.Stage.FINISH) }
                else -> NormalizationIoReservations.Token.issue(registry, owner, {},
                    NormalizationIoReservations.State(NormalizationIoReservations.Stage.TRANSCODE, key), null)
            }
            assertThrows(NormalizationRecovery::class.java) {
                registry.advance(candidate, NormalizationIoReservations.Stage.TRANSCODE, NormalizationIoReservations.Stage.FINISH)
            }
            registry.retire(token)
        }
    }

    @Test fun ledgerAbsentReservationExcludesEveryLegacyIdentityButDoesNotBlockAnotherArtifact() {
        val registry = NormalizationIoReservations(Any())
        val token = registry.reserve(owner, NormalizationIoReservations.Stage.RAW_PROBE, {})
        assertNull(token.key)
        assertTrue(registry.hasAnyReservations()); assertFalse(registry.resourcesUnreleased())
        // These are the three lookup forms used by prepare/commit/artifact legacy entry points.
        assertThrows(NormalizationRecovery::class.java) { registry.requireLegacyAvailable(taskId = owner.taskId) }
        assertThrows(NormalizationRecovery::class.java) { registry.requireLegacyAvailable(commitId = owner.commitId) }
        assertThrows(NormalizationRecovery::class.java) { registry.requireLegacyAvailable(artifactId = owner.artifactId) }
        assertEquals(SaveError.BUSY, assertThrows(SaveFailure::class.java) { registry.requireNoReservations() }.code)
        registry.requireLegacyAvailable("other-task", "33333333-3333-4333-8333-333333333333", "other-commit")
        registry.advance(token, NormalizationIoReservations.Stage.RAW_PROBE, NormalizationIoReservations.Stage.PREPARE)
        registry.bindGeneration(token, key)
        registry.requireExact(token, owner, key, NormalizationIoReservations.Stage.PREPARE)
        assertTrue(registry.retire(token)); assertFalse(registry.hasAnyReservations())
        registry.requireLegacyAvailable(taskId = owner.taskId)
        assertThrows(NormalizationRecovery::class.java) { registry.requireExact(token) }
    }

    @Test fun copiedValuesForeignRegistryAndForgedObligationDoNotConveyOwnership() {
        val registry = NormalizationIoReservations(Any())
        val token = registry.reserve(owner, NormalizationIoReservations.Stage.PREPARE, {}, key)
        val other = NormalizationIoReservations(Any())
        val foreign = other.reserve(owner.copy(), NormalizationIoReservations.Stage.PREPARE, {}, key.copy())
        val copy = NormalizationIoReservations.Token.issue(registry, owner.copy(), {},
            NormalizationIoReservations.State(NormalizationIoReservations.Stage.PREPARE, key.copy()), scope = null)
        for (bad in listOf(foreign, copy)) {
            assertThrows(NormalizationRecovery::class.java) { registry.requireExact(bad, owner, key) }
            assertThrows(NormalizationRecovery::class.java) { registry.retain(bad) }
            assertFalse(registry.retire(bad))
        }
        val obligation = registry.retain(token)
        val forged = NormalizationIoReservations.Obligation.issue(token)
        assertThrows(NormalizationRecovery::class.java) { registry.release(forged) }
        assertFalse(registry.retire(token))
        registry.release(obligation)
        assertThrows(NormalizationRecovery::class.java) { registry.release(obligation) }
        assertTrue(registry.retire(token))
    }

    @Test fun revokeAndDrainProbeNeverWaitForStorageMonitorAndLateIoCannotSucceed() {
        val lock = Any(); val registry = NormalizationIoReservations(lock)
        val token = registry.reserve(owner, NormalizationIoReservations.Stage.PREPARE, {}, key)
        val inIo = CountDownLatch(1); val releaseIo = CountDownLatch(1)
        val inLock = CountDownLatch(1); val releaseLock = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val returnedSuccess = AtomicBoolean(false)
        val io = pool.submit {
            try { registry.withIo(token) { inIo.countDown(); check(releaseIo.await(5, TimeUnit.SECONDS)); true }; returnedSuccess.set(true) }
            catch (_: NormalizationRecovery) { }
        }
        assertTrue(inIo.await(2, TimeUnit.SECONDS))
        val holder = pool.submit { synchronized(lock) { inLock.countDown(); check(releaseLock.await(5, TimeUnit.SECONDS)) } }
        try {
            assertTrue(inLock.await(2, TimeUnit.SECONDS))
            val probe = pool.submit<Boolean> {
                registry.revoke(token)
                registry.hasAnyReservations() && registry.resourcesUnreleased() && !registry.requiresProcessRecovery()
            }
            assertTrue(probe.get(1, TimeUnit.SECONDS))
        } finally { releaseLock.countDown(); holder.get(2, TimeUnit.SECONDS); releaseIo.countDown(); io.get(2, TimeUnit.SECONDS); pool.shutdownNow() }
        assertFalse(returnedSuccess.get()); assertTrue(registry.retire(token)); assertFalse(registry.resourcesUnreleased())
    }

    @Test fun acquisitionAndCloseUncertaintyAreStickyAndRetainTheExactOwner() {
        val registry = NormalizationIoReservations(Any())
        val token = registry.reserve(owner, NormalizationIoReservations.Stage.COMMIT, {}, key)
        registry.retain(token)
        val retained = Any(); registry.markUncertain(token, retained)
        assertTrue(registry.requiresProcessRecovery()); assertTrue(registry.resourcesUnreleased())
        assertFalse(registry.retire(token)); assertTrue(registry.hasReservation(owner.artifactId))
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED,
            assertThrows(SaveFailure::class.java) { registry.requireNoReservations() }.code)
        assertThrows(SaveFailure::class.java) { registry.reserve(owner, NormalizationIoReservations.Stage.CLEANUP, {}, key) }
    }
}
