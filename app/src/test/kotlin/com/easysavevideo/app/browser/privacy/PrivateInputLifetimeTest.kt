package com.easysavevideo.app.browser.privacy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Actual production lifetime/token with fake provider ports. Not Android/provider acceptance. */
class PrivateInputLifetimeTest {
    @Test fun startsDeniedAndDoesNotIssueTokens() {
        val lifetime = PrivateInputLifetime(Any())
        assertFalse(lifetime.isAdmitted)
        assertNull(lifetime.acquire(Any()))
    }

    @Test fun immutableSnapshotRetainsExactRootPresentationAndInputOwner() {
        val root = Any(); val presentation = Any(); val owner = Any()
        val lifetime = PrivateInputLifetime(root)
        assertTrue(lifetime.publish(presentation))
        val token = lifetime.acquire(owner)!!
        assertSame(root, token.snapshot.root)
        assertSame(presentation, token.snapshot.presentation)
        assertSame(owner, token.snapshot.owner)
        assertTrue(token.snapshot.generation > 0)
        assertTrue(token.isCurrent())
    }

    @Test fun revokeAndSamePresentationRepublishNeverReactivatesOldToken() {
        val lifetime = PrivateInputLifetime(Any()); val presentation = Any(); val owner = Any()
        lifetime.publish(presentation); val old = lifetime.acquire(owner)!!
        lifetime.revoke()
        assertFalse(old.isCurrent()); assertFalse(lifetime.isAdmitted)
        assertNull(lifetime.acquire(owner))
        lifetime.publish(presentation); val fresh = lifetime.acquire(owner)!!
        assertTrue(fresh.isCurrent()); assertFalse(old.isCurrent())
        assertTrue(fresh.snapshot.generation > old.snapshot.generation)
    }

    @Test fun directRepublishForTabDocumentDialogSwapAlsoRetiresOldToken() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val old = lifetime.acquire(Any())!!
        lifetime.publish(Any())
        assertFalse(old.isCurrent()); assertTrue(lifetime.acquire(Any())!!.isCurrent())
    }

    @Test fun localConnectionCloseDoesNotRevokeOtherEditors() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val first = lifetime.acquire(Any())!!; val second = lifetime.acquire(Any())!!
        first.revoke(); first.revoke()
        assertFalse(first.isCurrent()); assertTrue(second.isCurrent()); assertTrue(lifetime.isAdmitted)
    }

    @Test fun sessionForkCannotAcquireReplacementGenerationOrSurviveSessionRevoke() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val session = lifetime.acquire(Any())!!; val first = session.fork()!!
        first.revoke()
        assertTrue(session.isCurrent())
        val second = session.fork()!!; session.revoke()
        assertFalse(second.isCurrent()); assertNull(session.fork())
        lifetime.publish(Any())
        assertNull(session.fork()); assertFalse(first.isCurrent()); assertFalse(second.isCurrent())
    }

    @Test fun disposePermanentlyRejectsPublishAndRetiresEveryToken() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!
        lifetime.dispose(); lifetime.dispose(); lifetime.revoke()
        assertFalse(token.isCurrent()); assertFalse(lifetime.publish(Any()))
        assertFalse(lifetime.isAdmitted); assertNull(lifetime.acquire(Any()))
    }

    @Test fun staleMutationsIncludingBatchFlushDoNotInvokeProvider() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!; var calls = 0
        lifetime.revoke()
        assertFalse(token.mutate { calls++; true })
        assertEquals(0, calls)
    }

    @Test fun staleReadBeforeDelegationReturnsEmptyWithoutProviderCall() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!; var calls = 0
        lifetime.revoke()
        assertNull(token.read<String?>(null) { calls++; "fixture" })
        assertEquals(0, token.read(0) { calls++; 7 })
        assertEquals(0, calls)
    }

    @Test fun revokeInsideReadDiscardsReturnedDataAndDoesNotDeadlock() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!
        assertNull(token.read<String?>(null) { lifetime.revoke(); "fixture" })
    }

    @Test fun delegateLeaseReplacementInsideReadDiscardsDataWithoutRevokingRootSession() {
        val registry = PrivateInputDelegateLeases(); val delegate = Any()
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val session = lifetime.acquire(Any())!!
        val oldLease = registry.acquire(delegate, session)!!
        val oldToken = session.fork(oldLease::isCurrent)!!
        var freshLease: PrivateInputDelegateLease? = null
        assertNull(oldToken.read<String?>(null) {
            freshLease = registry.acquire(delegate, session)
            "fixture"
        })
        assertFalse(oldToken.isCurrent()); assertTrue(session.isCurrent())
        assertTrue(freshLease!!.isCurrent()); assertTrue(lifetime.isAdmitted)
    }

    @Test(timeout = 5000) fun blockingProviderReadDoesNotHoldAdmissionLockAndLateDataIsDiscarded() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicReference<String?>("not returned")
        val worker = thread(isDaemon = true) {
            result.set(token.read<String?>(null) { entered.countDown(); release.await(); "fixture" })
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            lifetime.revoke()
            assertFalse(token.isCurrent())
        } finally { release.countDown() }
        worker.join(2000)
        assertFalse(worker.isAlive); assertNull(result.get())
    }

    @Test(timeout = 5000) fun admittedMutationMayFinishAfterRevokeAndIsNeverClaimedAsDrained() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val worker = thread(isDaemon = true) {
            result.set(token.mutate { entered.countDown(); release.await(); true })
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); lifetime.revoke()
        } finally { release.countDown() }
        worker.join(2000)
        assertFalse(worker.isAlive); assertEquals(true, result.get()); assertFalse(token.isCurrent())
    }

    @Test fun referenceIdentitiesAreNeverLoggedByDefaultToString() {
        val lifetime = PrivateInputLifetime("root fixture"); lifetime.publish("presentation fixture")
        val token = lifetime.acquire("owner fixture")!!
        listOf(lifetime.toString(), token.toString(), token.snapshot.toString()).forEach {
            assertFalse(it.contains("fixture")); assertTrue(it.contains("[redacted]"))
        }
    }
}
