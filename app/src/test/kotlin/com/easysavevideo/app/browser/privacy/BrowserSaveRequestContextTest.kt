package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserSaveRequestContextTest {
    private fun lease() = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
    @Test fun revokeBeforeQueueRegistrationRejectsStart() {
        val lease = lease(); var stopped = false; var drained = false
        val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { stopped = true; it() }!!
        lease.revokeAndDrain { drained = true }
        assertTrue(stopped); assertTrue(drained); assertFalse(work.startQueued { fail("late start"); false })
        assertNull(lease.register(PrivateWorkKind.NETWORK_CANCEL) {})
    }
    @Test fun registrationIsOneShotAndRequiresActualRelease() {
        val lease = lease(); var released: (() -> Unit)? = null; var drained = false
        val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { released = it }!!
        assertTrue(work.startQueued { true }); assertFalse(work.startQueued { true })
        lease.revokeAndDrain { drained = true }; assertFalse(drained)
        released!!(); assertTrue(drained); work.complete()
    }
    @Test fun allOwnersReceiveExitEvenWhenOneThrows() {
        val lease = lease(); var second = false; var drained = false
        val failed = lease.register(PrivateWorkKind.NETWORK_CANCEL) { error("stop failure") }!!
        lease.register(PrivateWorkKind.NETWORK_CANCEL) { second = true; it() }
        lease.revokeAndDrain { drained = true }; assertTrue(second); assertFalse(drained)
        failed.complete(); assertTrue(drained)
    }
    @Test fun synchronousAcknowledgementDoesNotFinishBeforeAllStopsIssued() {
        val lease = lease(); val events = mutableListOf<String>()
        lease.register(PrivateWorkKind.NETWORK_CANCEL) { events += "network"; it() }
        lease.register(PrivateWorkKind.NETWORK_CANCEL) { events += "network2"; it() }
        lease.revokeAndDrain { events += "drained" }
        assertEquals(listOf("network", "network2", "drained"), events)
    }
    @Test fun outputEnumCannotCreateOrDetachPrivateSourceAuthority() {
        val lease = lease()
        assertNull(lease.register(PrivateWorkKind.OUTPUT_ONLY) { fail("not a source registration") })
        lease.revokeAndDrain {}
        assertNull(lease.register(PrivateWorkKind.OUTPUT_ONLY) {})
    }
    @Test fun sessionRevocationAlsoClosesRegistrationAndQueueGates() {
        val session = PrivateBrowsingSession(PrivateProfileName.random()); val lease = PrivateSessionLease(session)
        val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { it() }!!
        session.revoke(); assertFalse(lease.isLive()); assertFalse(work.startQueued { true })
        assertNull(lease.register(PrivateWorkKind.NETWORK_CANCEL) {})
    }
    @Test fun callbackReentryAndMultipleDrainWaitersAreSafe() {
        val lease = lease(); var count = 0
        lease.register(PrivateWorkKind.NETWORK_CANCEL) { lease.revokeAndDrain { count++ }; it() }
        lease.revokeAndDrain { count++ }; assertEquals(2, count)
    }
    @Test fun completedWorkDoesNotNeedExitAndSecretsNeverAppearInToString() {
        val lease = lease(); lease.register(PrivateWorkKind.NETWORK_CANCEL) { fail("already complete") }!!.complete()
        var drained = false; lease.revokeAndDrain { drained = true }; assertTrue(drained)
        val value = BrowserSaveRequestContext(BrowserSavePartition.Private(lease), "secret-cookie", "secret-agent")
        assertFalse(value.toString().contains("secret")); assertFalse(value.partition.toString().contains("esv_private"))
    }


    @Test fun partialQueueRegistrationFailureDoesNotInventRelease() {
        val lease = lease(); var drained = false
        val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { /* real owner still holds resources */ }!!
        assertFalse(work.startQueued { error("partial registration") })
        lease.revokeAndDrain { drained = true }; assertFalse(drained)
        work.complete(); assertTrue(drained)
    }
    @Test fun racingCompletionAndRevocationAcknowledgeDrainExactlyOnce() {
        repeat(20) {
            val lease = lease(); val start = CountDownLatch(1); val done = CountDownLatch(2)
            val drains = AtomicInteger()
            val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { it() }!!
            val complete = Thread { start.await(); work.complete(); done.countDown() }
            val revoke = Thread { start.await(); lease.revokeAndDrain { drains.incrementAndGet() }; done.countDown() }
            complete.start(); revoke.start(); start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(1, drains.get()); assertFalse(lease.isLive())
        }
    }


    @Test fun livenessProbeNeverWaitsForQueueRegistrationLock() {
        val lease = lease(); val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { it() }!!
        assertTrue(work.startQueued {
            val read = CountDownLatch(1)
            Thread { assertTrue(lease.isLive()); read.countDown() }.start()
            assertTrue(read.await(5, TimeUnit.SECONDS)); true
        })
        work.complete()
    }

    @Test fun continuationRequiresOriginalStartedLiveSourceRegistration() {
        val lease = lease(); val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { it() }!!
        assertFalse(work.continueQueued { fail("not started"); false })
        assertTrue(work.startQueued { true }); assertTrue(work.continueQueued { true })
        work.complete(); assertFalse(work.continueQueued { fail("completed"); false })
    }
    @Test fun revokedDormantSourceCannotRequeue() {
        val lease = lease(); val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { it() }!!
        assertTrue(work.startQueued { true }); lease.revokeAndDrain {}
        assertFalse(work.continueQueued { fail("revoked"); false })
    }
    @Test fun failedContinuationStillOwesRealOwnerRelease() {
        val lease = lease(); var drained = false
        val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { /* partial owner still alive */ }!!
        work.startQueued { true }; assertFalse(work.continueQueued { error("partial next claim") })
        lease.revokeAndDrain { drained = true }; assertFalse(drained)
        work.complete(); assertTrue(drained)
    }
    @Test fun continuationRegistrationAndExitRemainSerialized() {
        val lease = lease(); val work = lease.register(PrivateWorkKind.NETWORK_CANCEL) { it() }!!
        work.startQueued { true }
        val attempting = CountDownLatch(1); val revoked = CountDownLatch(1)
        assertTrue(work.continueQueued {
            Thread { attempting.countDown(); lease.revokeAndDrain { revoked.countDown() } }.start()
            assertTrue(attempting.await(5, TimeUnit.SECONDS))
            assertFalse(revoked.await(30, TimeUnit.MILLISECONDS)); true
        })
        assertTrue(revoked.await(5, TimeUnit.SECONDS)); assertFalse(lease.isLive())
    }

    @Test fun independentProjectionPermitSurvivesPrivateSourceRetirement() {
        val lease = lease(); val owner = Any(); var drained = false; var queued = false
        val permit = PrivateCaptureStartPermit(PrivateCaptureDescriptor(PrivateCaptureAudio.SILENT, PrivateCaptureMode.BASIC), owner)
        lease.revokeAndDrain { drained = true }
        assertTrue(drained); assertFalse(lease.isLive())
        assertEquals(PrivateCaptureStartPermit.Registration.REGISTERED,
            permit.consumeDeferred(owner) { queued = true })
        assertTrue(queued)
        assertEquals(PrivateCaptureStartPermit.Registration.REJECTED, permit.consumeDeferred(owner) { fail("one use") })
    }
    @Test fun browserSourceRegistryHasNoProjectionWorkKind() {
        assertEquals(setOf("NETWORK_CANCEL", "OUTPUT_ONLY"), PrivateWorkKind.entries.map { it.name }.toSet())
    }
    @Test fun retainedAuthorityNeverAcceptsReplacementSourceAndRetiresMonotonically() {
        var calls = 0
        val authority = BrowserSaveResumeAuthority { calls++; BrowserSaveRequestContext(BrowserSavePartition.Ordinary, null, "fixture") }
        assertNotNull(authority.refreshForExplicitResume()); assertEquals(1, calls)
        authority.retire(); assertNull(authority.refreshForExplicitResume()); assertEquals(1, calls)
        assertFalse(authority.toString().contains("fixture"))
    }
    @Test fun retiredDuringRefreshCannotReturnAnAlreadyProducedContext() {
        lateinit var authority: BrowserSaveResumeAuthority
        authority = BrowserSaveResumeAuthority {
            authority.retire(); BrowserSaveRequestContext(BrowserSavePartition.Ordinary, "fixture-secret", "fixture")
        }
        assertNull(authority.refreshForExplicitResume())
    }
    @Test fun refreshFailureDoesNotInventFreshCredentials() {
        val authority = BrowserSaveResumeAuthority { error("synthetic stale document") }
        assertNull(authority.refreshForExplicitResume())
    }

    @Test fun retireRacingInProgressRefreshDropsItsResult() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<BrowserSaveRequestContext?>()
        val authority = BrowserSaveResumeAuthority {
            started.countDown(); check(release.await(5, TimeUnit.SECONDS))
            BrowserSaveRequestContext(BrowserSavePartition.Ordinary, "fixture-secret", "fixture")
        }
        Thread { result.set(authority.refreshForExplicitResume()); finished.countDown() }.start()
        assertTrue(started.await(5, TimeUnit.SECONDS)); authority.retire(); release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS)); assertNull(result.get()); assertNull(authority.refreshForExplicitResume())
    }

}
