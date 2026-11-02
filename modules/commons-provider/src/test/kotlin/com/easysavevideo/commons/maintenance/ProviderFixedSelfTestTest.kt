package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsLimits
import org.junit.Assert.*
import org.junit.Test

class ProviderFixedSelfTestTest {
    private fun policy(limits: CommonsLimits = CommonsLimits(), enabled: Boolean = true) =
        ProviderPolicy(1, 0, 100_000, enabled, limits)

    @Test fun compiledVectorsPassAtEveryAdmittedPageSizeAndNeverNeedNetwork() {
        for (size in 1..10) assertTrue(ProviderFixedSelfTest.run(policy(CommonsLimits(pageSize = size))) {})
    }
    @Test fun tinyResponseBudgetFailsBeforeCandidateActivation() {
        assertFalse(ProviderFixedSelfTest.run(policy(CommonsLimits(maxResponseBytes = 1))) {})
        assertTrue(ProviderFixedSelfTest.run(policy(CommonsLimits(maxResponseBytes = 4_096))) {})
    }
    @Test fun disabledPolicyNeedsNoParserTrial() {
        assertTrue(ProviderFixedSelfTest.run(policy(CommonsLimits(maxResponseBytes = 1), false)) {})
    }
    @Test fun fixedWorkIsBoundedAndCancellationIsNotTurnedIntoFailedSelfTest() {
        var calls = 0
        assertTrue(ProviderFixedSelfTest.run(policy()) { calls++ })
        assertTrue(calls in 1..2_050)
        for (stop in listOf(1, calls / 2, calls)) {
            var seen = 0
            try { ProviderFixedSelfTest.run(policy()) { if (++seen == stop) throw IllegalStateException("CANCELLED") }; fail() }
            catch (error: IllegalStateException) { assertEquals("CANCELLED", error.message) }
        }
    }
}
