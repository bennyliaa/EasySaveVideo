package com.easysavevideo.app.browser.privacy

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Production bookkeeping with deterministic fake ports. No Android/IME/native erasure claim. */
class PrivateInputRetirementTest {
    private class Fixture {
        val lifetime = PrivateInputLifetime(Any())
        val ticket = lifetime.beginRetirementTracking()!!
        init { check(lifetime.publish(Any())) }
        val token = lifetime.acquire(Any())!!
    }

    @Test fun optInRequiresUnpublishedAndCannotReplaceUnknownTracker() {
        val f = Fixture()
        assertNull(f.lifetime.beginRetirementTracking())
        f.lifetime.revoke()
        assertNull(f.lifetime.beginRetirementTracking())
        assertFalse(f.lifetime.finishRetirementTracking(f.ticket))
    }

    @Test fun emptyRealTicketCanRetireOnceAndRestoreDefaultPath() {
        val f = Fixture(); var completed = 0
        f.ticket.retire { completed++ }; f.ticket.retire { completed += 100 }
        assertEquals(1, completed); assertTrue(f.ticket.isRetired)
        assertFalse(f.token.isCurrent()); assertFalse(f.lifetime.publish(Any()))
        assertTrue(f.lifetime.finishRetirementTracking(f.ticket))
        assertFalse(f.lifetime.finishRetirementTracking(f.ticket))
        assertTrue(f.lifetime.publish(Any()))
        assertFalse(f.lifetime.acquire(Any())!!.hasRetirementTracking)
    }

    @Test fun ordinaryAndBrowserDefaultDoNotAcquireNewBehavior() {
        val lifetime = PrivateInputLifetime(Any()); lifetime.publish(Any())
        val token = lifetime.acquire(Any())!!
        assertFalse(token.hasRetirementTracking)
        assertNull(token.beginConnectionCreation())
        assertTrue(token.mutate { lifetime.revoke(); true })
        lifetime.publish(Any()); assertTrue(lifetime.isAdmitted)
    }

    @Test fun sealRejectsAllOldTokenOperationsAndNewFactories() {
        val f = Fixture(); var calls = 0
        f.ticket.retire { }
        assertFalse(f.token.mutate { calls++; true })
        assertNull(f.token.read<String?>(null) { calls++; "fixture" })
        assertNull(f.token.beginConnectionCreation())
        assertEquals(0, calls)
    }

    @Test fun merelyRevokingInputIsNotARetirementProof() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        creation.invocation.returned(); f.lifetime.revoke()
        assertFalse(f.lifetime.isAdmitted); assertFalse(f.ticket.isRetired)
        f.ticket.retire { fail("Unknown connection must retain fence") }
        assertFalse(f.ticket.isRetired); assertFalse(f.lifetime.finishRetirementTracking(f.ticket))
        assertNull(f.lifetime.beginRetirementTracking())
    }

    @Test(timeout = 5000) fun blockedReadWaitsForActualReturnAndDropsLateData() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val completed = AtomicInteger(); val result = AtomicReference<String?>("pending")
        val worker = thread(isDaemon = true) {
            result.set(f.token.read<String?>(null) { entered.countDown(); release.await(); "fixture" })
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.ticket.retire { completed.incrementAndGet() }
            assertEquals(0, completed.get()); assertFalse(f.ticket.isRetired)
        } finally { release.countDown() }
        worker.join(2000); assertFalse(worker.isAlive)
        assertNull(result.get()); assertTrue(f.ticket.isRetired); assertEquals(1, completed.get())
    }

    @Test(timeout = 5000) fun closeRunsOnlyAfterAdmittedMutationActuallyReturns() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closes = AtomicInteger(); val completed = AtomicInteger()
        creation.connection.bindClose { closes.incrementAndGet(); creation.connection.confirmClosed() }
        creation.invocation.returned()
        val worker = thread(isDaemon = true) { f.token.mutate { entered.countDown(); release.await(); true } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.ticket.retire { completed.incrementAndGet() }
            assertEquals(0, closes.get()); assertEquals(0, completed.get())
        } finally { release.countDown() }
        worker.join(2000); assertFalse(worker.isAlive)
        assertEquals(1, closes.get()); assertEquals(1, completed.get())
    }

    @Test fun thrownProviderCallReturnsItsInvocationDebtButDoesNotForgeClose() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        creation.invocation.returned()
        var done = 0
        try { f.token.mutate { f.ticket.retire { done++ }; throw IllegalStateException() }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(f.ticket.isRetired); assertEquals(0, done)
        creation.connection.confirmClosed()
        assertTrue(f.ticket.isRetired); assertEquals(1, done)
    }

    @Test fun blockedFactoryOrHandlerReservationCannotAckBeforeConnectionRegisters() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        var calls = 0; var closed = 0
        f.ticket.retire { calls++ }
        assertEquals(0, calls)
        creation.connection.bindClose { closed++; creation.connection.confirmClosed() }
        assertEquals(0, closed); assertEquals(0, calls)
        creation.invocation.returned()
        assertEquals(1, closed); assertEquals(1, calls)
    }

    @Test fun normalNullFactoryStillWaitsForActualInvocationReturn() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!; var done = 0
        f.ticket.retire { done++ }
        creation.connection.confirmClosed()
        assertEquals(0, done)
        creation.invocation.returned(); creation.invocation.returned()
        assertEquals(1, done)
    }

    @Test fun thrownFactoryOrUnownedReturnedDelegateKeepsFenceAfterCallReturns() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        f.ticket.retire { fail("No positive close") }
        creation.invocation.returned()
        assertFalse(f.ticket.isRetired); assertFalse(f.lifetime.finishRetirementTracking(f.ticket))
    }

    @Test fun cleanupRequestedAndQueuedDoNotProveClose() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        val queue = mutableListOf<Runnable>(); var done = 0
        val cleanup = PrivateInputCleanup(f.token, { queue.add(it); true },
            onCleanupReturned = creation.connection::confirmClosed) { }
        creation.connection.bindClose(cleanup::close); creation.invocation.returned()
        f.ticket.retire { done++ }
        assertEquals(0, done); assertFalse(f.ticket.isRetired)
        queue.single().run(); queue.single().run()
        assertEquals(1, done); assertTrue(f.ticket.isRetired)
    }

    @Test fun rejectingScheduleCannotAckEvenIfItIllegallyRunsTheCallback() {
        listOf(false, true).forEach { runBeforeReturn ->
            val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
            var queued: Runnable? = null
            val cleanup = PrivateInputCleanup(f.token, { queued = it; if (runBeforeReturn) it.run(); false },
                onCleanupReturned = creation.connection::confirmClosed) { }
            creation.connection.bindClose(cleanup::close); creation.invocation.returned()
            f.ticket.retire { fail("Rejected schedule is unknown") }
            queued!!.run()
            assertFalse(f.ticket.isRetired)
        }
    }

    @Test fun thrownCloseOrSchedulingDoesNotPublishRetirement() {
        listOf(false, true).forEach { schedulingThrows ->
            val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
            val cleanup = PrivateInputCleanup(f.token, { if (schedulingThrows) throw IllegalStateException(); it.run(); true },
                onCleanupReturned = creation.connection::confirmClosed) { throw IllegalStateException() }
            creation.connection.bindClose(cleanup::close); creation.invocation.returned()
            f.ticket.retire { fail("Thrown close is unknown") }
            assertFalse(f.ticket.isRetired)
        }
    }

    @Test fun positiveReceiptRequiresExactLeaseAndSuccessfulCleanupReturn() {
        val f = Fixture(); val registry = PrivateInputDelegateLeases(); val delegate = Any()
        val lease = registry.acquire(delegate, f.token)!!
        val creation = f.token.beginConnectionCreation()!!; var successful = false
        val cleanup = PrivateInputCleanup(f.token, { it.run(); true }, onCleanupReturned = {
            if (successful) creation.connection.confirmClosed()
        }) { successful = lease.cleanupIfOwned { } }
        creation.connection.bindClose(cleanup::close); creation.invocation.returned()
        // Replacement invalidates the old exact lease without affecting the captured root token.
        assertNotNull(registry.acquire(delegate, f.token))
        f.ticket.retire { fail("Stale lease cannot prove close") }
        assertFalse(successful); assertFalse(f.ticket.isRetired)
    }

    @Test fun allConnectionsMustPositivelyCloseAndDuplicateAckHasNoEffect() {
        val f = Fixture(); val one = f.token.beginConnectionCreation()!!; val two = f.token.beginConnectionCreation()!!
        one.invocation.returned(); two.invocation.returned(); var done = 0
        f.ticket.retire { done++ }
        one.connection.confirmClosed(); one.connection.confirmClosed()
        assertEquals(0, done)
        two.connection.confirmClosed(); two.connection.confirmClosed()
        assertEquals(1, done)
    }

    @Test fun oldReceiptCannotRetireOrRemoveNewClaimTracker() {
        val f = Fixture(); f.ticket.retire { }; assertTrue(f.lifetime.finishRetirementTracking(f.ticket))
        val next = f.lifetime.beginRetirementTracking()!!
        assertTrue(f.lifetime.publish(Any())); val token = f.lifetime.acquire(Any())!!
        f.ticket.retire { fail("Old callback cannot be replaced") }
        assertFalse(f.lifetime.finishRetirementTracking(f.ticket))
        assertTrue(token.isCurrent()); assertFalse(next.isRetired)
        assertFalse(f.token.mutate { fail("Old token admitted"); true })
    }

    @Test fun disposeDoesNotEraseUnknownDebtOrPermitReplacement() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        creation.invocation.returned(); f.lifetime.dispose(); f.ticket.retire { }
        assertFalse(f.ticket.isRetired); assertNull(f.lifetime.beginRetirementTracking())
        creation.connection.confirmClosed(); assertTrue(f.ticket.isRetired)
        assertTrue(f.lifetime.finishRetirementTracking(f.ticket)); assertFalse(f.lifetime.publish(Any()))
    }

    @Test(timeout = 5000) fun callbackAndCloseExecuteOutsideTrackingLockAndAllowOwnerReentry() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        creation.connection.bindClose {
            val closeReturn = thread(isDaemon = true) { creation.connection.confirmClosed() }
            closeReturn.join(2000); assertFalse(closeReturn.isAlive)
        }
        creation.invocation.returned()
        var done = 0
        f.ticket.retire {
            val releaseOwner = thread(isDaemon = true) { assertTrue(f.lifetime.finishRetirementTracking(f.ticket)) }
            releaseOwner.join(2000); assertFalse(releaseOwner.isAlive); done++
        }
        assertEquals(1, done)
    }

    @Test(timeout = 5000) fun competingSealAndAdmissionNeverAcknowledgeAnUnreturnedInvocation() {
        repeat(100) {
            val f = Fixture(); val start = CountDownLatch(1); val release = CountDownLatch(1)
            val admitted = CountDownLatch(1); val done = AtomicInteger()
            val worker = thread(isDaemon = true) {
                start.await(); f.token.mutate { admitted.countDown(); release.await(); true }
            }
            start.countDown(); f.ticket.retire { done.incrementAndGet() }
            if (admitted.count == 0L) assertEquals(0, done.get())
            release.countDown(); worker.join(2000); assertFalse(worker.isAlive)
            assertEquals(1, done.get()); assertTrue(f.ticket.isRetired)
        }
    }
    @Test(timeout = 5000) fun platformCloseBeforeHostSealWaitsForAlreadyAdmittedWork() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        val connectionToken = f.token.forConnection(creation.connection)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closes = AtomicInteger()
        creation.connection.bindClose { closes.incrementAndGet(); creation.connection.confirmClosed() }
        creation.invocation.returned()
        val worker = thread(isDaemon = true) { connectionToken.mutate { entered.countDown(); release.await(); true } }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            connectionToken.revoke(); creation.connection.requestClose()
            assertEquals(0, closes.get()); assertTrue(f.token.isCurrent())
            assertFalse(connectionToken.isCurrent())
        } finally { release.countDown() }
        worker.join(2000); assertFalse(worker.isAlive); assertEquals(1, closes.get())
        assertFalse(f.ticket.isRetired)
        f.ticket.retire { }; assertTrue(f.ticket.isRetired)
    }

    @Test fun synchronousCleanupReceiptWaitsForSchedulerAcceptanceAndCleanupReturn() {
        val f = Fixture(); val creation = f.token.beginConnectionCreation()!!
        var cleanupReturned = false; var schedulerAccepted = false; var completed = 0
        val cleanup = PrivateInputCleanup(f.token, { command ->
            command.run(); assertEquals(0, completed); schedulerAccepted = true; true
        }, onCleanupReturned = {
            assertTrue(cleanupReturned); assertTrue(schedulerAccepted); creation.connection.confirmClosed()
        }) { assertEquals(0, completed); cleanupReturned = true }
        creation.connection.bindClose(cleanup::close); creation.invocation.returned()
        f.ticket.retire { completed++ }
        assertEquals(1, completed)
    }

    @Test(timeout = 5000) fun competingPublicationCannotReopenASealedTicket() {
        repeat(100) {
            val lifetime = PrivateInputLifetime(Any()); val ticket = lifetime.beginRetirementTracking()!!
            val start = CountDownLatch(1)
            val worker = thread(isDaemon = true) { start.await(); lifetime.publish(Any()) }
            start.countDown(); ticket.retire { }; worker.join(2000)
            assertFalse(worker.isAlive); assertTrue(ticket.isRetired); assertFalse(lifetime.isAdmitted)
            assertTrue(lifetime.finishRetirementTracking(ticket))
        }
    }

}
