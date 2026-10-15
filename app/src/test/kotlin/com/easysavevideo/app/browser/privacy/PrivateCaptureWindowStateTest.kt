package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

/** Production admission/snapshot transitions; no platform/compositor acceptance is claimed. */
class PrivateCaptureWindowStateTest {
    private val descriptor = PrivateCaptureDescriptor(PrivateCaptureAudio.SILENT, PrivateCaptureMode.BASIC)
    private val changedDescriptor = PrivateCaptureDescriptor(PrivateCaptureAudio.DEVICE_AUDIO, PrivateCaptureMode.BASIC)
    private fun layout() = PrivateCaptureWindowLayout(1, PrivateCaptureWindowRect(0, 0, 800, 600),
        PrivateCaptureWindowRect(0, 60, 800, 500), PrivateCaptureWindowRect(0, 0, 800, 600),
        PrivateCaptureWindowRect(0, 0, 800, 600), PrivateCaptureWindowRect(0, 24, 0, 24),
        PrivateCaptureWindowRect(0, 0, 0, 0), PrivateCaptureWindowRect(0, 0, 0, 0), 0, 0, 320, 2f, 1f, 1)
    private fun snapshot() = PrivateCaptureWindowSnapshot(Any(), Any(), Any(), Any(), Any(), Any(), Any(),
        listOf(Any(), Any()), layout())
    private fun copy(s: PrivateCaptureWindowSnapshot, layout: PrivateCaptureWindowLayout = s.layout) =
        PrivateCaptureWindowSnapshot(s.window, s.decor, s.root, s.view, s.document, s.presentation,
            s.windowToken, s.parents.toList(), layout)
    private class Fixture(val state: PrivateCaptureWindowState, val target: Any, val owner: Any,
        val snapshot: PrivateCaptureWindowSnapshot, val evidence: Any)
    private fun ready(): Fixture {
        val state = PrivateCaptureWindowState(); val target = Any(); val owner = Any(); val snapshot = snapshot(); val evidence = Any()
        assertTrue(state.request(target, owner, 7, descriptor))
        assertTrue(state.acknowledgeComposition(7, descriptor))
        val draw = state.drawn(snapshot)!!; val ticket = state.committed(draw, copy(snapshot))!!
        assertTrue(state.register(ticket, evidence))
        return Fixture(state, target, owner, snapshot, evidence)
    }
    @Test fun noRequestOrActualCompositionMeansNoFrameEvidence() {
        val state = PrivateCaptureWindowState()
        assertNull(state.drawn(snapshot()))
        assertFalse(state.acknowledgeComposition(7, descriptor))
        assertTrue(state.request(Any(), Any(), 7, descriptor))
        assertNull(state.drawn(snapshot()))
        assertFalse(state.acknowledgeComposition(8, descriptor))
        assertFalse(state.acknowledgeComposition(7, changedDescriptor))
        assertNull(state.drawn(snapshot()))
    }
    @Test fun regionAndDuplicateRequestsStayClosed() {
        val state = PrivateCaptureWindowState()
        assertFalse(state.request(Any(), Any(), 1, descriptor.copy(mode = PrivateCaptureMode.REGION)))
        assertTrue(state.request(Any(), Any(), 1, descriptor))
        assertFalse(state.request(Any(), Any(), 2, descriptor))
    }
    @Test fun exactFrameIdentityAllowsOneConsumeAndOneOpen() {
        val f = ready()
        assertTrue(f.state.isCurrent(f.evidence, f.target, f.owner, copy(f.snapshot)))
        assertFalse(f.state.opened(f.evidence))
        assertTrue(f.state.consume(f.evidence, f.target, f.owner, copy(f.snapshot)))
        assertFalse(f.state.consume(f.evidence, f.target, f.owner, f.snapshot))
        assertTrue(f.state.opened(f.evidence)); assertTrue(f.state.opened)
        assertFalse(f.state.opened(f.evidence))
    }
    @Test fun unknownEvidenceTargetAndOwnerNeverConsume() {
        val f = ready()
        assertFalse(f.state.consume(Any(), f.target, f.owner, f.snapshot))
        assertFalse(f.state.consume(f.evidence, Any(), f.owner, f.snapshot))
        assertFalse(f.state.consume(f.evidence, f.target, Any(), f.snapshot))
        assertTrue(f.state.consume(f.evidence, f.target, f.owner, f.snapshot))
    }
    @Test fun exactOwnedWindowDecorRootAndAttachmentMustMatch() {
        val f = ready(); val s = f.snapshot
        val wrong = listOf(
            PrivateCaptureWindowSnapshot(Any(), s.decor, s.root, s.view, s.document, s.presentation, s.windowToken, s.parents, s.layout),
            PrivateCaptureWindowSnapshot(s.window, Any(), s.root, s.view, s.document, s.presentation, s.windowToken, s.parents, s.layout),
            PrivateCaptureWindowSnapshot(s.window, s.decor, Any(), s.view, s.document, s.presentation, s.windowToken, s.parents, s.layout),
            PrivateCaptureWindowSnapshot(s.window, s.decor, s.root, s.view, s.document, s.presentation, Any(), s.parents, s.layout),
        )
        wrong.forEach { assertFalse(f.state.isCurrent(f.evidence, f.target, f.owner, it)) }
    }
    @Test fun sceneGeometryImeAndRotationDoNotRevokeOwnedAttachment() {
        val f = ready(); val l = f.snapshot.layout
        listOf(l.copy(generation = 2), l.copy(root = l.root.copy(right = 801)), l.copy(view = l.view.copy(top = 61)),
            l.copy(metrics = l.metrics.copy(bottom = 601)), l.copy(maximumMetrics = l.maximumMetrics.copy(bottom = 601)),
            l.copy(systemInsets = l.systemInsets.copy(top = 25)), l.copy(imeInsets = l.imeInsets.copy(bottom = 300)),
            l.copy(cutoutInsets = l.cutoutInsets.copy(top = 30)), l.copy(rotation = 1), l.copy(displayId = 2),
            l.copy(densityDpi = 480), l.copy(density = 3f), l.copy(fontScale = 1.2f), l.copy(orientation = 2)).forEach {
            assertTrue(f.state.isCurrent(f.evidence, f.target, f.owner, copy(f.snapshot, it)))
        }
    }
    @Test fun sceneChangeBetweenDrawAndCommitKeepsOwnedWindowEvidence() {
        val state = PrivateCaptureWindowState(); val s = snapshot()
        state.request(Any(), Any(), 1, descriptor); state.acknowledgeComposition(1, descriptor)
        val draw = state.drawn(s)!!
        assertNotNull(state.committed(draw, copy(s, s.layout.copy(rotation = 1))))
        assertFalse(state.isInvalidated)
    }

    @Test fun sceneChangeBetweenDrawsDoesNotInventNativeStopOrInvalidateWindow() {
        val state = PrivateCaptureWindowState(); val s = snapshot()
        state.request(Any(), Any(), 1, descriptor); state.acknowledgeComposition(1, descriptor)
        assertNotNull(state.drawn(s))
        assertNotNull(state.drawn(copy(s, s.layout.copy(fontScale = 2f))))
        assertFalse(state.isInvalidated)
    }
    @Test fun replacementAttachmentBetweenDrawAndCommitStillLatchesInvalidation() {
        val state = PrivateCaptureWindowState(); val s = snapshot()
        state.request(Any(), Any(), 1, descriptor); state.acknowledgeComposition(1, descriptor)
        val draw = state.drawn(s)!!
        val replacement = PrivateCaptureWindowSnapshot(s.window, s.decor, s.root, s.view, s.document,
            s.presentation, Any(), s.parents, s.layout)
        assertNull(state.committed(draw, replacement)); assertTrue(state.isInvalidated)
    }
    @Test fun pageDocumentAndAncestryChangesStayWithinTheSameOwnedWindow() {
        val f = ready(); val s = f.snapshot
        val changed = PrivateCaptureWindowSnapshot(s.window, s.decor, s.root, Any(), Any(), Any(),
            s.windowToken, listOf(Any()), s.layout)
        assertTrue(f.state.isCurrent(f.evidence, f.target, f.owner, changed))
    }

    @Test fun supersededDrawAndFabricatedTicketsAreRejected() {
        val state = PrivateCaptureWindowState(); val s = snapshot()
        state.request(Any(), Any(), 1, descriptor); state.acknowledgeComposition(1, descriptor)
        val old = state.drawn(s)!!; val next = state.drawn(copy(s))!!
        assertNull(state.committed(old, s))
        assertFalse(state.register(PrivateCaptureWindowTicket(next), Any()))
        val ticket = state.committed(next, s)!!
        assertTrue(state.register(ticket, Any())); assertFalse(state.register(ticket, Any()))
        assertNull(state.committed(next, s))
    }
    @Test fun queuedStopBeforeOpeningRejectsEvidenceAndNeverResets() {
        val f = ready()
        assertTrue(f.state.consume(f.evidence, f.target, f.owner, f.snapshot))
        f.state.invalidate()
        assertFalse(f.state.opened(f.evidence)); assertFalse(f.state.opened)
        assertFalse(f.state.isCurrent(f.evidence, f.target, f.owner, f.snapshot))
        assertFalse(f.state.request(Any(), Any(), 8, descriptor))
        assertFalse(f.state.acknowledgeComposition(7, descriptor))
    }
    @Test fun activeOwnerRemainsCurrentAfterGrantConsumptionButDescriptorCannotChange() {
        assertTrue(privateCaptureOwnerReadbackAllowed(descriptor, descriptor, true, false, false, true))
        assertFalse(privateCaptureOwnerReadbackAllowed(descriptor, descriptor, false, false, false, true))
        assertFalse(privateCaptureOwnerReadbackAllowed(descriptor, descriptor, true, true, false, true))
        assertTrue(privateCaptureOwnerReadbackAllowed(descriptor, descriptor, false, true, true, true))
        assertFalse(privateCaptureOwnerReadbackAllowed(descriptor, changedDescriptor, false, true, true, true))
    }
    @Test fun preSubmissionTargetEvidenceStillRequiresForegroundAndExactTarget() {
        assertFalse(privateCaptureOwnerReadbackAllowed(descriptor, descriptor, true, false, false, false))
        assertFalse(privateCaptureOwnerReadbackAllowed(descriptor, descriptor, false, true, true, false))
    }
    @Test fun restorationAttemptsEveryIndependentActionAfterThrowsOrFailures() {
        val events = mutableListOf<Int>()
        assertFalse(restorePrivateCaptureProtection({ events += 1; throw IllegalStateException() },
            { events += 2; false }, { events += 3; true }, { events += 4; true }))
        assertEquals(listOf(1, 2, 3, 4), events)
    }
    @Test fun finalPolicyCheckIsImmediatelyBeforeSecureMutation() {
        val events = mutableListOf<String>()
        assertTrue(performPrivateCaptureOpening({ events += "policy"; true }, { events += "clear" },
            { events += "reveal"; true }, { events += "verify"; true }, { events += "restore" }))
        assertEquals(listOf("policy", "clear", "reveal", "verify"), events)
    }
    @Test fun rejectedPolicyNeverClearsAndAlwaysRestores() {
        val events = mutableListOf<String>()
        assertFalse(performPrivateCaptureOpening({ false }, { events += "clear" },
            { events += "reveal"; true }, { events += "verify"; true }, { events += "restore" }))
        assertEquals(listOf("restore"), events)
    }
    @Test fun clearRevealVerifyFailuresAlwaysAttemptRestoration() {
        for (failure in 0..2) {
            var restored = false
            assertFalse(performPrivateCaptureOpening({ true }, { if (failure == 0) throw IllegalStateException() },
                { failure != 1 }, { failure != 2 }, { restored = true }))
            assertTrue(restored)
        }
    }
    @Test fun restorationFailureCannotTurnOpeningFailureIntoAdmission() {
        assertFalse(performPrivateCaptureOpening({ true }, {}, { false }, { true }, { throw IllegalStateException() }))
    }
    @Test fun captureRootNeedsActualCompositionAndHardwareFrame() {
        val state = PrivateRootProtectionState(); val id = PrivateRootIdentity(Any(), Any(), Any(), Any(), Any())
        state.reservePrivate(); val turn = state.begin(id)!!
        assertFalse(state.revealCapture(turn, id))
        val old = state.drawn(turn, id, true, true, true)!!
        state.acknowledgeAppliedComposition(turn, id)
        assertFalse(state.commit(old, id, true, true))
        assertNull(state.drawn(turn, id, true, false, true))
        assertFalse(state.revealCapture(turn, id))
        val draw = state.drawn(turn, id, true, true, true)!!
        assertTrue(state.commit(draw, id, true, true)); assertTrue(state.revealCapture(turn, id))
        assertEquals(PrivateRootPresentationState.PRIVATE_CAPTURE_VISIBLE, state.presentationState)
        assertFalse(state.covered); assertTrue(state.suppressStructure)
    }
    @Test fun rootReplacementAndDetachCannotAcknowledgeRetiringCapture() {
        val state = PrivateRootProtectionState(); val id = PrivateRootIdentity(Any(), Any(), Any(), Any(), Any())
        state.reservePrivate(); val turn = state.begin(id)!!; state.acknowledgeAppliedComposition(turn, id)
        val draw = state.drawn(turn, id, true, true, true)!!
        assertFalse(state.commit(draw, id, false, true)); assertFalse(state.revealCapture(turn, id))
        state.invalidate(); assertFalse(state.commit(draw, id, true, true)); assertFalse(state.revealCapture(turn, id))
    }
    @Test fun idempotentAttachmentReadbackNeverClearsKnownLocalFailure() {
        assertTrue(privateCaptureAttachmentHealthy(true, false, false, true))
        repeat(3) {
            assertFalse(privateCaptureAttachmentHealthy(false, false, false, true))
            assertFalse(privateCaptureAttachmentHealthy(true, true, false, true))
            assertFalse(privateCaptureAttachmentHealthy(true, false, true, true))
            assertFalse(privateCaptureAttachmentHealthy(true, false, false, false))
        }
    }

    @Test fun missingSubmissionReadbackNeverAuthorizesSetupCancellation() {
        assertTrue(privateCaptureSetupMayCancel(false))
        assertFalse(privateCaptureSetupMayCancel(true))
        assertFalse(privateCaptureSetupMayCancel(null))
    }

    @Test fun ownAndFirstChildRestorationThrowsCannotStarveRemainingDialogWindows() {
        val events = mutableListOf<String>()
        val actions: List<() -> Boolean> = listOf(
            { events += "own"; throw IllegalStateException() },
            { events += "child-one"; throw IllegalStateException() },
            { events += "child-two"; true },
            { events += "child-three"; true },
        )
        assertFalse(restorePrivateCaptureProtection(*actions.toTypedArray()))
        assertEquals(listOf("own", "child-one", "child-two", "child-three"), events)
    }

    @Test fun thrownRestorationCannotBecomeSuccessfulOnSameAuthOrNoAuthReplay() {
        var failed = false
        val initial = restorePrivateCaptureProtection({ throw IllegalStateException() }, { true })
        failed = privateCaptureProtectionFailure(failed, initial)
        assertTrue(failed)
        // A later repeated attach or detach may do no new barrier work. Even a successful no-op
        // cannot erase the original failure or certify that secure/cover restoration occurred.
        repeat(2) {
            val later = restorePrivateCaptureProtection({ true }, { true })
            failed = privateCaptureProtectionFailure(failed, later)
            assertTrue(failed)
            assertFalse(privateCaptureAttachmentHealthy(true, false, failed, true))
        }
        assertFalse(privateCaptureProtectionFailure(false, true))
    }

}
