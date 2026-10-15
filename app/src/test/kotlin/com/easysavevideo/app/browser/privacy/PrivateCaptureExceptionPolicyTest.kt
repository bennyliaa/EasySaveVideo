package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

/** Setup-only policy tests. Ongoing capture ownership is covered by ContinuousRecordingOwnerTest. */
class PrivateCaptureExceptionPolicyTest {
    private class Clock : PrivatePolicyScheduler {
        class Ticket(val action: () -> Unit, var active: Boolean = true)
        val tickets = mutableListOf<Ticket>()
        override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
            val ticket = Ticket(action); tickets += ticket; return { ticket.active = false }
        }
        fun fire(includeCancelled: Boolean = false) {
            tickets.toList().filter { includeCancelled || it.active }.forEach { it.active = false; it.action() }
        }
    }
    private class Fixture {
        val session = PrivateBrowsingSession(PrivateProfileName.random())
        val target = PrivateCaptureTarget(session, Any(), Any(), Any()); val owner = Any(); val clock = Clock()
        val events = mutableListOf<String>(); var logical = true; var ownerLive = true; var visible = true
        var openSucceeds = true; var restorationFails = false; var stopFails = false; var discardFails = false
        var protected: (() -> Unit)? = null; var released: (() -> Unit)? = null
        val dispositions = mutableListOf<PrivateCaptureDisposition>()
        val scope = PrivateCaptureScope.API34_PLUS_USER_CHOICE
        val descriptor = PrivateCaptureDescriptor(PrivateCaptureAudio.DEVICE_AUDIO, PrivateCaptureMode.BASIC)
        val proof = PrivateCaptureSurfaceEvidence(target, owner, Any(), scope, PrivateCaptureRecentsProtection.API33_RECENTS_SUPPRESSED)
        var phaseObserved: (PrivateCapturePhase) -> Unit = {}
        val policy = PrivateCaptureExceptionPolicy(object : PrivateCaptureSurfacePort {
            override fun openException(evidence: PrivateCaptureSurfaceEvidence, stillAllowed: () -> Boolean): Boolean {
                assertTrue(stillAllowed()); events += "open"; return openSucceeds
            }
            override fun restoreProtection(target: PrivateCaptureTarget, protected: () -> Unit) {
                events += "protect"; if (restorationFails) error("synthetic"); this@Fixture.protected = protected
            }
        }, object : PrivateCaptureStopPort {
            override fun stop(ownerIdentity: Any, disposition: PrivateCaptureDisposition, done: () -> Unit) {
                assertSame(owner, ownerIdentity); events += "stop"; dispositions += disposition
                if (stopFails) error("synthetic"); released = done
            }
            override fun discardUnpublished(ownerIdentity: Any) {
                assertSame(owner, ownerIdentity); events += "discard"; if (discardFails) error("synthetic")
            }
        }, { logical && it === target }, { ownerLive && it === owner }, { visible && it === proof }, clock,
            { it() }, { phaseObserved(it) })
        fun request() = policy.requestFromExplicitAction(target, owner, scope, true, descriptor)!!
        fun armed(): Pair<PrivateCaptureAttempt, PrivateCaptureStartPermit> {
            val attempt = request(); assertTrue(policy.projectionAccepted(attempt))
            return attempt to requireNotNull(policy.openForSurface(attempt, proof))
        }
        fun drain() { protected!!(); released!!() }
    }
    @Test fun disclosureGrantAndOwnedSurfaceRemainSeparateGates() {
        val f = Fixture(); assertNull(f.policy.requestFromExplicitAction(f.target, f.owner, f.scope, false, f.descriptor))
        val a = f.request(); assertNull(f.policy.openForSurface(a, f.proof)); assertTrue(f.events.isEmpty())
        assertTrue(f.policy.projectionAccepted(a)); assertFalse(f.policy.projectionAccepted(a))
    }
    @Test fun wrongOwnerTargetScopeAndStaleEvidenceCannotClearProtection() {
        val f = Fixture(); val a = f.request(); f.policy.projectionAccepted(a)
        listOf(
            PrivateCaptureSurfaceEvidence(f.target, Any(), Any(), f.scope, PrivateCaptureRecentsProtection.API33_RECENTS_SUPPRESSED),
            PrivateCaptureSurfaceEvidence(PrivateCaptureTarget(f.session, Any(), Any(), Any()), f.owner, Any(), f.scope, PrivateCaptureRecentsProtection.API33_RECENTS_SUPPRESSED),
            PrivateCaptureSurfaceEvidence(f.target, f.owner, Any(), PrivateCaptureScope.API31_TO_33_DEFAULT_DISPLAY, PrivateCaptureRecentsProtection.LEGACY_RECENTS_RISK_ACCEPTED)
        ).forEach { assertNull(f.policy.openForSurface(a, it)) }
        f.visible = false; assertNull(f.policy.openForSurface(a, f.proof)); assertTrue(f.events.isEmpty())
    }
    @Test fun staleInitialTargetAtGrantCannotStart() {
        val f = Fixture(); val a = f.request(); f.session.revoke()
        assertFalse(f.policy.projectionAccepted(a)); assertEquals(listOf("protect", "stop"), f.events)
    }
    @Test fun unsubmittedArmingTimeoutStopsWithoutInventingNativeRelease() {
        val f = Fixture(); val (a, permit) = f.armed(); f.clock.fire()
        assertEquals(PrivateCapturePhase.STOPPING, f.policy.phase)
        assertFalse(permit.consumeAndRegister(f.owner) {})
        assertFalse(f.policy.retireAfterSubmission(a)); f.drain(); assertEquals(PrivateCapturePhase.IDLE, f.policy.phase)
    }
    @Test fun submittedArmingRetiresEveryPolicyClosureBeforeDelayedNativeStart() {
        val f = Fixture(); val (a, permit) = f.armed()
        assertTrue(f.policy.retireAfterSubmission(a))
        f.session.revoke(); f.logical = false; f.visible = false; f.clock.fire(includeCancelled = true)
        assertEquals(listOf("open"), f.events)
        assertTrue(permit.consumeAndRegister(f.owner) {})
        assertTrue(permit.startedAllowed()); assertFalse(f.policy.revoke(a))
        for (name in listOf("surface", "stopPort", "targetCurrent", "ownerCurrent", "surfaceCurrent", "scheduler", "onOwnerThread", "changed", "current", "evidence", "cancelWatch")) {
            val field = f.policy.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull(name, field.get(f.policy))
        }
        assertNull(a.permit)
    }
    @Test fun nativeHeldPermitCannotRetainPolicyTargetProfileOrCallback() {
        val f = Fixture(); val (a, permit) = f.armed(); assertTrue(f.policy.retireAfterSubmission(a))
        permit.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
            it.isAccessible = true
            val value = it.get(permit)
            assertFalse(it.name, value is Function<*>)
            assertFalse(it.name, value is PrivateCaptureTarget || value is PrivateCaptureAttempt || value is PrivateCaptureExceptionPolicy || value is PrivateBrowsingSession)
            assertTrue(it.name, it.name in setOf("descriptor", "ownerIdentity", "revoked", "consumed"))
        }
    }
    @Test fun retirementCannotBeUsedBeforeAcceptedGrantAndArming() {
        val f = Fixture(); val a = f.request(); assertFalse(f.policy.retireAfterSubmission(a))
        f.policy.projectionAccepted(a); assertFalse(f.policy.retireAfterSubmission(a))
        f.policy.openForSurface(a, f.proof); assertTrue(f.policy.retireAfterSubmission(a))
        assertFalse(f.policy.retireAfterSubmission(a))
        assertNull(f.policy.requestFromExplicitAction(f.target, f.owner, f.scope, true, f.descriptor))
    }
    @Test fun permitRegistrationIsOneShotAndFailureIsCallbackFreeResult() {
        val f = Fixture(); val (_, permit) = f.armed()
        assertEquals(PrivateCaptureStartPermit.Registration.REJECTED, permit.consumeDeferred(Any()) { fail("foreign") })
        assertEquals(PrivateCaptureStartPermit.Registration.FAILED, permit.consumeDeferred(f.owner) { error("partial") })
        assertFalse(permit.startedAllowed())
        assertEquals(PrivateCaptureStartPermit.Registration.REJECTED, permit.consumeDeferred(f.owner) { fail("duplicate") })
        assertEquals(listOf("open"), f.events) // Real owner dispatches FAILED after its locks; permit cannot call Activity.
    }
    @Test fun explicitSetupDiscardAndLaterDiscardRemainMonotonic() {
        val f = Fixture(); val (a, _) = f.armed(); f.policy.revoke(a)
        f.policy.revoke(a, PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)
        f.policy.revoke(a, PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)
        assertEquals(1, f.events.count { it == "stop" }); assertEquals(1, f.events.count { it == "discard" })
    }
    @Test fun failedDiscardRemainsRetryable() {
        val f = Fixture(); val (a, _) = f.armed(); f.policy.revoke(a); f.discardFails = true
        assertFalse(f.policy.revoke(a, PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT))
        f.discardFails = false; assertTrue(f.policy.revoke(a, PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT))
        assertEquals(2, f.events.count { it == "discard" })
    }
    @Test fun setupProtectionAndNativeReleaseAreIndependent() {
        val f = Fixture(); val (a, _) = f.armed(); f.policy.revoke(a)
        f.released!!(); assertEquals(PrivateCapturePhase.STOPPING, f.policy.phase)
        f.protected!!(); assertEquals(PrivateCapturePhase.IDLE, f.policy.phase)
    }
    @Test fun failedProtectionStillStopsButDoesNotClaimProtectedDrain() {
        val f = Fixture(); val (a, _) = f.armed(); f.restorationFails = true
        f.policy.revoke(a); f.released!!(); assertEquals(PrivateCapturePhase.BLOCKED, f.policy.phase)
        assertEquals(listOf("open", "protect", "stop"), f.events)
    }
    @Test fun failedStopStillRestoresWithoutFakeRelease() {
        val f = Fixture(); val (a, _) = f.armed(); f.stopFails = true
        f.policy.revoke(a); f.protected!!(); assertEquals(PrivateCapturePhase.BLOCKED, f.policy.phase)
    }
    @Test fun observerFailureCannotPreventProtectionOrStop() {
        val f = Fixture(); val (a, _) = f.armed()
        f.phaseObserved = { if (it == PrivateCapturePhase.STOPPING) error("observer") }
        f.policy.revoke(a); f.drain(); assertEquals(PrivateCapturePhase.IDLE, f.policy.phase)
    }
    @Test fun reentrantRevocationBeforeOpeningCannotClearSecure() {
        val f = Fixture(); val a = f.request(); f.policy.projectionAccepted(a)
        f.phaseObserved = { if (it == PrivateCapturePhase.ARMING) f.policy.revoke(a) }
        assertNull(f.policy.openForSurface(a, f.proof)); assertEquals(listOf("protect", "stop"), f.events)
    }
    @Test fun rejectedOpenCannotIssuePermit() {
        val f = Fixture(); val a = f.request(); f.policy.projectionAccepted(a); f.openSucceeds = false
        assertNull(f.policy.openForSurface(a, f.proof)); assertEquals(listOf("open", "protect", "stop"), f.events)
    }
    @Test fun revokeBeforeConsumptionBlocksQueuedStart() {
        val f = Fixture(); val (a, permit) = f.armed(); f.policy.revoke(a)
        assertFalse(permit.consumeAndRegister(f.owner) { fail("late queue") })
    }
}
