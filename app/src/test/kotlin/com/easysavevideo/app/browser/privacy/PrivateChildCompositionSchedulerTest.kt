package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

/** Executes the production scheduler; no Android attachment, compositor or privacy acceptance. */
class PrivateChildCompositionSchedulerTest {
    private class Harness {
        var current = true
        var attached = false
        var oldViewDetached = true
        var dialogRetired = true
        var requests = 0
        var checks = 0
        var throwOnCheck = false
        var invalidateDuringCheck = false
        val scheduler: PrivateChildCompositionScheduler = PrivateChildCompositionScheduler(
            isCurrent = { current }, acknowledge = {
                checks++
                if (throwOnCheck) error("technical test failure")
                if (invalidateDuringCheck) { invalidateDuringCheck = false; invalidate() }
                attached && oldViewDetached && dialogRetired
            }, requestComposition = { requests++ })
        private fun invalidate() = scheduler.hierarchyChanged()
        fun apply() = scheduler.childApplied(scheduler.ticket)
    }

    @Test fun outerApplyDoesNothingAndLateChildAttachmentNeedsFreshRealChildApply() {
        val h = Harness()
        // Outer composition has applied; no child SideEffect has run.
        assertEquals(0, h.checks); assertFalse(h.scheduler.acknowledged)
        assertFalse(h.apply()) // Actual first child apply can still precede native attachment.
        val beforeAttach = h.scheduler.ticket
        h.attached = true; h.scheduler.hierarchyChanged()
        assertEquals(1, h.requests); assertEquals(1, h.checks)
        assertFalse(h.scheduler.childApplied(beforeAttach))
        assertFalse(h.scheduler.acknowledged)
        assertTrue(h.apply()) // No browser/load/progress state changed.
        assertEquals(2, h.checks)
    }
    @Test fun ordinaryShellApplyBeforeOldAndroidViewDetachProgressesWithoutBrowserUpdate() {
        val h = Harness().apply { attached = true; oldViewDetached = false }
        assertFalse(h.apply()); assertFalse(h.scheduler.acknowledged)
        val old = h.scheduler.ticket
        h.oldViewDetached = true; h.scheduler.hierarchyChanged()
        assertEquals(1, h.checks) // Native detach only requested fresh composition.
        assertFalse(h.scheduler.childApplied(old))
        assertTrue(h.apply()); assertEquals(2, h.checks)
    }
    @Test fun independentDialogRetirementRequestsApplyAndUncertainTeardownStaysBlocked() {
        val h = Harness().apply { attached = true; dialogRetired = false }
        assertFalse(h.apply())
        // A failed native teardown has not emitted successful unregister or made proof true.
        assertFalse(h.apply()); assertEquals(0, h.requests)
        h.dialogRetired = true; h.scheduler.hierarchyChanged()
        assertFalse(h.scheduler.acknowledged); assertEquals(2, h.checks)
        assertTrue(h.apply()); assertEquals(3, h.checks)
    }
    @Test fun lateOldRouteAndTicketCannotAcknowledgeReplacementOwner() {
        val old = Harness().apply { attached = true }
        val oldTicket = old.scheduler.ticket
        old.current = false
        val replacement = Harness().apply { attached = true }
        old.scheduler.hierarchyChanged()
        assertEquals(0, old.requests); assertFalse(old.scheduler.childApplied(oldTicket))
        assertFalse(replacement.scheduler.childApplied(oldTicket))
        assertEquals(0, replacement.checks); assertFalse(replacement.scheduler.acknowledged)
        assertTrue(replacement.apply())
    }
    @Test fun firstLayoutCanRequestOneFreshApplyButRepeatedLayoutCannotSpin() {
        val h = Harness()
        assertFalse(h.apply())
        h.scheduler.childLaidOut(h.scheduler.ticket)
        assertEquals(1, h.requests); assertEquals(1, h.checks)
        repeat(20) { h.scheduler.childLaidOut(h.scheduler.ticket) }
        assertEquals(1, h.requests); assertFalse(h.scheduler.acknowledged)
        h.attached = true; h.scheduler.hierarchyChanged()
        assertTrue(h.apply())
    }
    @Test fun coverOrAttachmentChangeDuringAcknowledgementCannotAcceptOldTicket() {
        val h = Harness().apply { attached = true; invalidateDuringCheck = true }
        val beforeCover = h.scheduler.ticket
        assertFalse(h.scheduler.childApplied(beforeCover))
        assertFalse(h.scheduler.acknowledged); assertEquals(1, h.requests)
        assertTrue(h.apply()); assertEquals(2, h.checks)
    }
    @Test fun firstOrdinaryDepartureTurnRevokesInputAndRequiresTheNextRealChildApply() {
        var nativeOrdinaryTurnStarted = false
        var compositionApplied = false
        var requests = 0
        var checks = 0
        lateinit var scheduler: PrivateChildCompositionScheduler
        scheduler = PrivateChildCompositionScheduler(isCurrent = { true }, acknowledge = {
            checks++; compositionApplied = true
            if (!nativeOrdinaryTurnStarted) {
                nativeOrdinaryTurnStarted = true
                // The actual beginOrdinaryPresentation synchronously revokes root input.
                compositionApplied = false
                scheduler.hierarchyChanged()
            }
            compositionApplied
        }, requestComposition = { requests++ })
        val initial = scheduler.ticket
        assertFalse(scheduler.childApplied(initial))
        assertTrue(nativeOrdinaryTurnStarted); assertFalse(scheduler.acknowledged)
        assertEquals(1, requests); assertEquals(1, checks)
        assertFalse(scheduler.childApplied(initial))
        assertTrue(scheduler.childApplied(scheduler.ticket))
        assertEquals(2, checks); assertTrue(compositionApplied)
    }
    @Test fun repeatedAppliedSideEffectDoesNotReuseOldAuthorityAfterHierarchyAba() {
        val h = Harness().apply { attached = true }
        val old = h.scheduler.ticket
        assertTrue(h.apply()); assertTrue(h.apply()); assertEquals(1, h.checks)
        h.attached = false; h.scheduler.hierarchyChanged()
        h.attached = true; h.scheduler.hierarchyChanged()
        assertFalse(h.scheduler.childApplied(old)); assertEquals(1, h.checks)
        assertTrue(h.apply()); assertEquals(2, h.checks)
    }
    @Test fun exceptionAndCloseNeverCreateAcknowledgementOrFurtherRequests() {
        val h = Harness().apply { attached = true; throwOnCheck = true }
        assertFalse(h.apply()); assertFalse(h.scheduler.acknowledged)
        h.throwOnCheck = false; h.scheduler.hierarchyChanged()
        assertTrue(h.apply())
        val old = h.scheduler.ticket
        h.scheduler.close(); h.scheduler.hierarchyChanged(); h.scheduler.childLaidOut(old)
        assertFalse(h.scheduler.childApplied(old)); assertFalse(h.scheduler.acknowledged)
        assertEquals(1, h.requests)
    }
}
