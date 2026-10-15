package com.easysavevideo.app.browser.privacy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Production lease/token/cleanup gates with fake provider ports; not Android/provider acceptance. */
class PrivateInputDelegateLeaseTest {
    private fun token(): PrivateInputToken =
        PrivateInputLifetime(Any()).also { it.publish(Any()) }.acquire(Any())!!

    private class Delegate {
        val cursorStops = AtomicInteger()
        val closes = AtomicInteger()
        var onCursorStop: () -> Unit = {}
        var onClose: () -> Unit = {}

        fun requestCursorUpdates(mode: Int) {
            check(mode == 0)
            cursorStops.incrementAndGet()
            onCursorStop()
        }

        fun closeConnection() { closes.incrementAndGet(); onClose() }

        fun cleanup() { requestCursorUpdates(0); closeConnection() }
    }

    @Test fun queuedCleanupCannotTouchSameDelegateReusedByNewGeneration() {
        val registry = PrivateInputDelegateLeases()
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val delegate = Delegate()
        val oldSession = lifetime.acquire(Any())!!
        val oldLease = registry.acquire(delegate, oldSession)!!
        val oldToken = oldSession.fork(oldLease::isCurrent)!!
        val queue = mutableListOf<Runnable>()
        val oldCleanup = PrivateInputCleanup(oldToken, { queue.add(it); true }) {
            oldLease.cleanupIfOwned(delegate::cleanup)
        }
        oldCleanup.close()
        lifetime.publish(Any())
        val freshSession = lifetime.acquire(Any())!!
        val freshLease = registry.acquire(delegate, freshSession)!!
        val freshToken = freshSession.fork(freshLease::isCurrent)!!

        queue.single().run(); queue.single().run()

        assertEquals(0, delegate.cursorStops.get()); assertEquals(0, delegate.closes.get())
        assertFalse(oldToken.isCurrent()); assertFalse(oldLease.isCurrent())
        assertTrue(freshToken.isCurrent())
        assertTrue(freshToken.snapshot.generation > oldToken.snapshot.generation)
        assertTrue(freshLease.cleanupIfOwned(delegate::cleanup))
        assertEquals(1, delegate.cursorStops.get()); assertEquals(1, delegate.closes.get())
        assertFalse(freshToken.isCurrent())
    }

    @Test fun presentationAbaAndSeveralQueuedClosesNeverReviveObsoleteLeases() {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate()
        val lifetime = PrivateInputLifetime(Any()); val presentationA = Any()
        val queue = mutableListOf<Runnable>()
        fun issueAndQueueClose(presentation: Any): PrivateInputDelegateLease {
            lifetime.publish(presentation)
            val session = lifetime.acquire(Any())!!
            val lease = registry.acquire(delegate, session)!!
            val leasedToken = session.fork(lease::isCurrent)!!
            PrivateInputCleanup(leasedToken, { queue.add(it); true }) {
                lease.cleanupIfOwned(delegate::cleanup)
            }.close()
            return lease
        }
        val first = issueAndQueueClose(presentationA)
        val second = issueAndQueueClose(Any())
        lifetime.publish(presentationA)
        val session = lifetime.acquire(Any())!!
        val third = registry.acquire(delegate, session)!!
        val current = session.fork(third::isCurrent)!!

        first.abandon(); second.abandon()
        queue.asReversed().forEach(Runnable::run)
        queue.forEach(Runnable::run)

        assertFalse(first.isCurrent()); assertFalse(second.isCurrent()); assertTrue(current.isCurrent())
        assertEquals(0, delegate.cursorStops.get()); assertEquals(0, delegate.closes.get())
        assertTrue(third.cleanupIfOwned(delegate::cleanup))
        assertFalse(third.cleanupIfOwned(delegate::cleanup))
        assertEquals(1, delegate.cursorStops.get()); assertEquals(1, delegate.closes.get())
    }

    @Test fun replacementWithinSamePublicationRevokesOldLeaseTokenOnly() {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate(); val session = token()
        val old = registry.acquire(delegate, session)!!
        val oldToken = session.fork(old::isCurrent)!!
        val fresh = registry.acquire(delegate, session)!!
        val freshToken = session.fork(fresh::isCurrent)!!
        var mutations = 0

        assertFalse(oldToken.mutate { mutations++; true })
        assertFalse(old.cleanupIfOwned(delegate::cleanup))
        assertTrue(freshToken.mutate { mutations++; true })
        assertEquals(1, mutations); assertTrue(session.isCurrent())
        assertEquals(0, delegate.cursorStops.get()); assertEquals(0, delegate.closes.get())
    }

    @Test fun lateStaleFactoryCannotRetireCurrentDelegateLease() {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate()
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val stale = lifetime.acquire(Any())!!
        lifetime.publish(Any())
        val current = lifetime.acquire(Any())!!
        val currentLease = registry.acquire(delegate, current)!!

        assertNull(registry.acquire(delegate, stale))
        assertTrue(currentLease.isCurrent())
        assertTrue(current.fork(currentLease::isCurrent)!!.isCurrent())
        current.revoke()
        assertNull(registry.acquire(delegate, current))
        assertTrue(currentLease.isCurrent())
    }

    @Test fun abandonedConstructionCannotCleanOrRemoveItsReplacement() {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate(); val session = token()
        val abandoned = registry.acquire(delegate, session)!!
        abandoned.abandon()
        assertFalse(abandoned.isCurrent())
        val fresh = registry.acquire(delegate, session)!!
        abandoned.abandon()
        assertFalse(abandoned.cleanupIfOwned(delegate::cleanup))
        assertTrue(fresh.isCurrent())
        assertEquals(0, delegate.cursorStops.get()); assertEquals(0, delegate.closes.get())
    }

    @Test fun identityRegistryNeverCallsProviderEqualsHashCodeOrToString() {
        class HostileDelegate {
            override fun equals(other: Any?): Boolean = error("Provider equals must not run")
            override fun hashCode(): Int = error("Provider hashCode must not run")
            override fun toString(): String = error("Provider toString must not run")
        }
        val registry = PrivateInputDelegateLeases(); val session = token()
        val firstDelegate = HostileDelegate(); val secondDelegate = HostileDelegate()
        val first = registry.acquire(firstDelegate, session)!!
        val second = registry.acquire(secondDelegate, session)!!
        assertTrue(first.isCurrent()); assertTrue(second.isCurrent())
        val replacement = registry.acquire(firstDelegate, session)!!
        assertFalse(first.isCurrent()); assertTrue(second.isCurrent()); assertTrue(replacement.isCurrent())
        assertTrue(second.cleanupIfOwned {})
        assertTrue(replacement.isCurrent())
    }

    @Test fun cleanupCanReenterRegistryButCannotPublishOrReleaseItsOwnClaim() {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate(); val session = token()
        val lease = registry.acquire(delegate, session)!!
        var nestedCleanupCalls = 0
        delegate.onCursorStop = {
            lease.abandon()
            assertFalse(lease.isCurrent())
            assertNull(registry.acquire(delegate, session))
            assertFalse(lease.cleanupIfOwned { nestedCleanupCalls++ })
            assertNotNull(registry.acquire(Any(), session))
        }
        delegate.onClose = { assertNull(registry.acquire(delegate, session)) }

        assertTrue(lease.cleanupIfOwned(delegate::cleanup))

        assertEquals(0, nestedCleanupCalls)
        assertEquals(1, delegate.cursorStops.get()); assertEquals(1, delegate.closes.get())
        assertNotNull(registry.acquire(delegate, session))
    }

    @Test fun throwingCleanupReleasesExclusionInFinallyWithoutRevivingOldLease() {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate(); val session = token()
        val old = registry.acquire(delegate, session)!!
        val failure = IllegalStateException()
        try {
            old.cleanupIfOwned { throw failure }
            fail("Expected provider failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse(old.isCurrent())
        val fresh = registry.acquire(delegate, session)!!
        assertFalse(old.cleanupIfOwned(delegate::cleanup))
        old.abandon()
        assertTrue(fresh.isCurrent())
        assertEquals(0, delegate.cursorStops.get()); assertEquals(0, delegate.closes.get())
    }

    @Test(timeout = 5000) fun claimedBlockedCursorCleanupExcludesReplacementWithoutHoldingRegistryLock() {
        assertBlockedCleanupExcludesReplacement(blockCursorStop = true)
    }

    @Test(timeout = 5000) fun claimedBlockedCloseCleanupExcludesReplacementUntilBothProviderCallsReturn() {
        assertBlockedCleanupExcludesReplacement(blockCursorStop = false)
    }

    private fun assertBlockedCleanupExcludesReplacement(blockCursorStop: Boolean) {
        val registry = PrivateInputDelegateLeases(); val delegate = Delegate()
        val otherDelegate = Delegate(); val lifetime = PrivateInputLifetime(Any())
        lifetime.publish(Any())
        val oldSession = lifetime.acquire(Any())!!
        val lease = registry.acquire(delegate, oldSession)!!
        val oldToken = oldSession.fork(lease::isCurrent)!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val block: () -> Unit = { entered.countDown(); release.await() }
        if (blockCursorStop) delegate.onCursorStop = block else delegate.onClose = block
        val cleanupFailure = AtomicReference<Throwable?>()
        val cleanup = PrivateInputCleanup(oldToken, { it.run(); true }) {
            lease.cleanupIfOwned(delegate::cleanup)
        }
        val cleaner = thread(isDaemon = true) {
            try { cleanup.close() } catch (failure: Throwable) { cleanupFailure.set(failure) }
        }
        val attempted = CountDownLatch(1)
        val replacement = AtomicReference<PrivateInputDelegateLease?>()
        val unrelated = AtomicReference<PrivateInputDelegateLease?>()
        val contenderFailure = AtomicReference<Throwable?>()
        var contender: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // Publish the replacement presentation while the old provider cleanup is blocked.
            lifetime.publish(Any())
            val freshSession = lifetime.acquire(Any())!!
            contender = thread(isDaemon = true) {
                try {
                    lease.abandon()
                    replacement.set(registry.acquire(delegate, freshSession))
                    unrelated.set(registry.acquire(otherDelegate, freshSession))
                    assertFalse(lease.cleanupIfOwned(delegate::cleanup))
                } catch (failure: Throwable) { contenderFailure.set(failure) }
                finally { attempted.countDown() }
            }
            // This must return while the provider is still blocked, proving no registry lock
            // spans either cursor-stop or close I/O. Same-delegate publication remains denied.
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertNull(contenderFailure.get()); assertNull(replacement.get())
            assertNotNull(unrelated.get()); assertTrue(unrelated.get()!!.isCurrent())
            assertFalse(oldToken.isCurrent()); assertFalse(lease.isCurrent())
            assertEquals(1, delegate.cursorStops.get())
            assertEquals(if (blockCursorStop) 0 else 1, delegate.closes.get())
        } finally {
            release.countDown()
            cleaner.join(1000); contender?.join(1000)
        }
        assertFalse(cleaner.isAlive); assertFalse(contender?.isAlive ?: false)
        assertNull(cleanupFailure.get()); assertNull(contenderFailure.get())
        assertEquals(1, delegate.cursorStops.get()); assertEquals(1, delegate.closes.get())
        val freshSession = lifetime.acquire(Any())!!
        val freshLease = registry.acquire(delegate, freshSession)!!
        assertTrue(freshSession.fork(freshLease::isCurrent)!!.isCurrent())
        cleanup.close()
        assertFalse(lease.cleanupIfOwned(delegate::cleanup))
        assertTrue(freshLease.isCurrent())
        assertEquals(1, delegate.cursorStops.get()); assertEquals(1, delegate.closes.get())
    }
}
