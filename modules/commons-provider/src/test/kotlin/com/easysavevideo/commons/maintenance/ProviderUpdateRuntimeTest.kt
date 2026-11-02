package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CancellationHandle
import com.easysavevideo.discovery.DiscoveryTimer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ProviderUpdateRuntimeTest {
    private val clock = EpochClock { ProviderPackageFixtures.NOW }
    private val deadline = ProviderPackageFixtures.NOW + 10_000
    private class Timer : DiscoveryTimer {
        val action = AtomicReference<(() -> Unit)?>()
        override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle {
            this.action.set(action); return CancellationHandle { this.action.set(null) }
        }
        fun fire() { action.getAndSet(null)?.invoke() }
    }
    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("esv-provider-update-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun repo(dir: File) = ProviderRepository(dir, ProviderPackageFixtures.verifier(), clock)
    private fun source(body: () -> InputStream) = ProviderUpdateSource { object : ProviderUpdateCall {
        override fun execute() = body()
        override fun cancel() {}
    } }
    private fun execute(runtime: ProviderUpdateRuntime): ProviderUpdateOutcome {
        val done = CountDownLatch(1); val outcome = AtomicReference<ProviderUpdateOutcome>()
        runtime.execute(deadline) { outcome.set(it); done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS)); return outcome.get()
    }
    private fun error(expected: ProviderUpdateError, outcome: ProviderUpdateOutcome) =
        assertEquals(expected, (outcome as ProviderUpdateOutcome.Failure).code)
    private fun waitUninterruptibly(latch: CountDownLatch) {
        while (latch.count != 0L) try { latch.await() } catch (_: InterruptedException) { }
    }
    private fun assertBusy(runtime: ProviderUpdateRuntime) {
        val outcome = AtomicReference<ProviderUpdateOutcome>()
        runtime.execute(deadline) { outcome.set(it) }
        error(ProviderUpdateError.BUSY, outcome.get())
    }

    @Test fun successfulSignedUpdateUsesOneSourceAndCallbackRunsOffCaller() = temp { dir ->
        repo(dir).use { repository ->
            val calls = AtomicInteger(); val cancellation = CountDownLatch(1)
            val source = ProviderUpdateSource {
                calls.incrementAndGet()
                object : ProviderUpdateCall {
                    override fun execute() = ByteArrayInputStream(ProviderPackageFixtures.signed())
                    override fun cancel() { cancellation.countDown() }
                }
            }
            ProviderUpdateRuntime(repository, source, clock, Timer()).use { runtime ->
                val caller = Thread.currentThread(); val callbackThread = AtomicReference<Thread>()
                val outcome = AtomicReference<ProviderUpdateOutcome>(); val done = CountDownLatch(1); val count = AtomicInteger()
                val handle = runtime.execute(deadline) {
                    callbackThread.set(Thread.currentThread()); outcome.set(it); count.incrementAndGet(); done.countDown()
                }
                assertTrue(done.await(3, TimeUnit.SECONDS)); assertTrue(cancellation.await(3, TimeUnit.SECONDS))
                assertNotEquals(caller, callbackThread.get())
                assertEquals(1, calls.get())
                assertTrue((outcome.get() as ProviderUpdateOutcome.Completed).result is ProviderRepositoryResult.Success)
                assertEquals(1L, repository.snapshot().pendingVersion)
                handle.cancel()
                assertEquals(1, count.get())
            }
        }
    }

    @Test fun productionRuntimeHasNoSourceAndProductionVerifierHasNoTrustRoots() = temp { dir ->
        ProviderRepository(dir).use { repository ->
            ProviderUpdateRuntime(repository, clock = clock, timer = Timer()).use { runtime ->
                error(ProviderUpdateError.SOURCE_UNAVAILABLE, execute(runtime))
            }
            ProviderUpdateRuntime(repository, source { ByteArrayInputStream(ProviderPackageFixtures.signed()) }, clock, Timer()).use { runtime ->
                val outcome = execute(runtime) as ProviderUpdateOutcome.Completed
                assertEquals(ProviderRepositoryError.INVALID_PACKAGE, (outcome.result as ProviderRepositoryResult.Failure).code)
                assertEquals(0L, repository.snapshot().highestAcceptedVersion)
            }
        }
    }

    @Test fun oversizedEmptyZeroProgressAndMalformedResponseAreBounded() {
        val cases = listOf(
            Pair(ProviderUpdateError.RESPONSE_TOO_LARGE, { ByteArrayInputStream(ByteArray(ProviderRepository.MAX_PACKAGE_BYTES + 1)) as InputStream }),
            Pair(ProviderUpdateError.INVALID_RESPONSE, { ByteArrayInputStream(byteArrayOf()) as InputStream }),
            Pair(ProviderUpdateError.INVALID_RESPONSE, { object : InputStream() {
                override fun read(): Int = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            } }),
        )
        cases.forEach { (expected, body) -> temp { dir ->
            repo(dir).use { repository ->
                ProviderUpdateRuntime(repository, source(body), clock, Timer()).use { runtime ->
                    error(expected, execute(runtime))
                    assertEquals(0L, repository.snapshot().highestAcceptedVersion)
                }
            }
        } }
        temp { dir -> repo(dir).use { repository ->
            ProviderUpdateRuntime(repository, source { ByteArrayInputStream(byteArrayOf(1)) }, clock, Timer()).use { runtime ->
                val result = (execute(runtime) as ProviderUpdateOutcome.Completed).result as ProviderRepositoryResult.Failure
                assertEquals(ProviderRepositoryError.INVALID_PACKAGE, result.code)
            }
        } }
    }

    @Test fun deadlineCompletesWhileSourceIgnoresInterruptAndRetainsSlotUntilSourceAndCancelExit() = temp { dir ->
        repo(dir).use { repository ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val cancelEntered = CountDownLatch(1); val cancelRelease = CountDownLatch(1); val cancelDone = CountDownLatch(1)
            val timer = Timer()
            val source = ProviderUpdateSource { object : ProviderUpdateCall {
                override fun execute(): InputStream { entered.countDown(); waitUninterruptibly(release); return ByteArrayInputStream(ProviderPackageFixtures.signed()) }
                override fun cancel() { cancelEntered.countDown(); waitUninterruptibly(cancelRelease); cancelDone.countDown() }
            } }
            ProviderUpdateRuntime(repository, source, clock, timer).use { runtime ->
                val done = CountDownLatch(1); val outcome = AtomicReference<ProviderUpdateOutcome>(); val count = AtomicInteger()
                try {
                    runtime.execute(deadline) { outcome.set(it); count.incrementAndGet(); done.countDown() }
                    assertTrue(entered.await(3, TimeUnit.SECONDS)); timer.fire()
                    assertTrue(done.await(1, TimeUnit.SECONDS)); assertTrue(cancelEntered.await(1, TimeUnit.SECONDS))
                    error(ProviderUpdateError.DEADLINE_EXCEEDED, outcome.get()); assertBusy(runtime)
                    release.countDown()
                    assertBusy(runtime) // The resource-cancellation hook is still physically blocked.
                    assertEquals(0L, repository.snapshot().highestAcceptedVersion)
                    assertEquals(1, count.get())
                } finally { release.countDown(); cancelRelease.countDown(); assertTrue(cancelDone.await(3, TimeUnit.SECONDS)) }
            }
        }
    }

    @Test fun cancelledCreationCannotLaterExecuteOrPersistAndKeepsWorkerSlot() = temp { dir ->
        repo(dir).use { repository ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
            val executions = AtomicInteger(); val creations = AtomicInteger()
            val source = ProviderUpdateSource {
                creations.incrementAndGet(); entered.countDown(); waitUninterruptibly(release)
                object : ProviderUpdateCall {
                    override fun execute(): InputStream { executions.incrementAndGet(); return ByteArrayInputStream(ProviderPackageFixtures.signed()) }
                    override fun cancel() { cancelled.countDown() }
                }
            }
            ProviderUpdateRuntime(repository, source, clock, Timer()).use { runtime ->
                val done = CountDownLatch(1); val outcome = AtomicReference<ProviderUpdateOutcome>()
                try {
                    val handle = runtime.execute(deadline) { outcome.set(it); done.countDown() }
                    assertTrue(entered.await(3, TimeUnit.SECONDS)); handle.cancel()
                    assertTrue(done.await(1, TimeUnit.SECONDS)); error(ProviderUpdateError.CANCELLED, outcome.get())
                    repeat(5) { assertBusy(runtime) }
                    assertEquals(1, creations.get()); assertEquals(0, executions.get())
                    release.countDown(); assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                    assertEquals(0, executions.get()); assertEquals(0L, repository.snapshot().highestAcceptedVersion)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun hungCancellationAndHungResultCallbackEachHoldPhysicalSlotAfterCompletedWork() = temp { dir ->
        repo(dir).use { repository ->
            val cancelStarted = CountDownLatch(1); val cancelRelease = CountDownLatch(1)
            val callbackStarted = CountDownLatch(1); val callbackRelease = CountDownLatch(1)
            val source = ProviderUpdateSource { object : ProviderUpdateCall {
                override fun execute() = ByteArrayInputStream(ProviderPackageFixtures.signed())
                override fun cancel() { cancelStarted.countDown(); waitUninterruptibly(cancelRelease) }
            } }
            ProviderUpdateRuntime(repository, source, clock, Timer()).use { runtime ->
                try {
                    runtime.execute(deadline) { callbackStarted.countDown(); waitUninterruptibly(callbackRelease) }
                    assertTrue(callbackStarted.await(3, TimeUnit.SECONDS)); assertTrue(cancelStarted.await(3, TimeUnit.SECONDS))
                    assertBusy(runtime)
                    cancelRelease.countDown()
                    assertBusy(runtime)
                } finally { cancelRelease.countDown(); callbackRelease.countDown() }
            }
        }
    }

    @Test fun closeCancelsCurrentSourceAndDeliversExactlyOnce() = temp { dir ->
        repo(dir).use { repository ->
            val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
            val source = ProviderUpdateSource { object : ProviderUpdateCall {
                override fun execute(): InputStream { entered.countDown(); cancelled.await(); throw InterruptedException() }
                override fun cancel() { cancelled.countDown() }
            } }
            val runtime = ProviderUpdateRuntime(repository, source, clock, Timer())
            val done = CountDownLatch(1); val outcome = AtomicReference<ProviderUpdateOutcome>(); val count = AtomicInteger()
            runtime.execute(deadline) { outcome.set(it); count.incrementAndGet(); done.countDown() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); runtime.close()
            assertTrue(done.await(1, TimeUnit.SECONDS)); assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            error(ProviderUpdateError.CANCELLED, outcome.get()); assertEquals(1, count.get())
            error(ProviderUpdateError.CANCELLED, execute(runtime))
        }
    }

    @Test fun timerFailureNeverStartsSourceAndDoesNotExposeExceptionDetails() = temp { dir ->
        repo(dir).use { repository ->
            val starts = AtomicInteger()
            val timer = object : DiscoveryTimer {
                override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle =
                    throw IllegalStateException("https://secret.invalid/private-path")
            }
            val source = ProviderUpdateSource { starts.incrementAndGet(); error("must not run") }
            ProviderUpdateRuntime(repository, source, clock, timer).use { runtime ->
                val outcome = execute(runtime)
                error(ProviderUpdateError.SOURCE_FAILURE, outcome)
                assertFalse(outcome.toString().contains("secret")); assertEquals(0, starts.get())
            }
        }
    }

    @Test fun cancellationAfterDurableWriteCompletesWithoutWaitingForStorageAndCannotActivateOnRestart() {
        ProviderWriteStage.entries.forEach { stage -> temp { dir ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val repository = ProviderRepository(dir, ProviderPackageFixtures.verifier(), clock,
                ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits())) {
                if (it == stage) { entered.countDown(); waitUninterruptibly(release) }
            }
            val source = source { ByteArrayInputStream(ProviderPackageFixtures.signed()) }
            val runtime = ProviderUpdateRuntime(repository, source, clock, Timer())
            val done = CountDownLatch(1); val outcome = AtomicReference<ProviderUpdateOutcome>()
            val cancellationReturned = CountDownLatch(1)
            try {
                val handle = runtime.execute(deadline) { outcome.set(it); done.countDown() }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                Thread { handle.cancel(); cancellationReturned.countDown() }.start()
                assertTrue(cancellationReturned.await(1, TimeUnit.SECONDS)); assertTrue(done.await(1, TimeUnit.SECONDS))
                error(ProviderUpdateError.CANCELLED, outcome.get()); assertBusy(runtime)
                // snapshot access never waits for the storage transaction's mutex.
                assertEquals(0L, repository.snapshot().policy.packageVersion)
            } finally {
                release.countDown(); runtime.close(); repository.close()
            }
            repo(dir).use { restored ->
                assertFalse(restored.snapshot().policy.enabled)
                assertTrue(restored.snapshot().error in setOf(ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.RECOVERY_REQUIRED))
            }
        } }
    }
}
