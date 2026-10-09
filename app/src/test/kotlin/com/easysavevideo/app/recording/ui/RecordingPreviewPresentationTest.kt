package com.easysavevideo.app.recording.ui

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class RecordingPreviewPresentationTest {
    private val geometry = CaptureDisplayGeometry(1080, 1920, 0, CaptureInsets(top = 80, bottom = 96))
    private val bounds = RegionNormalizedRect(0.101, 0.101, 0.901, 0.901)
    private fun snapshot() = RegionPreviewSnapshot(
        session = RecordingSessionId("ui-preview", 2), geometryGeneration = 3, frameSequence = 1,
        observation = CaptureGeometryObservation(geometry, CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY,
            CaptureGeometryProvenance.DEFAULT_DISPLAY_METRICS_AND_CONTENT_CALLBACK, 10),
        rasterWidth = 540, rasterHeight = 960,
        sourceTransform = RegionPreviewTransform(FloatArray(16) { if (it % 5 == 0) 1f else 0f }),
        sourceFrameTimestampNs = 20_000, capturedAtMonotonicUs = 20, expiresAtMonotonicUs = 60_000_020,
    )

    @Test fun explicitModesAndDisclosureDoNotClaimOtherAppOrLivePreview() {
        assertEquals(3, RecordingSelectionMode.entries.size)
        assertTrue(recordingPreviewScopeDisclosure.contains("当前可见的 ESV 浏览器"))
        assertTrue(recordingPreviewScopeDisclosure.contains("不是实时预览"))
        assertTrue(recordingPreviewScopeDisclosure.contains("尺寸相同不能证明来源"))
        assertTrue(recordingPreviewScopeDisclosure.contains("不支持预览任意其他应用"))
        assertEquals("授权帧选区", recordingSelectionModeLabel(RecordingSelectionMode.AUTHORIZED_FRAME))
        assertEquals("比例示意录制", recordingSelectionModeLabel(RecordingSelectionMode.PROPORTIONAL))
    }

    @Test fun previewUsesOriginalSourcePixelEdgesNotBoundedRasterPixels() {
        val token = snapshot()
        assertNull(recordingPreviewSelectionError(token, bounds, 30))
        val recipe = RegionCaptureRecipe(bounds, token.observation.geometry, token.observation.scopeEvidence)
        assertEquals(PixelCrop(110, 194, 972, 1728), recipe.pixelCrop)
        assertEquals(862, recipe.encodedWidth)
        assertEquals(1534, recipe.encodedHeight)
        assertTrue(recipe.encodedWidth > token.rasterWidth)
    }

    @Test fun sourceAspectFitAndLetterboxHitTestMatchShownRasterExtent() {
        val token = snapshot()
        val viewport = recordingViewport(800.0, 400.0, token.observation.geometry)
        assertEquals(225.0, viewport.width, 0.00001)
        assertEquals(400.0, viewport.height, 0.00001)
        assertEquals(287.5, viewport.left, 0.00001)
        fun hit(x: Double, y: Double) = recordingRegionHit(RegionNormalizedRect.FULL,
            (x - viewport.left) / viewport.width, (y - viewport.top) / viewport.height,
            24 / viewport.width, 24 / viewport.height)
        assertNull(hit(100.0, 200.0))
        assertNull(hit(700.0, 200.0))
        assertEquals(RecordingRegionHandle.MOVE, hit(400.0, 200.0))
    }

    @Test fun expiredOrFutureSnapshotCannotConfirm() {
        val token = snapshot()
        assertNotNull(recordingPreviewSelectionError(token, bounds, token.capturedAtMonotonicUs - 1))
        assertNull(recordingPreviewSelectionError(token, bounds, token.capturedAtMonotonicUs))
        assertNotNull(recordingPreviewSelectionError(token, bounds, token.expiresAtMonotonicUs))
        assertFalse(RecordingPreviewUiFence(token).confirm(token, bounds, token.expiresAtMonotonicUs, true, true))
    }

    @Test fun drawingReadinessEnabledAndExplicitIdentityAreAllRequired() {
        val token = snapshot()
        val fence = RecordingPreviewUiFence(token)
        assertFalse(fence.confirm(token, bounds, 30, enabled = false, drawingAvailable = true))
        assertFalse(fence.confirm(token, bounds, 30, enabled = true, drawingAvailable = false))
        assertFalse(fence.confirm(token.copy(frameSequence = 2), bounds, 30, true, true))
        assertFalse(fence.confirm(token.copy(geometryGeneration = 4), bounds, 30, true, true))
        assertFalse(fence.confirm(token.copy(session = RecordingSessionId("other", 2)), bounds, 30, true, true))
        assertFalse(fence.confirm(token.copy(sourceFrameTimestampNs = 19_000), bounds, 30, true, true))
        assertTrue(fence.confirm(token, bounds, 30, true, true))
    }

    @Test fun tooSmallSelectionRemainsCorrectableButCannotBeConfirmed() {
        val token = snapshot()
        val fence = RecordingPreviewUiFence(token)
        val tiny = RegionNormalizedRect(0.1, 0.1, 0.12, 0.12)
        assertNotNull(recordingPreviewSelectionError(token, tiny, 30))
        assertFalse(fence.confirm(token, tiny, 30, true, true))
        assertTrue(fence.confirm(token, bounds, 30, true, true))
    }

    @Test fun highResolutionFullFrameMustShrinkRatherThanDowngradeOutput() {
        val original = snapshot()
        val source = CaptureDisplayGeometry(2160, 3840, 0)
        val token = original.copy(observation = original.observation.copy(geometry = source))
        assertNotNull(recordingPreviewSelectionError(token, RegionNormalizedRect.FULL, 30))
        assertNull(recordingPreviewSelectionError(token, RegionNormalizedRect(0.2, 0.2, 0.8, 0.6), 30))
    }

    @Test fun explicitConfirmationIsOneShotAndRetirementCannotConfirmAgain() {
        val token = snapshot()
        val fence = RecordingPreviewUiFence(token)
        assertTrue(fence.confirm(token, bounds, 30, true, true))
        assertFalse(fence.confirm(token, bounds, 31, true, true))
        assertTrue(fence.retire())
        assertFalse(fence.retire())
        assertFalse(fence.confirm(token, bounds, 32, true, true))
        assertFalse(fence.cancel())
    }

    @Test fun cancelBackgroundOrDismissNeverProducesConfirmation() {
        val token = snapshot()
        val fence = RecordingPreviewUiFence(token)
        assertTrue(fence.cancel())
        assertFalse(fence.cancel())
        assertFalse(fence.confirm(token, bounds, 30, true, true))
        assertTrue(fence.retire())
        assertFalse(fence.retire())
        assertFalse(fence.confirm(token, bounds, 31, true, true))
    }

    @Test fun lifecycleCancellationCanRevokeAConfirmClickBeforeRetirement() {
        val token = snapshot()
        val fence = RecordingPreviewUiFence(token)
        assertTrue(fence.confirm(token, bounds, 30, true, true))
        assertTrue(fence.cancel())
        assertFalse(fence.cancel())
        assertTrue(fence.retire())
        assertFalse(fence.confirm(token, bounds, 31, true, true))
    }

    @Test fun unconfirmedDisposalCannotCreateAConfirmation() {
        val token = snapshot()
        val fence = RecordingPreviewUiFence(token)
        assertTrue(fence.retire())
        assertFalse(fence.confirm(token, bounds, 30, true, true))
    }
}
