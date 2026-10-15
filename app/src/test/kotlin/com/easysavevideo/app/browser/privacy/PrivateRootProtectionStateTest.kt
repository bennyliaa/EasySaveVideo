package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

/** Exercises production state, not an Android stub or a separate model of the implementation. */
class PrivateRootProtectionStateTest {
    private fun identity() = PrivateRootIdentity(Any(), Any(), Any(), Any(), Any())
    private fun copy(value: PrivateRootIdentity) = PrivateRootIdentity(value.window, value.decor,
        value.root, value.target, value.presentation)
    private fun draw(state: PrivateRootProtectionState, turn: PrivateProtectionTurn) =
        checkNotNull(state.drawn(turn, copy(turn.identity), attached = true, hardware = true, hasArea = true))

    @Test fun coldStartIsCoveredAndStructureSuppressedWithoutRestoredEvidence() {
        val state = PrivateRootProtectionState()
        assertTrue(state.covered); assertTrue(state.suppressStructure)
        assertNull(state.current); assertFalse(state.coverFrameCommitted)
    }
    @Test fun callbackBeforeActualCoverDrawCannotAcknowledgeProtection() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        assertFalse(state.commit(PrivateCoverDraw(turn), id, attached = true, hardware = true))
        assertTrue(state.covered); assertFalse(state.coverFrameCommitted)
    }
    @Test fun exactDrawThenHardwareCommitIsOneShotAndLeavesStickyCover() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        val draw = draw(state, turn)
        assertFalse(state.coverFrameCommitted)
        assertTrue(state.commit(draw, copy(id), attached = true, hardware = true))
        assertFalse(state.commit(draw, id, attached = true, hardware = true))
        assertTrue(state.coverFrameCommitted); assertTrue(state.covered); assertTrue(state.suppressStructure)
    }
    @Test fun everyWindowDecorRootTargetAndPresentationReferenceMustMatch() {
        val id = identity()
        val changed = listOf(
            PrivateRootIdentity(Any(), id.decor, id.root, id.target, id.presentation),
            PrivateRootIdentity(id.window, Any(), id.root, id.target, id.presentation),
            PrivateRootIdentity(id.window, id.decor, Any(), id.target, id.presentation),
            PrivateRootIdentity(id.window, id.decor, id.root, Any(), id.presentation),
            PrivateRootIdentity(id.window, id.decor, id.root, id.target, Any()),
        )
        changed.forEach { wrong ->
            val state = PrivateRootProtectionState(); val turn = state.begin(id)!!
            val draw = draw(state, turn)
            assertFalse(state.commit(draw, wrong, attached = true, hardware = true))
            assertFalse(state.coverFrameCommitted)
            assertTrue(state.commit(draw, copy(id), attached = true, hardware = true))
        }
    }
    @Test fun equalButDifferentIdentityObjectsCannotSubstituteForOwnedRoot() {
        val id = PrivateRootIdentity("window", "decor", String(charArrayOf('r')), "target", "presentation")
        val other = PrivateRootIdentity(id.window, id.decor, String(charArrayOf('r')), id.target, id.presentation)
        assertEquals(id.root, other.root); assertFalse(id.sameAs(other))
    }
    @Test fun newerCoverGenerationRejectsDelayedOldDrawAndCommit() {
        val state = PrivateRootProtectionState(); val id = identity(); val old = state.begin(id)!!
        val oldDraw = draw(state, old)
        val next = state.begin(id)!!
        assertTrue(next.generation > old.generation)
        assertNull(state.drawn(old, id, true, true, true))
        assertFalse(state.commit(oldDraw, id, true, true))
        assertTrue(state.commit(draw(state, next), id, true, true))
    }
    @Test fun replacementRootAndDetachRetirePreviouslySubmittedFrames() {
        val state = PrivateRootProtectionState(); val id = identity(); val old = state.begin(id)!!
        val draw = draw(state, old)
        state.invalidate()
        assertFalse(state.commit(draw, id, true, true)); assertNull(state.current)
        val replacement = identity(); state.begin(replacement)
        assertFalse(state.commit(draw, replacement, true, true))
        assertTrue(state.covered); assertTrue(state.suppressStructure)
    }
    @Test fun destroyCannotBeReopenedOrAcknowledgeAnOldFrame() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        val draw = draw(state, turn); state.close()
        assertFalse(state.commit(draw, id, true, true)); assertNull(state.begin(id))
        assertTrue(state.disposed); assertTrue(state.covered); assertTrue(state.suppressStructure)
    }
    @Test fun softwareDrawNeverIssuesHardwareAcknowledgment() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        assertNull(state.drawn(turn, id, true, false, true))
        assertFalse(state.commit(PrivateCoverDraw(turn), id, true, false))
        assertFalse(state.coverFrameCommitted); assertTrue(state.covered)
    }
    @Test fun unattachedOrZeroAreaDrawAndUnattachedCommitStayPending() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        assertNull(state.drawn(turn, id, false, true, true))
        assertNull(state.drawn(turn, id, true, true, false))
        val draw = draw(state, turn)
        assertFalse(state.commit(draw, id, false, true))
        assertFalse(state.commit(draw, id, true, false))
        assertFalse(state.coverFrameCommitted); assertTrue(state.covered)
    }
    @Test fun unavailableOrNoOpRegistrationNeverBecomesSuccessAndCanRetryAfterNewDraw() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        val first = draw(state, turn)
        // A no-op callback leaves this current generation pending, with no timeout success.
        assertNull(state.drawn(turn, id, true, true, true)); assertFalse(state.coverFrameCommitted)
        state.registrationUnavailable(first)
        val retry = draw(state, turn)
        assertFalse(state.commit(first, id, true, true))
        assertTrue(state.commit(retry, id, true, true))
    }
    @Test fun ordinaryReleaseNeedsActualDrawAndCurrentOrdinaryPresentation() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        assertFalse(state.releaseOrdinary(turn, id, true, false))
        draw(state, turn)
        assertFalse(state.releaseOrdinary(turn, id, false, false))
        assertFalse(state.releaseOrdinary(turn, identity(), true, false))
        assertTrue(state.releaseOrdinary(turn, id, true, false))
        assertFalse(state.covered); assertFalse(state.suppressStructure)
    }
    @Test fun attachedPrivateContentCannotBeReleasedByAnOrdinaryClaim() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        draw(state, turn)
        assertFalse(state.releaseOrdinary(turn, id, true, true))
        assertTrue(state.covered); assertTrue(state.suppressStructure)
    }
    @Test fun ordinarySoftwarePresentationCanRecoverWithoutClaimingPrivateFrameProof() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        assertNull(state.drawn(turn, id, true, false, true))
        assertTrue(state.releaseOrdinary(turn, id, true, false))
        assertFalse(state.coverFrameCommitted); assertFalse(state.covered)
    }
    @Test fun ordinaryReleaseAndRenewedProtectionBothRejectPreviousCallbacks() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        val draw = draw(state, turn)
        assertTrue(state.releaseOrdinary(turn, id, true, false))
        assertFalse(state.commit(draw, id, true, true))
        val next = state.begin(id)!!
        assertTrue(next.generation > turn.generation); assertTrue(state.covered)
        assertFalse(state.commit(draw, id, true, true))
    }

    @Test fun ordinaryAppOptInRestoresDespiteSystemEffectiveFalse() {
        var systemEnabled = false
        var appOptIn = true
        fun effective() = systemEnabled && appOptIn
        val policy = PrivateContentCaptureSetting(ordinaryOptIn = true)
        assertFalse(effective())
        policy.protect { appOptIn = it }
        assertFalse(appOptIn)
        policy.restoreOrdinary { appOptIn = it }
        assertTrue(appOptIn); assertFalse(effective())
        systemEnabled = true
        assertTrue(effective())
    }
    @Test fun explicitlyDisabledOrdinaryAppPolicyRemainsDisabled() {
        var appOptIn = false
        val policy = PrivateContentCaptureSetting(ordinaryOptIn = false)
        policy.protect { appOptIn = it }
        policy.restoreOrdinary { appOptIn = it }
        assertFalse(appOptIn)
    }

    private fun privateReadback() = PrivateRevealReadback(secure = true, structureExcluded = true,
        contentCaptureDisabled = true, recentsProtection = true, integrationReady = true,
        claimCurrent = true, compositionApplied = true, attached = true, focused = true,
        captureExceptionInactive = true)
    private fun committedPrivate(state: PrivateRootProtectionState, id: PrivateRootIdentity): PrivateProtectionTurn {
        state.reservePrivate()
        return state.begin(id)!!.also {
            assertTrue(state.acknowledgeAppliedComposition(it, id))
            assertTrue(state.commit(draw(state, it), id, true, true))
        }
    }
    private fun ordinaryDeparture() = PrivateOrdinaryDepartureReadback(true, true, true, true, true)

    @Test fun protectedPrivateRevealRetiresTurnButPreservesStructureAndExactVisibleIdentity() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
        val before = state.generation
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(turn, copy(id), privateReadback()))
        assertFalse(state.covered); assertTrue(state.suppressStructure); assertNull(state.current)
        assertTrue(state.generation > before)
        assertEquals(PrivateRootPresentationState.PRIVATE_VISIBLE, state.presentationState)
        assertTrue(state.visibleIdentity!!.sameAs(id))
        assertEquals(PrivateRevealResult.STALE, state.revealProtectedPrivate(turn, id, privateReadback()))
    }
    @Test fun protectedRevealCannotSkipReservationOrActualMatchingHardwareFrame() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = state.begin(id)!!
        assertEquals(PrivateRevealResult.STALE, state.revealProtectedPrivate(turn, id, privateReadback()))
        state.reservePrivate()
        assertEquals(PrivateRevealResult.PENDING, state.revealProtectedPrivate(turn, id, privateReadback()))
        state.drawn(turn, id, true, false, true)
        assertEquals(PrivateRevealResult.PENDING, state.revealProtectedPrivate(turn, id, privateReadback()))
        assertTrue(state.covered); assertTrue(state.suppressStructure)
    }
    @Test fun protectedRevealRejectsEachIndependentLocalAndIntegrationFailure() {
        val safe = privateReadback()
        listOf(safe.copy(secure = false), safe.copy(structureExcluded = false),
            safe.copy(contentCaptureDisabled = false), safe.copy(recentsProtection = false),
            safe.copy(integrationReady = false), safe.copy(captureExceptionInactive = false)).forEach { bad ->
            val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
            assertEquals(PrivateRevealResult.BLOCKED, state.revealProtectedPrivate(turn, id, bad))
            assertTrue(state.covered); assertTrue(state.suppressStructure)
            assertNull(state.visibleIdentity)
        }
    }
    @Test fun attachmentCompositionAndOwnedFocusRemainPendingRatherThanPermanentBarrierFailure() {
        val safe = privateReadback()
        listOf(safe.copy(attached = false), safe.copy(compositionApplied = false),
            safe.copy(focused = false)).forEach { pending ->
            val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
            assertEquals(PrivateRevealResult.PENDING, state.revealProtectedPrivate(turn, id, pending))
            assertTrue(state.covered)
            assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(turn, id, safe))
        }
    }
    @Test fun staleClaimAndEachIdentityReplacementCannotUnmaskCurrentContent() {
        val id = identity()
        val changed = listOf(
            PrivateRootIdentity(Any(), id.decor, id.root, id.target, id.presentation),
            PrivateRootIdentity(id.window, Any(), id.root, id.target, id.presentation),
            PrivateRootIdentity(id.window, id.decor, Any(), id.target, id.presentation),
            PrivateRootIdentity(id.window, id.decor, id.root, Any(), id.presentation),
            PrivateRootIdentity(id.window, id.decor, id.root, id.target, Any()),
        )
        changed.forEach { wrong ->
            val state = PrivateRootProtectionState(); val turn = committedPrivate(state, id)
            assertEquals(PrivateRevealResult.STALE, state.revealProtectedPrivate(turn, wrong, privateReadback()))
            assertEquals(PrivateRevealResult.STALE, state.revealProtectedPrivate(turn, id, privateReadback().copy(claimCurrent = false)))
            assertTrue(state.covered)
        }
    }
    @Test fun privateReleaseThenNewCoverInvalidatesOldCommitAndVisibleClaimEvenForSameIdentity() {
        val state = PrivateRootProtectionState(); val id = identity(); state.reservePrivate()
        val turn = state.begin(id)!!; state.acknowledgeAppliedComposition(turn, id); val oldDraw = draw(state, turn)
        assertTrue(state.commit(oldDraw, id, true, true))
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(turn, id, privateReadback()))
        assertFalse(state.commit(oldDraw, id, true, true))
        val next = state.begin(id)!!
        assertNull(state.visibleIdentity); assertTrue(state.covered); assertTrue(state.suppressStructure)
        assertEquals(PrivateRevealResult.STALE, state.revealProtectedPrivate(turn, id, privateReadback()))
        assertEquals(PrivateRevealResult.PENDING, state.revealProtectedPrivate(next, id, privateReadback()))
    }
    @Test fun startupOrdinaryReleasePermanentlyRefusesAnyPrivateReservation() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
        assertFalse(state.releaseOrdinary(turn, id, true, false))
        assertFalse(state.releasePresentedOrdinary(turn, id, ordinaryDeparture()))
        val departure = state.beginOrdinaryDeparture(id)!!
        state.acknowledgeAppliedComposition(departure, id); draw(state, departure)
        assertTrue(state.releasePresentedOrdinary(departure, id, ordinaryDeparture()))
        val next = state.begin(identity())!!; draw(state, next)
        assertFalse(state.releaseOrdinary(next, next.identity, true, false))
    }
    @Test fun ordinaryDepartureRequiresEveryFreshTypedProofAndActualNewDraw() {
        val state = PrivateRootProtectionState(); val privateId = identity()
        val privateTurn = committedPrivate(state, privateId)
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(privateTurn, privateId, privateReadback()))
        val ordinaryId = identity(); val ordinaryTurn = state.beginOrdinaryDeparture(ordinaryId)!!
        assertFalse(state.releasePresentedOrdinary(ordinaryTurn, ordinaryId, ordinaryDeparture()))
        state.acknowledgeAppliedComposition(ordinaryTurn, ordinaryId)
        draw(state, ordinaryTurn)
        val safe = ordinaryDeparture()
        listOf(safe.copy(ordinaryCurrent = false), safe.copy(privateViewsDetached = false),
            safe.copy(privateDialogsAbsent = false), safe.copy(privateCallbacksRetired = false),
            safe.copy(captureDrained = false)).forEach { bad ->
            assertFalse(state.releasePresentedOrdinary(ordinaryTurn, ordinaryId, bad))
            assertTrue(state.covered); assertTrue(state.suppressStructure)
        }
        assertFalse(state.releasePresentedOrdinary(privateTurn, privateId, safe))
        assertTrue(state.releasePresentedOrdinary(ordinaryTurn, ordinaryId, safe))
        assertEquals(PrivateRootPresentationState.ORDINARY_VISIBLE, state.presentationState)
        assertFalse(state.covered); assertFalse(state.suppressStructure); assertNull(state.visibleIdentity)
    }
    @Test fun disposalNeverAcceptsPrivateOrOrdinaryRelease() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
        state.close()
        assertEquals(PrivateRootPresentationState.DISPOSED, state.presentationState)
        assertEquals(PrivateRevealResult.STALE, state.revealProtectedPrivate(turn, id, privateReadback()))
        assertFalse(state.releasePresentedOrdinary(turn, id, ordinaryDeparture()))
        assertTrue(state.covered); assertTrue(state.suppressStructure)
    }

    @Test fun frameBeforeAppliedCompositionCannotReleasePrivateOrOrdinaryHierarchy() {
        val state = PrivateRootProtectionState(); val id = identity(); state.reservePrivate()
        val turn = state.begin(id)!!; val oldDraw = draw(state, turn)
        assertTrue(state.commit(oldDraw, id, true, true))
        assertEquals(PrivateRevealResult.PENDING, state.revealProtectedPrivate(turn, id, privateReadback()))
        assertTrue(state.acknowledgeAppliedComposition(turn, id))
        assertFalse(state.coverDrawn); assertFalse(state.coverFrameCommitted)
        assertFalse(state.commit(oldDraw, id, true, true))
        val newDraw = draw(state, turn)
        assertTrue(state.acknowledgeAppliedComposition(turn, id)) // Repeat acknowledgement does not reset.
        assertTrue(state.commit(newDraw, id, true, true))
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(turn, id, privateReadback()))
        val ordinary = state.beginOrdinaryDeparture(id)!!; draw(state, ordinary)
        assertFalse(state.releasePresentedOrdinary(ordinary, id, ordinaryDeparture()))
        state.acknowledgeAppliedComposition(ordinary, id)
        assertFalse(state.releasePresentedOrdinary(ordinary, id, ordinaryDeparture()))
        draw(state, ordinary)
        assertTrue(state.releasePresentedOrdinary(ordinary, id, ordinaryDeparture()))
    }
    @Test fun liveAuthorizationAllowsContinuousPixelsButKeepsAllOtherRevealChecks() {
        val safe = privateReadback().copy(secure = false, captureExceptionInactive = false,
            continuousCaptureAuthorized = true)
        val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(turn, id, safe))
        assertEquals(PrivateRootPresentationState.PRIVATE_CAPTURE_VISIBLE, state.presentationState)
        assertTrue(state.suppressStructure); assertFalse(state.covered)
        for (bad in listOf(safe.copy(continuousCaptureAuthorized = false), safe.copy(structureExcluded = false),
            safe.copy(contentCaptureDisabled = false), safe.copy(recentsProtection = false), safe.copy(integrationReady = false))) {
            val next = PrivateRootProtectionState(); val nextId = identity(); val nextTurn = committedPrivate(next, nextId)
            assertEquals(PrivateRevealResult.BLOCKED, next.revealProtectedPrivate(nextTurn, nextId, bad))
            assertTrue(next.covered)
        }
    }
    @Test fun endedAuthorizationRequiresRealSecureProtectionOnTheNextPresentation() {
        val state = PrivateRootProtectionState(); val id = identity(); val turn = committedPrivate(state, id)
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(turn, id,
            privateReadback().copy(secure = false, continuousCaptureAuthorized = true)))
        val next = state.begin(id)!!; state.acknowledgeAppliedComposition(next, id)
        assertTrue(state.commit(draw(state, next), id, true, true))
        assertEquals(PrivateRevealResult.BLOCKED, state.revealProtectedPrivate(next, id, privateReadback().copy(secure = false)))
        assertEquals(PrivateRevealResult.REVEALED, state.revealProtectedPrivate(next, id, privateReadback()))
        assertEquals(PrivateRootPresentationState.PRIVATE_VISIBLE, state.presentationState)
    }
    @Test fun independentProjectionDoesNotBlockRealPrivateDepartureOrClaimNativeDrain() {
        val state = PrivateRootProtectionState(); val id = identity(); committedPrivate(state, id)
        val departure = state.beginOrdinaryDeparture(id)!!; state.acknowledgeAppliedComposition(departure, id)
        draw(state, departure)
        val proof = ordinaryDeparture().copy(captureDrained = false, independentRecordingOwner = true)
        assertFalse(proof.captureDrained); assertTrue(proof.complete)
        listOf(proof.copy(privateViewsDetached = false), proof.copy(privateDialogsAbsent = false),
            proof.copy(privateCallbacksRetired = false), proof.copy(ordinaryCurrent = false)).forEach {
            assertFalse(state.releasePresentedOrdinary(departure, id, it)); assertTrue(state.covered)
        }
        assertTrue(state.releasePresentedOrdinary(departure, id, proof)); assertFalse(state.covered)
    }

}
