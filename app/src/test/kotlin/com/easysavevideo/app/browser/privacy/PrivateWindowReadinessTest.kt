package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

/** Executes the production readiness value, not Android windows or provider acceptance. */
class PrivateWindowReadinessTest {
    private fun ready(recents: PrivateRecentsCapability) = PrivateWindowReadiness(
        ownedRootAttached = true, secureRequested = true, viewTreeOwnersInstalled = true,
        structureExcluded = true, contentCapture = PrivateContentCaptureCapability.DISABLED_BY_ACTIVITY,
        recents = recents, coverFrameSubmitted = false, resumedAndFocused = false,
        pending = setOf(PrivateWindowPendingGate.CAPTURE_POLICY_ADAPTER),
    )
    @Test fun api31And32SecureBrowsingDoesNotDependOnCaptureExceptionOrApi33Recents() {
        assertTrue(ready(PrivateRecentsCapability.LEGACY_EVIDENCE_UNAVAILABLE).ready)
        assertTrue(ready(PrivateRecentsCapability.API33_SUPPRESSION_REQUESTED).ready)
        assertFalse(ready(PrivateRecentsCapability.UNAVAILABLE).ready)
    }
    @Test fun coveredTransitionAndFocusTransferDoNotWithdrawInstalledReadiness() {
        val current = ready(PrivateRecentsCapability.API33_SUPPRESSION_REQUESTED)
        assertFalse(current.coverFrameSubmitted); assertFalse(current.resumedAndFocused)
        assertTrue(current.ready)
    }
    @Test fun everyNonCaptureAcceptanceGateStillBlocksBrowsing() {
        val current = ready(PrivateRecentsCapability.LEGACY_EVIDENCE_UNAVAILABLE)
        PrivateWindowPendingGate.entries.filter { it != PrivateWindowPendingGate.CAPTURE_POLICY_ADAPTER }.forEach {
            assertFalse(it.name, current.copy(pending = setOf(it)).ready)
        }
    }
    @Test fun missingActualParentSecurityOrContentCaptureCapabilityIsNeverReady() {
        val safe = ready(PrivateRecentsCapability.LEGACY_EVIDENCE_UNAVAILABLE)
        assertFalse(safe.copy(ownedRootAttached = false).ready)
        assertFalse(safe.copy(secureRequested = false).ready)
        assertFalse(safe.copy(viewTreeOwnersInstalled = false).ready)
        assertFalse(safe.copy(structureExcluded = false).ready)
        assertFalse(safe.copy(contentCapture = PrivateContentCaptureCapability.UNAVAILABLE).ready)
        assertTrue(safe.copy(contentCapture = PrivateContentCaptureCapability.FACILITY_ABSENT).ready)
    }
    @Test fun authorizedContinuousPixelsOnlyReplaceSecureFlagRequirement() {
        val active = ready(PrivateRecentsCapability.LEGACY_EVIDENCE_UNAVAILABLE)
            .copy(secureRequested = false, continuousCaptureAuthorized = true)
        assertTrue(active.ready)
        assertFalse(active.copy(continuousCaptureAuthorized = false).ready)
        assertFalse(active.copy(ownedRootAttached = false).ready)
        assertFalse(active.copy(structureExcluded = false).ready)
        assertFalse(active.copy(pending = setOf(PrivateWindowPendingGate.ANDROID_DEVICE_VALIDATION)).ready)
    }

}
