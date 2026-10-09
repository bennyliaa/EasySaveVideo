package com.easysavevideo.app.recording.ui

import org.junit.Assert.*
import org.junit.Test

class RecordingPreviewWindowFocusFenceTest {
    @Test fun initialCompositionCanWaitForFirstRealDialogFocus() {
        val fence = RecordingPreviewWindowFocusFence()
        assertFalse(fence.onFocusChanged(false, false))
        assertFalse(fence.onFocusChanged(false, false))
        assertTrue(fence.canUseWindow)
        assertFalse(fence.onFocusChanged(true, false))
        assertTrue(fence.canUseWindow)
    }

    @Test fun focusLossAfterAcquisitionRevokesWithoutAnyLifecyclePause() {
        val fence = RecordingPreviewWindowFocusFence()
        fence.onFocusChanged(true, false)
        assertTrue(fence.onFocusChanged(false, false))
        assertFalse(fence.canUseWindow)
        assertFalse(fence.onFocusChanged(false, false))
    }

    @Test fun regainingFocusCannotResurrectRevokedPixelsOrConfirmation() {
        val fence = RecordingPreviewWindowFocusFence()
        fence.onFocusChanged(true, false)
        assertTrue(fence.onFocusChanged(false, false))
        assertFalse(fence.onFocusChanged(true, false))
        assertFalse(fence.canUseWindow)
    }

    @Test fun intentionalConfirmedDismissalDoesNotCancelAcceptedSelection() {
        val fence = RecordingPreviewWindowFocusFence()
        fence.onFocusChanged(true, false)
        assertFalse(fence.onFocusChanged(false, intentionallyRetiring = true))
        fence.retire()
        assertFalse(fence.canUseWindow)
        assertFalse(fence.onFocusChanged(false, false))
    }

    @Test fun focusLossBeforeConfirmCannotBeReclassifiedAsIntentionalRetirement() {
        val fence = RecordingPreviewWindowFocusFence()
        fence.onFocusChanged(true, false)
        assertTrue(fence.onFocusChanged(false, false))
        assertFalse(fence.onFocusChanged(false, intentionallyRetiring = true))
        assertFalse(fence.canUseWindow)
    }

    @Test fun retiredListenerCannotAffectANewDialog() {
        val old = RecordingPreviewWindowFocusFence()
        old.onFocusChanged(true, false)
        old.retire()
        val fresh = RecordingPreviewWindowFocusFence()
        assertFalse(old.onFocusChanged(false, false))
        assertFalse(old.onFocusChanged(true, false))
        assertFalse(old.canUseWindow)
        assertTrue(fresh.canUseWindow)
        fresh.onFocusChanged(true, false)
        assertTrue(fresh.onFocusChanged(false, false))
    }
}
