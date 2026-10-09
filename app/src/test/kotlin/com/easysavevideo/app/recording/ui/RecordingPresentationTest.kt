package com.easysavevideo.app.recording.ui

import com.easysavevideo.recording.*
import org.junit.Assert.*
import org.junit.Test

class RecordingPresentationTest {
    private val geometry = CaptureDisplayGeometry(1080, 1920, 0, CaptureInsets(top = 80, bottom = 96))
    private val setup = RecordingRegionSetup(geometry, CaptureScopeEvidence.API34_PLUS_EXPLICIT_DEFAULT_DISPLAY, true)
    private val bounds = RegionNormalizedRect(0.2, 0.2, 0.8, 0.8)
    private fun recipe() = RegionCaptureRecipe(bounds, geometry, requireNotNull(setup.scopeEvidence), 123)

    @Test fun fullSelectionIncludesInsetsAndFitsEditorExactly() {
        assertNull(recordingRegionError(RegionNormalizedRect.FULL, setup))
        val full = RegionCaptureRecipe(RegionNormalizedRect.FULL, geometry, requireNotNull(setup.scopeEvidence))
        assertEquals(PixelCrop(0, 0, 1080, 1920), full.pixelCrop)
        assertTrue(recordingEffectiveCropSummary(full).contains("1080 × 1920"))
    }

    @Test fun croppedPixelEdgesRoundInwardAndAreShown() {
        val selection = RegionCaptureRecipe(RegionNormalizedRect(0.101, 0.101, 0.901, 0.901), geometry, requireNotNull(setup.scopeEvidence))
        assertEquals(PixelCrop(110, 194, 972, 1728), selection.pixelCrop)
        val text = recordingEffectiveCropSummary(selection)
        assertTrue(text.contains("110")); assertTrue(text.contains("194")); assertTrue(text.contains("862 × 1534"))
    }

    @Test fun tabletOrHighResolutionFullSelectionIsRejectedWithoutImplicitDownscale() {
        val large = setup.copy(geometry = CaptureDisplayGeometry(2160, 3840, 0))
        assertNotNull(recordingRegionError(RegionNormalizedRect.FULL, large))
        assertNull(recordingRegionError(RegionNormalizedRect(0.2, 0.2, 0.8, 0.6), large))
        val square = setup.copy(geometry = CaptureDisplayGeometry(1920, 1920, 0))
        assertNotNull(recordingRegionError(RegionNormalizedRect.FULL, square))
    }

    @Test fun tinyUnobservedOrUnknownScopeCannotBeConfirmed() {
        assertNotNull(recordingRegionError(RegionNormalizedRect(0.1, 0.1, 0.12, 0.12), setup))
        assertNotNull(recordingRegionError(bounds, setup.copy(geometry = null)))
        assertNotNull(recordingRegionError(bounds, setup.copy(scopeEvidence = null)))
        assertFalse(recordingSelectionIsCurrent(null, setup))
        assertFalse(recordingSelectionIsCurrent(recipe(), null))
    }

    @Test fun geometryRotationInsetsVersionOrRequestedScopeChangesInvalidateSelection() {
        assertTrue(recordingSelectionIsCurrent(recipe(), setup))
        assertFalse(recordingSelectionIsCurrent(recipe(), setup.copy(geometry = geometry.copy(width = 1078))))
        assertFalse(recordingSelectionIsCurrent(recipe(), setup.copy(geometry = geometry.copy(rotationDegrees = 180))))
        assertFalse(recordingSelectionIsCurrent(recipe(), setup.copy(geometry = geometry.copy(insets = CaptureInsets(top = 81, bottom = 96)))))
        assertFalse(recordingSelectionIsCurrent(recipe(), setup.copy(geometry = geometry.copy(transformVersion = 2))))
        assertFalse(recordingSelectionIsCurrent(recipe(), setup.copy(scopeEvidence = CaptureScopeEvidence.API31_TO_33_DEFAULT_DISPLAY)))
        // An entitlement change affects admission, not coordinate identity.
        assertTrue(recordingSelectionIsCurrent(recipe(), setup.copy(canStart = false)))
    }

    @Test fun viewportFitsWholeDisplayOnPhoneAndTabletWithoutDoubleRotation() {
        val portrait = recordingViewport(800.0, 400.0, geometry)
        assertEquals(225.0, portrait.width, 0.0001)
        assertEquals(400.0, portrait.height, 0.0001)
        assertEquals(287.5, portrait.left, 0.0001)
        assertEquals(0.0, portrait.top, 0.0001)
        val landscapeGeometry = CaptureDisplayGeometry(1920, 1080, 90)
        val landscape = recordingViewport(300.0, 500.0, landscapeGeometry)
        assertEquals(300.0, landscape.width, 0.0001)
        assertEquals(168.75, landscape.height, 0.0001)
        assertEquals(165.625, landscape.top, 0.0001)
    }

    @Test fun invalidViewportRejected() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            rejects { recordingViewport(invalid, 300.0, geometry) }
            rejects { recordingViewport(300.0, invalid, geometry) }
        }
    }

    @Test fun hitTestingHasBodyCornersAndEntireEdgesButExcludesLetterbox() {
        assertEquals(RecordingRegionHandle.MOVE, recordingRegionHit(bounds, 0.5, 0.5, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.TOP_LEFT, recordingRegionHit(bounds, 0.2, 0.2, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.TOP_RIGHT, recordingRegionHit(bounds, 0.8, 0.2, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.BOTTOM_LEFT, recordingRegionHit(bounds, 0.2, 0.8, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.BOTTOM_RIGHT, recordingRegionHit(bounds, 0.8, 0.8, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.LEFT, recordingRegionHit(bounds, 0.2, 0.35, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.RIGHT, recordingRegionHit(bounds, 0.8, 0.65, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.TOP, recordingRegionHit(bounds, 0.35, 0.2, 0.03, 0.03))
        assertEquals(RecordingRegionHandle.BOTTOM, recordingRegionHit(bounds, 0.65, 0.8, 0.03, 0.03))
        assertNull(recordingRegionHit(RegionNormalizedRect.FULL, -0.001, 0.5, 0.03, 0.03))
        assertNull(recordingRegionHit(RegionNormalizedRect.FULL, 0.5, 1.001, 0.03, 0.03))
        assertNull(recordingRegionHit(bounds, 0.1, 0.1, 0.03, 0.03))
    }

    @Test fun invalidHitInputsAndDragDeltasCannotAlterSelection() {
        assertNull(recordingRegionHit(bounds, Double.NaN, 0.5, 0.03, 0.03))
        assertNull(recordingRegionHit(bounds, 0.5, 0.5, 0.0, 0.03))
        assertNull(recordingRegionHit(bounds, 0.5, 0.5, Double.POSITIVE_INFINITY, 0.03))
        assertSame(bounds, dragRecordingRegion(bounds, RecordingRegionHandle.MOVE, Double.NaN, 0.1))
        assertSame(bounds, dragRecordingRegion(bounds, RecordingRegionHandle.TOP_LEFT, 0.1, Double.NEGATIVE_INFINITY))
    }

    @Test fun bodyTranslationClampsAtDisplayAndPreservesSize() {
        val result = dragRecordingRegion(bounds, RecordingRegionHandle.MOVE, 4.0, -4.0)
        assertEquals(1.0, result.right, 0.000001)
        assertEquals(0.0, result.top, 0.000001)
        assertEquals(bounds.right - bounds.left, result.right - result.left, 0.000001)
        assertEquals(bounds.bottom - bounds.top, result.bottom - result.top, 0.000001)
        assertEquals(RegionNormalizedRect.FULL, dragRecordingRegion(RegionNormalizedRect.FULL, RecordingRegionHandle.MOVE, 0.2, 0.4))
    }

    @Test fun everyResizeHandleAdjustsOnlyItsOwnedEdges() {
        RecordingRegionHandle.entries.filter { it != RecordingRegionHandle.MOVE }.forEach { handle ->
            val next = dragRecordingRegion(bounds, handle, 0.03, 0.04)
            val name = handle.name
            assertEquals(if (name.contains("LEFT")) 0.23 else 0.2, next.left, 0.000001)
            assertEquals(if (name.contains("RIGHT")) 0.83 else 0.8, next.right, 0.000001)
            assertEquals(if (name.contains("TOP")) 0.24 else 0.2, next.top, 0.000001)
            assertEquals(if (name.contains("BOTTOM")) 0.84 else 0.8, next.bottom, 0.000001)
        }
    }

    @Test fun resizingCannotInvertOrLeaveDisplay() {
        RecordingRegionHandle.entries.forEach { handle ->
            for (dx in listOf(-10.0, 10.0)) for (dy in listOf(-10.0, 10.0)) {
                val next = dragRecordingRegion(bounds, handle, dx, dy)
                assertTrue(next.left >= 0 && next.top >= 0 && next.right <= 1 && next.bottom <= 1)
                assertTrue(next.right > next.left && next.bottom > next.top)
            }
        }
    }

    @Test fun closedRowsRemainOrderedAndDetachedAndNeverFakeResult() {
        val rows = mutableListOf(RecordingSegmentRow(0, 250_000, false), RecordingSegmentRow(1, 400_000, true))
        val state = RecordingSegmentsPresentation(RecordingSegmentsPhase.PAUSED, rows, effectiveResumeCount = 1)
        rows.clear()
        assertEquals(2, state.closedSegments.size)
        assertEquals(650_000L, state.effectiveDurationUs)
        assertFalse(state.verifiedOutputAvailable)
        assertTrue(state.active)
        assertTrue(state.canStop)
        assertTrue(state.canDiscard)
        try { (state.closedSegments as MutableList<RecordingSegmentRow>).clear(); fail("Must be immutable") }
        catch (_: UnsupportedOperationException) { }
        rejects { RecordingSegmentsPresentation(RecordingSegmentsPhase.PAUSED, listOf(RecordingSegmentRow(1, 250_000, false))) }
        rejects { RecordingSegmentsPresentation(RecordingSegmentsPhase.PAUSED, listOf(RecordingSegmentRow(0, 250_000, false)), effectiveDurationUs = 249_999) }
    }

    @Test fun effectiveResumesBoundedAt32AndInitialPlusResumesAt33() {
        val rows = (0..32).map { RecordingSegmentRow(it, 250_000, false) }
        val full = RecordingSegmentsPresentation(RecordingSegmentsPhase.PAUSED, rows, effectiveResumeCount = 32)
        assertEquals(33, full.closedSegments.size)
        assertTrue(full.canStop); assertTrue(full.canDiscard)
        rejects { RecordingSegmentsPresentation(RecordingSegmentsPhase.PAUSED, effectiveResumeCount = 33) }
        rejects { RecordingSegmentRow(33, 250_000, false) }
        rejects { RecordingSegmentRow(0, Long.MAX_VALUE, false) }
    }

    @Test fun recoveryCanRetryWithoutNewCaptureAndCancellationDoesNotClaimPublication() {
        val recovery = RecordingSegmentsPresentation(RecordingSegmentsPhase.RECOVERY_REQUIRED,
            listOf(RecordingSegmentRow(0, 250_000, false)), canRetryProcessing = true)
        assertFalse(recovery.active); assertFalse(recovery.canStop); assertTrue(recovery.canDiscard)
        assertFalse(recovery.verifiedOutputAvailable)
        val cancelling = RecordingSegmentsPresentation(RecordingSegmentsPhase.CANCELLING)
        assertTrue(cancelling.active); assertFalse(cancelling.canStop); assertFalse(cancelling.canDiscard)
        val cancelled = RecordingSegmentsPresentation(RecordingSegmentsPhase.CANCELLED)
        assertFalse(cancelled.active); assertFalse(cancelled.canStop); assertFalse(cancelled.canDiscard)
    }

    @Test fun successfulEditorEntryRequiresPublishedPhaseAndRetryRequiresRecoveryPhase() {
        rejects { RecordingSegmentsPresentation(RecordingSegmentsPhase.PROCESSING, verifiedOutputAvailable = true) }
        rejects { RecordingSegmentsPresentation(RecordingSegmentsPhase.RECORDING, canRetryProcessing = true) }
        val success = RecordingSegmentsPresentation(RecordingSegmentsPhase.SUCCEEDED, verifiedOutputAvailable = true)
        assertTrue(success.verifiedOutputAvailable); assertFalse(success.active); assertFalse(success.canDiscard)
        assertTrue(recordingSegmentsPhaseLabel(RecordingSegmentsPhase.VERIFYING).contains("尚未"))
        assertTrue(recordingSegmentsPhaseLabel(RecordingSegmentsPhase.RECOVERY_REQUIRED).contains("保存状态待核对"))
    }

    @Test fun successfulRawCleanupIsSeparateFromDiscardAndRequiresRetainedRows() {
        val published = RecordingSegmentsPresentation(RecordingSegmentsPhase.SUCCEEDED,
            listOf(RecordingSegmentRow(0, 250_000, true)), verifiedOutputAvailable = true)
        assertFalse(published.canDiscard)
        assertTrue(published.canCleanRetainedSources)
        assertTrue(published.verifiedOutputAvailable)
        val cleaned = RecordingSegmentsPresentation(RecordingSegmentsPhase.SUCCEEDED, verifiedOutputAvailable = true)
        assertFalse(cleaned.canCleanRetainedSources)
        assertTrue(cleaned.verifiedOutputAvailable)
        val pending = RecordingSegmentsPresentation(RecordingSegmentsPhase.VERIFYING,
            listOf(RecordingSegmentRow(0, 250_000, true)))
        assertFalse(pending.canCleanRetainedSources)
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid presentation input to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
    @org.junit.Test fun repeatedDiscardStopAndAdmittedCommitCannotHideWaitingRecovery() {
        for (requested in RecordingSegmentsPhase.entries) {
            val result = recordingPreserveWaitingPhase(RecordingSegmentsPhase.RECOVERY_REQUIRED, requested)
            if (requested in setOf(RecordingSegmentsPhase.SUCCEEDED, RecordingSegmentsPhase.FAILED, RecordingSegmentsPhase.CANCELLED))
                org.junit.Assert.assertEquals(requested, result)
            else org.junit.Assert.assertEquals(RecordingSegmentsPhase.RECOVERY_REQUIRED, result)
        }
        org.junit.Assert.assertEquals(RecordingSegmentsPhase.CANCELLING,
            recordingPreserveWaitingPhase(RecordingSegmentsPhase.PROCESSING, RecordingSegmentsPhase.CANCELLING))
    }

    @org.junit.Test fun newExplicitRetryOrCleanupDoesNotInheritPriorOwnersStickyWarning() {
        for (phase in listOf(RecordingSegmentsPhase.VERIFYING, RecordingSegmentsPhase.CANCELLING))
            org.junit.Assert.assertEquals(phase, recordingPreserveWaitingPhase(
                RecordingSegmentsPhase.RECOVERY_REQUIRED, phase, sameOwner = false))
    }

}
