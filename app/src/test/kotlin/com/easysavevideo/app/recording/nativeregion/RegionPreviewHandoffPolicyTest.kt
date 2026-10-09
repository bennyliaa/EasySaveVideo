package com.easysavevideo.app.recording.nativeregion

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class RegionPreviewHandoffPolicyTest {
    private fun confirmation(expiresAt: Long = 60_000_120): RegionPreviewConfirmation {
        val session = RecordingSessionId("handoff-preview", 1)
        val geometry = CaptureDisplayGeometry(1080, 1920, 0)
        val snapshot = RegionPreviewSnapshot(session, 1, 1,
            CaptureGeometryObservation(geometry, CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY,
                CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 110),
            540, 960, RegionPreviewTransform(floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)),
            120_000, 120, expiresAt)
        val gate = RegionPreviewGate(session, 1, 100)
        assertTrue(gate.markHostReady(110)); assertTrue(gate.publish(snapshot, 120))
        assertTrue(gate.confirm(snapshot, RegionNormalizedRect.FULL, 130))
        assertTrue(gate.retireUi(snapshot, 140)); assertTrue(gate.markCaptureReady(snapshot, 150))
        return requireNotNull(gate.takeReady(151))
    }

    @Test fun successfulAdmissionBeforeBlockingCallCannotRenewTheHandoffDeadline() {
        val selection = confirmation()
        assertTrue(RegionPreviewHandoffPolicy.allows(selection, 151))
        assertTrue(RegionPreviewHandoffPolicy.allows(selection, 5_000_149))
        assertFalse(RegionPreviewHandoffPolicy.allows(selection, 5_000_150))
        assertFalse(RegionPreviewHandoffPolicy.allows(selection, 9_000_150))
    }

    @Test fun snapshotExpiryIsRecheckedAfterSourceWorkEvenIfGateAlreadyAdmitted() {
        val selection = confirmation(expiresAt = 200)
        assertTrue(RegionPreviewHandoffPolicy.allows(selection, 151))
        assertTrue(RegionPreviewHandoffPolicy.allows(selection, 199))
        assertFalse(RegionPreviewHandoffPolicy.allows(selection, 200))
    }

    @Test fun clockBeforeCaptureReadinessCannotAdmitHandoff() {
        assertFalse(RegionPreviewHandoffPolicy.allows(confirmation(), 149))
    }
}
