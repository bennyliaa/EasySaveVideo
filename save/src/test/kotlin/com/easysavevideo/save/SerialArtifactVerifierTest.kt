package com.easysavevideo.save

import com.easysavevideo.core.model.CancellationSource
import com.easysavevideo.core.model.CancellationToken
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SerialArtifactVerifierTest {
    @Test fun typedDescriptorIsForwardedWithoutManufacturingAnotherTransfer() {
        val transfer = TransferResult("artifact", 1, "video/mp4")
        val output = ArtifactOutputDescriptor.Transferred(transfer)
        val context = operation()
        val delegate = object : ArtifactVerifier {
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence = error("Typed dispatch lost")
            override fun verify(artifact: StagingArtifact, received: ArtifactOutputDescriptor,
                receivedContext: OperationContext): VerificationEvidence {
                assertSame(output, received); assertSame(context, receivedContext)
                return evidence
            }
        }
        assertSame(evidence, SerialArtifactVerifier(delegate, EpochClock { 0 })
            .verify(StagingArtifact("artifact", "commit"), output, context))
    }

    @Test fun typedTransferredDescriptorKeepsLegacyAdapterCompatible() {
        val transfer = TransferResult("artifact", 1, "video/mp4")
        val delegate = ArtifactVerifier { _, actual, _ -> assertSame(transfer, actual); evidence }
        assertSame(evidence, SerialArtifactVerifier(delegate, EpochClock { 0 }).verify(
            StagingArtifact("artifact", "commit"), ArtifactOutputDescriptor.Transferred(transfer), operation()))
    }

    @Test fun oneDelegateAtATimeAndOriginalContextsArePreserved() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        val observedWaiter = ObservedToken()
        val a = operation("a"); val b = operation("b", token = observedWaiter)
        val verifier = SerialArtifactVerifier(ArtifactVerifier { _, _, context ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    assertSame(a, context); entered.countDown(); check(finish.await(3, TimeUnit.SECONDS))
                } else { assertSame(b, context) }
                evidence
            } finally { active.decrementAndGet() }
        }, EpochClock { 0 })
        val first = Worker { verify(verifier, a) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val second = Worker { verify(verifier, b) }
        try {
            assertTrue(observedWaiter.checked.await(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get()); assertEquals(1, maximum.get())
        } finally { finish.countDown() }
        assertSame(evidence, first.success()); assertSame(evidence, second.success())
        assertEquals(2, calls.get()); assertEquals(1, maximum.get())
    }

    @Test fun cancelledWaiterExitsWhileFirstDelegateStillOwnsLane() {
        val h = BlockingVerifier(); val token = ObservedToken()
        val first = Worker { verify(h.serial) }; h.awaitEntered()
        val second = Worker { verify(h.serial, operation("waiting", token = token)) }
        try {
            assertTrue(token.checked.await(3, TimeUnit.SECONDS)); token.cancelled.set(true)
            second.failure(SaveError.CANCELLED)
            assertEquals(1, h.calls.get()); assertEquals(1L, h.finish.count)
        } finally { h.finish.countDown() }
        first.success()
    }

    @Test fun originalDeadlineExpiresWithoutWaitingForActiveDelegateToReturn() {
        val now = AtomicLong(0); val h = BlockingVerifier(EpochClock { now.get() })
        val token = ObservedToken()
        val first = Worker { verify(h.serial) }; h.awaitEntered()
        val second = Worker { verify(h.serial, operation("waiting", deadline = 10, token = token)) }
        try {
            assertTrue(token.checked.await(3, TimeUnit.SECONDS)); now.set(10)
            second.failure(SaveError.DEADLINE_EXCEEDED)
            assertEquals(1, h.calls.get()); assertEquals(1L, h.finish.count)
        } finally { h.finish.countDown() }
        first.success()
    }

    @Test fun interruptingWaiterPreservesInterruptAndDoesNotEnterDelegate() {
        val h = BlockingVerifier(); val token = ObservedToken()
        val first = Worker { verify(h.serial) }; h.awaitEntered()
        val second = Worker { verify(h.serial, operation("waiting", token = token)) }
        try {
            assertTrue(token.checked.await(3, TimeUnit.SECONDS)); second.worker.interrupt()
            second.failure(SaveError.CANCELLED); assertTrue(second.interrupted.get())
            assertEquals(1, h.calls.get())
        } finally { h.finish.countDown() }
        first.success()
    }

    @Test fun stoppedOrInterruptedCallerNeverEntersAnIdleLane() {
        val calls = AtomicInteger()
        val verifier = SerialArtifactVerifier(ArtifactVerifier { _, _, _ -> calls.incrementAndGet(); evidence }, EpochClock { 10 })
        val cancelled = CancellationSource().also { it.cancel() }
        assertFailure(SaveError.CANCELLED) { verify(verifier, operation(token = cancelled)) }
        assertFailure(SaveError.DEADLINE_EXCEEDED) { verify(verifier, operation(deadline = 10)) }
        val interrupted = Worker { Thread.currentThread().interrupt(); verify(verifier) }
        interrupted.failure(SaveError.CANCELLED); assertTrue(interrupted.interrupted.get())
        assertEquals(0, calls.get())
    }

    @Test fun cancellationDuringDelegateFencesLateSuccessfulEvidence() {
        val h = BlockingVerifier(); val token = CancellationSource()
        val worker = Worker { verify(h.serial, operation(token = token)) }; h.awaitEntered()
        token.cancel(); h.finish.countDown()
        worker.failure(SaveError.CANCELLED)
        assertFalse(h.serial.requiresProcessRecovery())
        assertSame(evidence, verify(h.serial, operation("next")))
    }

    @Test fun cancellationAndDeadlineAreRecheckedAfterAcquiringAnIdleLane() {
        val calls = AtomicInteger(); val checks = AtomicInteger()
        val delegate = ArtifactVerifier { _, _, _ -> calls.incrementAndGet(); evidence }
        val token = object : CancellationToken {
            override val isCancelled: Boolean get() = checks.incrementAndGet() >= 2
        }
        val cancelled = SerialArtifactVerifier(delegate, EpochClock { 0 })
        assertFailure(SaveError.CANCELLED) { verify(cancelled, operation(token = token)) }
        val reads = AtomicInteger()
        val expired = SerialArtifactVerifier(delegate, EpochClock { if (reads.incrementAndGet() >= 3) 10 else 0 })
        assertFailure(SaveError.DEADLINE_EXCEEDED) { verify(expired, operation(deadline = 10)) }
        assertEquals(0, calls.get())
    }

    @Test fun nativeRecoveryIsRecheckedAfterAcquiringAnIdleLane() {
        val probes = AtomicInteger(); val calls = AtomicInteger()
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery(): Boolean = probes.incrementAndGet() >= 2
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence { calls.incrementAndGet(); return evidence }
        }, EpochClock { 0 })
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial) }
        assertTrue(serial.requiresProcessRecovery()); assertEquals(0, calls.get())
    }

    @Test fun deadlineDuringDelegateFencesLateSuccessfulEvidence() {
        val now = AtomicLong(0); val h = BlockingVerifier(EpochClock { now.get() })
        val worker = Worker { verify(h.serial, operation(deadline = 10)) }; h.awaitEntered()
        now.set(10); h.finish.countDown()
        worker.failure(SaveError.DEADLINE_EXCEEDED)
        assertSame(evidence, verify(h.serial, operation("next")))
    }

    @Test fun recoveryDuringDelegateRejectsEvidenceAndBlocksUntilNativeRelease() {
        val h = BlockingVerifier()
        val worker = Worker { verify(h.serial) }; h.awaitEntered()
        h.poison.set(true); h.finish.countDown()
        worker.failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        assertTrue(h.serial.requiresProcessRecovery())
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(h.serial, operation("while-draining")) }
        assertEquals(1, h.calls.get())
        h.poison.set(false)
        assertFalse(h.serial.requiresProcessRecovery())
        assertSame(evidence, verify(h.serial, operation("next")))
        assertEquals(2, h.calls.get())
    }

    @Test fun nativeDrainAfterAbandonedDelegateBlocksWaiterDespiteApplicationPermitRelease() {
        val entered = CountDownLatch(1); val abandon = CountDownLatch(1)
        val draining = AtomicBoolean(false); val calls = AtomicInteger(); val token = ObservedToken()
        val delegate = object : ArtifactVerifier {
            override fun requiresProcessRecovery() = draining.get()
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence {
                if (calls.incrementAndGet() > 1) return evidence
                entered.countDown(); check(abandon.await(3, TimeUnit.SECONDS))
                try { throw SaveFailure(SaveError.DEADLINE_EXCEEDED) }
                finally { draining.set(true) }
            }
        }
        val serial = SerialArtifactVerifier(delegate, EpochClock { 0 })
        val first = Worker { verify(serial) }; assertTrue(entered.await(3, TimeUnit.SECONDS))
        val waiting = Worker { verify(serial, operation("waiting", token = token)) }
        try { assertTrue(token.checked.await(3, TimeUnit.SECONDS)) }
        finally { abandon.countDown() }
        first.failure(SaveError.DEADLINE_EXCEEDED)
        waiting.failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        assertTrue(serial.requiresProcessRecovery())
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial, operation("while-draining")) }
        assertEquals(1, calls.get())
        draining.set(false)
        assertFalse(serial.requiresProcessRecovery())
        assertSame(evidence, verify(serial, operation("next")))
        assertEquals(2, calls.get())
    }

    @Test fun unconfirmedReleaseFailureDoesNotReplaceTheDelegateActualReleaseGate() {
        val calls = AtomicInteger()
        val serial = SerialArtifactVerifier(ArtifactVerifier { _, _, _ ->
            if (calls.incrementAndGet() == 1) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            evidence
        }, EpochClock { 0 })
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial) }
        assertFalse(serial.requiresProcessRecovery())
        assertSame(evidence, verify(serial, operation("next")))
        assertEquals(2, calls.get())
    }

    @Test fun onlyActualNativeWorkerFinallyReleaseAllowsNewExplicitVerification() {
        val draining = AtomicBoolean(false); val calls = AtomicInteger()
        val nativeEntered = CountDownLatch(1); val releaseNative = CountDownLatch(1)
        val nativeReleased = CountDownLatch(1); val nativeFailure = AtomicReference<Throwable?>()
        var nativeWorker: Thread? = null
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery() = draining.get()
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence {
                if (calls.incrementAndGet() > 1) return evidence
                draining.set(true)
                nativeWorker = thread(isDaemon = true) {
                    nativeEntered.countDown()
                    try { check(releaseNative.await(3, TimeUnit.SECONDS)) }
                    catch (failure: Throwable) { nativeFailure.set(failure) }
                    finally { draining.set(false); nativeReleased.countDown() }
                }
                check(nativeEntered.await(3, TimeUnit.SECONDS))
                throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            }
        }, EpochClock { 0 })
        try {
            assertFailure(SaveError.DEADLINE_EXCEEDED) { verify(serial) }
            assertTrue(serial.requiresProcessRecovery())
            assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial, operation("while-draining")) }
            assertEquals(1, calls.get()); assertEquals(1L, nativeReleased.count)
        } finally { releaseNative.countDown() }
        assertTrue(nativeReleased.await(3, TimeUnit.SECONDS)); nativeWorker?.join(3000)
        assertFalse(requireNotNull(nativeWorker).isAlive)
        nativeFailure.get()?.let { throw AssertionError("Native worker failed", it) }
        assertFalse(serial.requiresProcessRecovery())
        assertSame(evidence, verify(serial, operation("after-release")))
        assertEquals(2, calls.get())
    }

    @Test fun recoveryObservedWhileRelinquishingPermitAlsoFencesEvidence() {
        val returned = AtomicBoolean(false); val afterReturnProbes = AtomicInteger()
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery(): Boolean = returned.get() && afterReturnProbes.incrementAndGet() == 2
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence { returned.set(true); return evidence }
        }, EpochClock { 0 })
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial) }
        assertFalse(serial.requiresProcessRecovery())
        assertSame(evidence, verify(serial, operation("after-release")))
    }

    @Test fun ordinaryDelegateFailureReleasesApplicationLaneWithoutPoison() {
        val calls = AtomicInteger()
        val serial = SerialArtifactVerifier(ArtifactVerifier { _, _, _ ->
            if (calls.incrementAndGet() == 1) throw SaveFailure(SaveError.INVALID_MEDIA)
            evidence
        }, EpochClock { 0 })
        assertFailure(SaveError.INVALID_MEDIA) { verify(serial) }
        assertFalse(serial.requiresProcessRecovery())
        assertSame(evidence, verify(serial, operation("next"))); assertEquals(2, calls.get())
    }

    @Test fun originalStopReasonIsRetainedWhileNativePoisonStillGetsObserved() {
        val token = CancellationSource().also { it.cancel() }
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery() = true
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence = error("Stopped caller entered delegate")
        }, EpochClock { 0 })
        assertFailure(SaveError.CANCELLED) { verify(serial, operation(token = token)) }
        assertTrue(serial.requiresProcessRecovery())
    }

    @Test fun recoveryProbeExceptionFailsClosedAndRemainsLatchedWhenProbeRecovers() {
        val failProbe = AtomicBoolean(true); val calls = AtomicInteger()
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery(): Boolean {
                if (failProbe.get()) throw IllegalStateException("Probe unavailable")
                return false
            }
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence { calls.incrementAndGet(); return evidence }
        }, EpochClock { 0 })
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial) }
        failProbe.set(false)
        assertTrue(serial.requiresProcessRecovery())
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial, operation("next")) }
        assertEquals(0, calls.get())
    }

    @Test fun recoveryProbeExceptionAfterDelegateFencesLateSuccessfulEvidence() {
        val failProbe = AtomicBoolean(false); val calls = AtomicInteger()
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery(): Boolean {
                if (failProbe.get()) throw IllegalStateException("Probe unavailable")
                return false
            }
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence {
                calls.incrementAndGet(); failProbe.set(true); return evidence
            }
        }, EpochClock { 0 })
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial) }
        failProbe.set(false)
        assertTrue(serial.requiresProcessRecovery())
        assertFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { verify(serial, operation("next")) }
        assertEquals(1, calls.get())
    }

    @Test fun probeExceptionStillObservesOriginalCancellationBeforeDelegateEntry() {
        val token = CancellationSource().also { it.cancel() }
        val serial = SerialArtifactVerifier(object : ArtifactVerifier {
            override fun requiresProcessRecovery(): Boolean = throw IllegalStateException("Probe unavailable")
            override fun verify(artifact: StagingArtifact, transfer: TransferResult,
                context: OperationContext): VerificationEvidence = error("Stopped caller entered delegate")
        }, EpochClock { 0 })
        assertFailure(SaveError.CANCELLED) { verify(serial, operation(token = token)) }
        assertTrue(serial.requiresProcessRecovery())
    }

    private class ObservedToken : CancellationToken {
        val checked = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        override val isCancelled: Boolean get() { checked.countDown(); return cancelled.get() }
    }
    private class BlockingVerifier(clock: EpochClock = EpochClock { 0 }) : ArtifactVerifier {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val calls = AtomicInteger(); val poison = AtomicBoolean(false)
        val serial = SerialArtifactVerifier(this, clock)
        override fun requiresProcessRecovery() = poison.get()
        override fun verify(artifact: StagingArtifact, transfer: TransferResult,
            context: OperationContext): VerificationEvidence {
            calls.incrementAndGet(); entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); return evidence
        }
        fun awaitEntered() { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
    }
    private class Worker(block: () -> VerificationEvidence) {
        private val result = AtomicReference<Any?>()
        private val done = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val worker = thread(isDaemon = true) {
            try { result.set(block()) }
            catch (failure: Throwable) { result.set(failure) }
            finally { interrupted.set(Thread.currentThread().isInterrupted); done.countDown() }
        }
        private fun result(): Any? {
            assertTrue("Verifier worker did not finish", done.await(3, TimeUnit.SECONDS))
            worker.join(3000); assertFalse(worker.isAlive); return result.get()
        }
        fun success(): VerificationEvidence {
            val value = result()
            if (value is Throwable) throw AssertionError("Verifier worker failed", value)
            return value as VerificationEvidence
        }
        fun failure(expected: SaveError) {
            val value = result()
            assertTrue("Expected SaveFailure, got $value", value is SaveFailure)
            assertEquals(expected, (value as SaveFailure).code)
        }
    }
    private companion object {
        val evidence = VerificationEvidence("artifact", 1, "a".repeat(64), true, 1, 0,
            1000, 16, 16, false, DecodePosition.entries.toSet(), true)
        fun operation(id: String = "operation", deadline: Long = 1000,
            token: CancellationToken = CancellationSource()) = OperationContext(id, deadline, token)
        fun verify(verifier: ArtifactVerifier, context: OperationContext = operation()) =
            verifier.verify(StagingArtifact("artifact", "commit"), TransferResult("artifact", 1, "video/mp4"), context)
        fun assertFailure(expected: SaveError, block: () -> Unit) {
            try { block(); fail("Expected failure") }
            catch (failure: SaveFailure) { assertEquals(expected, failure.code) }
        }
    }
}
