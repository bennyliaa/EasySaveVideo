package com.easysavevideo.save

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.OperationContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FixedDirectTransferPoolTest {
    @Test fun bindsTwoDistinctInstancesAndRefusesThirdOwner() {
        val first = Port(); val second = Port(); val pool = FixedDirectTransferPool(first, second)
        val a = Any(); val b = Any(); val c = Any()
        assertSame(first, pool.acquire(a)); assertSame(second, pool.acquire(b))
        assertNull(pool.acquire(c))
        repeat(3) { assertSame(first, pool.acquire(a)); assertSame(second, pool.acquire(b)) }
        assertTrue(pool.release(a)); assertFalse(pool.release(a))
        assertSame(first, pool.acquire(c)); assertSame(second, pool.acquire(b))
        assertFalse(pool.release(a))
        assertNull(pool.acquire(Any()))
    }

    @Test fun equalButDifferentOwnersCannotReuseOrReleaseEachOther() {
        val first = Port(); val second = Port(); val pool = FixedDirectTransferPool(first, second)
        val a = EqualOwner(); val b = EqualOwner(); val stale = EqualOwner()
        assertSame(first, pool.acquire(a)); assertSame(second, pool.acquire(b))
        assertNull(pool.acquire(stale)); assertFalse(pool.release(stale))
        assertSame(first, pool.acquire(a)); assertSame(second, pool.acquire(b))
    }

    @Test fun oneTransportCannotBeConfiguredAsTwoLanes() {
        val port = Port()
        try { FixedDirectTransferPool(port, port); fail("Duplicate transport accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun transportIdentityDoesNotUseEquals() {
        val first = EqualPort(); val second = EqualPort()
        val pool = FixedDirectTransferPool(first, second)
        assertSame(first, pool.acquire(Any())); assertSame(second, pool.acquire(Any()))
    }

    @Test fun transferOnPoolNeverChoosesAnImplicitOwner() {
        val first = Port(); val second = Port(); val pool = FixedDirectTransferPool(first, second)
        assertFailure(SaveError.BUSY) { transfer(pool) }
        assertEquals(0, first.calls.get()); assertEquals(0, second.calls.get())
    }

    @Test fun bothPinnedPortsRunTogetherAndRetryKeepsExactPort() {
        val entered = CountDownLatch(2); val finish = CountDownLatch(1)
        val first = Port { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
        val second = Port { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
        val pool = FixedDirectTransferPool(first, second); val a = Any(); val b = Any()
        assertSame(first, pool.acquire(a)); assertSame(second, pool.acquire(b))
        val failures = AtomicReference<Throwable?>(null)
        val workers = listOf(a, b).map { owner ->
            thread(isDaemon = true) {
                try { transfer(requireNotNull(pool.acquire(owner))) }
                catch (error: Throwable) { failures.compareAndSet(null, error) }
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNull(pool.acquire(Any()))
            assertSame(first, pool.acquire(a)); assertSame(second, pool.acquire(b))
        } finally { finish.countDown(); workers.forEach { it.join(3000) } }
        workers.forEach { assertFalse(it.isAlive) }
        failures.get()?.let { throw AssertionError("Transport worker failed", it) }
        val pinned = pool.acquire(a)
        transfer(requireNotNull(pinned)); assertSame(pinned, pool.acquire(a))
        assertEquals(3, first.calls.get() + second.calls.get())
    }

    @Test fun idlePortPoisonBlocksFreshOwnersAndNeverResets() {
        val first = Port(); val second = Port(); val pool = FixedDirectTransferPool(first, second)
        val owner = Any(); assertSame(first, pool.acquire(owner)); assertTrue(pool.release(owner))
        second.poison.set(true)
        assertTrue(pool.requiresProcessRecovery())
        second.poison.set(false)
        assertTrue(pool.requiresProcessRecovery()); assertNull(pool.acquire(Any()))
        assertFalse(pool.release(owner))
        assertEquals(0, first.calls.get()); assertEquals(0, second.calls.get())
    }

    @Test fun releaseObservesPoisonAndNeverReplacesPinnedTransport() {
        val first = Port(); val second = Port(); val pool = FixedDirectTransferPool(first, second)
        val owner = Any(); assertSame(first, pool.acquire(owner))
        first.poison.set(true)
        assertSame(first, pool.acquire(owner))
        assertTrue(pool.release(owner)); first.poison.set(false)
        assertTrue(pool.requiresProcessRecovery()); assertNull(pool.acquire(Any()))
    }

    @Test fun recoveryAlwaysScansBothFixedInstancesIncludingIdleSlots() {
        val first = Port(); val second = Port(); val pool = FixedDirectTransferPool(first, second)
        first.poison.set(true)
        repeat(3) { assertTrue(pool.requiresProcessRecovery()) }
        assertEquals(3, first.probes.get()); assertEquals(3, second.probes.get())
    }

    @Test fun exceptionFromEitherProbeLatchesPoisonAndBothFixedPortsAreStillScanned() {
        for (failingSlot in 0..1) {
            val failProbe = AtomicBoolean(true)
            fun port(slot: Int) = object : Port() {
                override fun requiresProcessRecovery(): Boolean {
                    probes.incrementAndGet()
                    if (slot == failingSlot && failProbe.get()) throw IllegalStateException("Probe unavailable")
                    return false
                }
            }
            val first = port(0); val second = port(1); val pool = FixedDirectTransferPool(first, second)
            assertTrue(pool.requiresProcessRecovery())
            assertEquals(1, first.probes.get()); assertEquals(1, second.probes.get())
            failProbe.set(false)
            assertTrue(pool.requiresProcessRecovery()); assertNull(pool.acquire(Any()))
            assertEquals(3, first.probes.get()); assertEquals(3, second.probes.get())
        }
    }

    @Test fun probeExceptionDuringReleaseCannotClearPoisonOrSubstituteTransport() {
        val failProbe = AtomicBoolean(false)
        val first = object : Port() {
            override fun requiresProcessRecovery(): Boolean {
                if (failProbe.get()) throw IllegalStateException("Probe unavailable")
                return false
            }
        }
        val pool = FixedDirectTransferPool(first, Port()); val owner = Any()
        assertSame(first, pool.acquire(owner)); failProbe.set(true)
        assertTrue(pool.release(owner)); failProbe.set(false)
        assertTrue(pool.requiresProcessRecovery()); assertNull(pool.acquire(Any()))
        assertFalse(pool.release(owner))
    }

    @Test fun failedFirstProbeLatchesBeforeSecondProbeFinishes() {
        val scanning = AtomicReference<Thread?>(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val first = object : Port() {
            override fun requiresProcessRecovery(): Boolean {
                if (Thread.currentThread() === scanning.get()) throw IllegalStateException("Probe unavailable")
                return false
            }
        }
        val second = object : Port() {
            override fun requiresProcessRecovery(): Boolean {
                if (Thread.currentThread() === scanning.get()) {
                    entered.countDown(); check(finish.await(3, TimeUnit.SECONDS))
                }
                return false
            }
        }
        val pool = FixedDirectTransferPool(first, second); val failure = AtomicReference<Throwable?>()
        val scanner = thread(isDaemon = true) {
            scanning.set(Thread.currentThread())
            try { assertTrue(pool.requiresProcessRecovery()) }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNull(pool.acquire(Any()))
        } finally { finish.countDown(); scanner.join(3000) }
        assertFalse(scanner.isAlive)
        failure.get()?.let { throw AssertionError("Pool worker failed", it) }
    }

    @Test fun bindingMonitorIsNotHeldWhileConsultingDelegateRecovery() {
        val scanning = AtomicReference<Thread?>(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val first = object : Port() {
            override fun requiresProcessRecovery(): Boolean {
                if (Thread.currentThread() === scanning.get()) {
                    entered.countDown(); check(finish.await(3, TimeUnit.SECONDS))
                }
                return false
            }
        }
        val pool = FixedDirectTransferPool(first, Port()); val owner = Any()
        assertSame(first, pool.acquire(owner))
        val released = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        val scanner = thread(isDaemon = true) {
            scanning.set(Thread.currentThread())
            try { pool.acquire(Any()) }
            catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val releaser = thread(isDaemon = true) {
            try { assertTrue(pool.release(owner)) }
            catch (error: Throwable) { failure.set(error) }
            finally { released.countDown() }
        }
        try { assertTrue("Recovery probe held binding monitor", released.await(3, TimeUnit.SECONDS)) }
        finally { finish.countDown(); scanner.join(3000); releaser.join(3000) }
        assertFalse(scanner.isAlive); assertFalse(releaser.isAlive)
        failure.get()?.let { throw AssertionError("Pool worker failed", it) }
    }

    private class EqualOwner {
        override fun equals(other: Any?): Boolean = other is EqualOwner
        override fun hashCode(): Int = error("Owner hashCode must not be used")
    }
    private class EqualPort : Port() {
        override fun equals(other: Any?): Boolean = other is EqualPort
        override fun hashCode(): Int = error("Transport hashCode must not be used")
    }
    private open class Port(private val work: () -> Unit = {}) : DirectTransferPort {
        val poison = AtomicBoolean(false)
        val calls = AtomicInteger()
        val probes = AtomicInteger()
        override fun requiresProcessRecovery(): Boolean { probes.incrementAndGet(); return poison.get() }
        override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
            checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
            progress: TransferProgress): TransferResult {
            calls.incrementAndGet(); work(); return TransferResult(artifact.artifactId, 1, "video/mp4")
        }
    }
    private fun transfer(port: DirectTransferPort) = port.transfer(
        SelectedMediaResource("candidate", "variant", SourceUrl("https://example.test/media")),
        StagingArtifact("artifact", "commit"), null,
        OperationContext("operation", 1000, CancellationSource()), SaveLimits(1024, 2048),
        object : TransferProgress {
            override fun onBytes(delta: Long) = Unit
            override fun onCheckpoint(checkpoint: DownloadCheckpoint) = Unit
            override fun onRestart() = Unit
        },
    )
    private fun assertFailure(expected: SaveError, block: () -> Unit) {
        try { block(); fail("Expected failure") }
        catch (failure: SaveFailure) { assertEquals(expected, failure.code) }
    }
}
