package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production durable-decision guard; no Android database/device claim. */
class NormalizationLegacyFenceTest {
    private val owner = NormalizationOwner("task", "11111111-1111-4111-8111-111111111111", "commit")
    private val key = NormalizationKey(owner, "22222222-2222-4222-8222-222222222222")
    private val raw = NormalizationFingerprint(512, "a".repeat(64))
    private fun row(state: NormalizationLifecycle): NormalizationWorkspace {
        val output = if (state in setOf(NormalizationLifecycle.OUTPUT_READY, NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED))
            NormalizationFingerprint(400, "b".repeat(64)) else null
        return NormalizationWorkspace(key, 1, state, NormalizationDisposition.CONTINUE, raw, "c".repeat(64), output,
            output?.let { "d".repeat(64) },
            if (state in setOf(NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED)) NormalizationCleanupPlan(raw, null, null) else null)
    }
    private fun reject(block: () -> Unit) {
        val error = assertThrows(SaveFailure::class.java) { block() }
        assertEquals(SaveError.RECOVERY_REQUIRED, error.code)
    }
    @Test fun onlyExplicitDurableAbsenceAllowsLegacyWork() {
        var work = 0
        NormalizationLegacyFence.requireAbsent { NormalizationLookup.Absent }; work++
        assertEquals(1, work)
    }
    @Test fun everyPersistedLifecycleBlocksLegacyWorkWithoutAnActiveSession() {
        for (state in NormalizationLifecycle.entries) {
            var work = 0
            reject { NormalizationLegacyFence.requireAbsent { NormalizationLookup.Present(row(state)) }; work++ }
            assertEquals("$state must not reach legacy I/O", 0, work)
        }
    }
    @Test fun historicalCleanedDoesNotBecomeNewWriteAuthority() {
        repeat(3) { reject { NormalizationLegacyFence.requireAbsent { NormalizationLookup.Present(row(NormalizationLifecycle.CLEANED)) } } }
    }
    @Test fun unreadableLookupNeverMeansAbsence() {
        reject { NormalizationLegacyFence.requireAbsent { NormalizationLookup.Unreadable } }
    }
    @Test fun lookupThrowsBeforeOrAfterItsReadRemainClosed() {
        reject { NormalizationLegacyFence.requireAbsent { throw IllegalStateException("OWNED_TEST_READ") } }
        reject { NormalizationLegacyFence.requireAbsent { row(NormalizationLifecycle.WRITING); throw java.io.IOException("OWNED_TEST_READ") } }
    }
    @Test fun unrelatedAbsentOwnerStillWorksAfterAnotherOwnerWasBlocked() {
        reject { NormalizationLegacyFence.requireAbsent { NormalizationLookup.Present(row(NormalizationLifecycle.WRITING)) } }
        NormalizationLegacyFence.requireAbsent { NormalizationLookup.Absent }
    }
    @Test fun lostSessionAndRepeatedAttemptsNeverChangeTheDurableDecision() {
        val durable = row(NormalizationLifecycle.OUTPUT_READY)
        repeat(5) { reject { NormalizationLegacyFence.requireAbsent { NormalizationLookup.Present(durable) } } }
        assertEquals(NormalizationLifecycle.OUTPUT_READY, durable.lifecycle)
    }
}
