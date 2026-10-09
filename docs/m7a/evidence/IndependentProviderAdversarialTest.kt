package com.easysavevideo.commons.maintenance

import com.easysavevideo.commons.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.ResourceBudget
import com.easysavevideo.discovery.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Independent reviewer scenarios. Keys are ephemeral fixtures, never application trust. */
class IndependentProviderAdversarialTest {
    private val now = ProviderPackageFixtures.NOW
    private fun temp() = Files.createTempDirectory("esv-independent-provider-").toFile()
    private fun bundle() = ProviderPolicy(0, 0, Long.MAX_VALUE, true, CommonsLimits())
    private fun success(value: ProviderRepositoryResult) {
        assertTrue("Expected repository success, got $value", value is ProviderRepositoryResult.Success)
    }
    private fun operation(deadline: Long, cancellation: DiscoveryCancellation = DiscoveryCancellation()) =
        DiscoveryOperation(OperationContext("independent", deadline, cancellation, resourceBudget = ResourceBudget(1)), cancellation)
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) { }
    }

    @Test fun everyEnvelopeTruncationAndUnsignedByteMutationRejects() {
        val bytes = ProviderPackageFixtures.signed()
        val verifier = ProviderPackageFixtures.verifier()
        assertTrue(verifier.verify(bytes, now) is ProviderPackageResult.Verified)
        for (length in 0 until bytes.size) assertTrue("truncation $length", verifier.verify(bytes.copyOf(length), now) is ProviderPackageResult.Rejected)
        for (index in bytes.indices) {
            val changed = bytes.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
            assertTrue("mutation $index", verifier.verify(changed, now) is ProviderPackageResult.Rejected)
        }
    }

    @Test fun validSignatureCannotGrantUnknownAuthorityOrWiderBudget() {
        val verifier = ProviderPackageFixtures.verifier()
        val payloads = listOf(
            ProviderPackageFixtures.payload(schema = 2),
            ProviderPackageFixtures.payload(providerId = "other-provider"),
            ProviderPackageFixtures.payload(providerVersion = "0.2.0"),
            ProviderPackageFixtures.payload(pageSize = 6),
            ProviderPackageFixtures.payload(deadline = 15_001),
            ProviderPackageFixtures.payload(responseBytes = 2L * 1024 * 1024 + 1),
            ProviderPackageFixtures.payload(cacheEntries = 17),
            ProviderPackageFixtures.payload(cacheTtl = 300_001),
            ProviderPackageFixtures.payload(enabledByte = 2),
            ProviderPackageFixtures.payload() + byteArrayOf(0),
        )
        payloads.forEach { assertTrue(verifier.verify(ProviderPackageFixtures.envelope(it), now) is ProviderPackageResult.Rejected) }
        assertEquals(ProviderPackageError.UNTRUSTED_KEY,
            (ProviderPackageVerifier().verify(ProviderPackageFixtures.signed(), now) as ProviderPackageResult.Rejected).code)
    }

    @Test fun rollbackRejectRestartAndExpiredHighWaterNeverAdmitReplay() {
        val directory = temp(); var time = now
        val verifier = ProviderPackageFixtures.verifier()
        ProviderRepository(directory, verifier, EpochClock { time }).use { repository ->
            success(repository.install(ProviderPackageFixtures.signed(version = 10)))
            success(repository.confirmHealthy(10))
            success(repository.install(ProviderPackageFixtures.signed(version = 20)))
            success(repository.rejectCandidate(20))
            assertEquals(20L, repository.snapshot().highestAcceptedVersion)
            success(repository.rollback())
            assertEquals(0L, repository.snapshot().policy.packageVersion)
        }
        time += 120_000
        ProviderRepository(directory, verifier, EpochClock { time }).use { repository ->
            assertEquals(20L, repository.snapshot().highestAcceptedVersion)
            assertEquals(ProviderRepositoryState.BUNDLED, repository.snapshot().state)
            val freshReplay = ProviderPackageFixtures.signed(version = 20, issued = time - 1, expires = time + 1000)
            assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.REPLAYED_VERSION), repository.install(freshReplay))
            success(repository.install(ProviderPackageFixtures.signed(version = 21, issued = time - 1, expires = time + 1000)))
        }
    }

    @Test fun corruptionAndMissingStateNeverResetToFreshBundledRepository() {
        for (damage in 0..1) {
            val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
            ProviderRepository(directory, verifier, EpochClock { now }).use { success(it.install(ProviderPackageFixtures.signed(version = 9))) }
            val state = directory.resolve("provider-state.bin")
            if (damage == 0) state.writeBytes(byteArrayOf(1)) else check(state.delete())
            ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
                assertFalse(repository.snapshot().policy.enabled)
                assertTrue(repository.install(ProviderPackageFixtures.signed(version = 10)) is ProviderRepositoryResult.Failure)
            }
        }
    }

    @Test fun restartedPendingCandidateRequiresExplicitRejectionAndKeepsHighWater() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
        ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
            success(repository.install(ProviderPackageFixtures.signed(version = 7)))
            assertTrue(repository.snapshot().policy.enabled)
        }
        ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
            val restored = repository.snapshot()
            assertFalse(restored.policy.enabled)
            assertEquals(ProviderRepositoryError.RECOVERY_REQUIRED, restored.error)
            assertEquals(7L, restored.highestAcceptedVersion)
            assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.RECOVERY_REQUIRED), repository.confirmHealthy(7))
            assertTrue(repository.install(ProviderPackageFixtures.signed(version = 8)) is ProviderRepositoryResult.Failure)
            success(repository.rejectCandidate(7))
            assertTrue(repository.snapshot().policy.enabled)
            assertEquals(0L, repository.snapshot().policy.packageVersion)
            assertEquals(7L, repository.snapshot().highestAcceptedVersion)
            assertEquals(ProviderRepositoryResult.Failure(ProviderRepositoryError.REPLAYED_VERSION), repository.install(ProviderPackageFixtures.signed(version = 7)))
            success(repository.install(ProviderPackageFixtures.signed(version = 8)))
        }
    }

    @Test fun cancellationAfterAtomicMoveCannotActivateNowOrAfterRestart() {
        for (target in ProviderWriteStage.entries) {
            val directory = temp(); val verifier = ProviderPackageFixtures.verifier(); var cancelled = false
            ProviderRepository(directory, verifier, EpochClock { now }, bundle(), { stage ->
                if (stage == target) cancelled = true
            }).use { repository ->
                val outcome = repository.install(ProviderPackageFixtures.signed()) { check(!cancelled) }
                assertTrue("Late cancellation at $target must reject", outcome is ProviderRepositoryResult.Failure)
                assertFalse(repository.snapshot().policy.enabled)
            }
            ProviderRepository(directory, verifier, EpochClock { now }).use { assertFalse(it.snapshot().policy.enabled) }
        }
    }

    @Test fun faultsAtEveryDurabilityBoundaryRemainFailClosedOnRestart() {
        for (target in ProviderWriteStage.entries) {
            val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
            ProviderRepository(directory, verifier, EpochClock { now }, bundle(), { stage ->
                if (stage == target) error("fixture")
            }).use { repository ->
                assertTrue(repository.install(ProviderPackageFixtures.signed()) is ProviderRepositoryResult.Failure)
                assertFalse(repository.snapshot().policy.enabled)
            }
            ProviderRepository(directory, verifier, EpochClock { now }).use { assertFalse("restart at $target", it.snapshot().policy.enabled) }
        }
    }

    @Test fun effectiveDeadlineStillAppliesAfterResponseCloseAndBeforeHealthPromotion() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier(); var time = now
        ProviderRepository(directory, verifier, EpochClock { time }).use { repository ->
            success(repository.install(ProviderPackageFixtures.signed(limits = CommonsLimits(deadlineMillis = 10))))
            val transport = CommonsTransport { request -> object : CommonsHttpCall {
                override fun execute() = CommonsHttpResponse(200, request.url,
                    mapOf("Content-Type" to listOf("application/json")),
                    object : ByteArrayInputStream("{\"batchcomplete\":true,\"query\":{\"pages\":[]}}".toByteArray()) {
                        override fun close() { time += 11; super.close() }
                    })
                override fun cancel() {}
            } }
            val timer = object : DiscoveryTimer { override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {} }
            val provider = CommonsMetadataProvider(CommonsClientConfig(true, "fixture", "mailto:fixture@example.invalid"),
                transport, clock = EpochClock { time }, timer = timer, maintenance = RepositoryCommonsMaintenance(repository))
            try {
                provider.search(CommonsSearchRequest("fixture", safeSearchRequired = false), operation(now + 1000))
                fail("Managed deadline expired while closing response")
            } catch (failure: CommonsFailure) { assertEquals(CommonsError.DEADLINE_EXCEEDED, failure.code) }
            assertEquals(1L, repository.snapshot().pendingVersion)
        }
    }

    @Test fun policyDisableFencesBlockedExistingTransportAndPhysicalSlot() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
        ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
            val outcome = AtomicReference<Throwable?>(); val returned = AtomicBoolean(false)
            val transport = CommonsTransport { request -> object : CommonsHttpCall {
                override fun execute(): CommonsHttpResponse {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                    return CommonsHttpResponse(200, request.url, mapOf("Content-Type" to listOf("application/json")),
                        ByteArrayInputStream("{\"batchcomplete\":true,\"query\":{\"pages\":[]}}".toByteArray()))
                }
                override fun cancel() { cancelled.countDown() }
            } }
            val provider = CommonsMetadataProvider(CommonsClientConfig(true, "fixture", "mailto:fixture@example.invalid"),
                transport, clock = EpochClock { now }, maintenance = RepositoryCommonsMaintenance(repository))
            val operation = operation(now + 15_000)
            val thread = Thread {
                try { provider.search(CommonsSearchRequest("fixture", safeSearchRequired = false), operation); returned.set(true) }
                catch (failure: Throwable) { outcome.set(failure) }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                success(repository.install(ProviderPackageFixtures.signed(enabled = false)))
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                assertTrue(operation.cancellation.isCancelled)
            } finally { release.countDown(); thread.join(3000) }
            assertFalse(thread.isAlive); assertFalse(returned.get())
            assertTrue(outcome.get() is CommonsFailure)
        }
    }

    @Test fun explicitRuntimeCancellationMustNotWaitForBlockedRepositoryWrite() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cancellationReturned = CountDownLatch(1); val callbackArrived = CountDownLatch(1)
        val outcome = AtomicReference<ProviderUpdateOutcome?>()
        val bytes = ProviderPackageFixtures.signed()
        val repository = ProviderRepository(directory, verifier, EpochClock { now }, bundle(), { stage ->
            if (stage == ProviderWriteStage.AFTER_DATA_SYNC) {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            }
        })
        val runtime = ProviderUpdateRuntime(repository, ProviderUpdateSource { object : ProviderUpdateCall {
            override fun execute() = ByteArrayInputStream(bytes)
            override fun cancel() {}
        } }, EpochClock { now })
        val handle = runtime.execute(now + 15_000) { outcome.set(it); callbackArrived.countDown() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread { handle.cancel(); cancellationReturned.countDown() }.apply { isDaemon = true; start() }
            assertTrue("Cancel must not wait for a repository fsync", cancellationReturned.await(1, TimeUnit.SECONDS))
            assertTrue("Logical completion must not wait for a repository fsync", callbackArrived.await(1, TimeUnit.SECONDS))
            assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.CANCELLED), outcome.get())
            val retry = AtomicReference<ProviderUpdateOutcome?>()
            runtime.execute(now + 15_000) { retry.set(it) }
            assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.BUSY), retry.get())
        } finally {
            release.countDown(); cancellationReturned.await(2, TimeUnit.SECONDS)
            runtime.close(); repository.close()
        }
        ProviderRepository(directory, verifier, EpochClock { now }).use { assertFalse(it.snapshot().policy.enabled) }
    }

    @Test fun healthPromotionCrossingDeadlineCannotCommitOrReactivateAfterRestart() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier(); var time = now; var advancing = false
        ProviderRepository(directory, verifier, EpochClock { time }, bundle(), { stage ->
            if (advancing && stage == ProviderWriteStage.AFTER_COMMIT_SYNC) time += 11
        }).use { repository ->
            success(repository.install(ProviderPackageFixtures.signed(limits = CommonsLimits(deadlineMillis = 10))))
            advancing = true
            val transport = CommonsTransport { request -> object : CommonsHttpCall {
                override fun execute() = CommonsHttpResponse(200, request.url,
                    mapOf("Content-Type" to listOf("application/json")),
                    ByteArrayInputStream("{\"batchcomplete\":true,\"query\":{\"pages\":[]}}".toByteArray()))
                override fun cancel() {}
            } }
            val timer = object : DiscoveryTimer { override fun schedule(delayMillis: Long, action: () -> Unit) = CancellationHandle {} }
            val provider = CommonsMetadataProvider(CommonsClientConfig(true, "fixture", "mailto:fixture@example.invalid"),
                transport, clock = EpochClock { time }, timer = timer, maintenance = RepositoryCommonsMaintenance(repository))
            try {
                provider.search(CommonsSearchRequest("fixture", safeSearchRequired = false), operation(now + 1000))
                fail("Deadline crossed during health fsync")
            } catch (failure: CommonsFailure) {
                assertTrue(failure.code == CommonsError.DEADLINE_EXCEEDED || failure.code == CommonsError.DISABLED || failure.code == CommonsError.CANCELLED)
            }
            assertFalse(repository.snapshot().policy.enabled)
        }
        ProviderRepository(directory, verifier, EpochClock { time }).use { assertFalse(it.snapshot().policy.enabled) }
    }

    @Test fun hungSourceCreateExecuteAndBodyCloseEachRetainPhysicalSlotAfterDeadline() {
        for (blockedStage in listOf("create", "execute", "close")) {
            val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
            ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
                val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
                val requests = AtomicInteger(); val result = AtomicReference<ProviderUpdateOutcome?>()
                val bytes = ProviderPackageFixtures.signed()
                fun block(stage: String) { if (stage == blockedStage) { entered.countDown(); awaitIgnoringInterrupt(release) } }
                var timeout: (() -> Unit)? = null
                val timer = object : DiscoveryTimer {
                    override fun schedule(delayMillis: Long, action: () -> Unit): CancellationHandle { timeout = action; return CancellationHandle {} }
                }
                val runtime = ProviderUpdateRuntime(repository, ProviderUpdateSource {
                    requests.incrementAndGet(); block("create")
                    object : ProviderUpdateCall {
                        override fun execute(): ByteArrayInputStream {
                            block("execute")
                            return object : ByteArrayInputStream(bytes) { override fun close() { block("close"); super.close() } }
                        }
                        override fun cancel() {}
                    }
                }, EpochClock { now }, timer)
                try {
                    runtime.execute(now + 1000) { result.set(it); completed.countDown() }
                    assertTrue(entered.await(2, TimeUnit.SECONDS)); checkNotNull(timeout).invoke()
                    assertTrue(completed.await(2, TimeUnit.SECONDS))
                    assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.DEADLINE_EXCEEDED), result.get())
                    repeat(10) {
                        val retry = AtomicReference<ProviderUpdateOutcome?>()
                        runtime.execute(now + 1000) { retry.set(it) }
                        assertEquals("physical $blockedStage", ProviderUpdateOutcome.Failure(ProviderUpdateError.BUSY), retry.get())
                    }
                    assertEquals(1, requests.get()); assertEquals(0L, repository.snapshot().highestAcceptedVersion)
                } finally { release.countDown(); runtime.close() }
            }
        }
    }

    @Test fun hungCancellationHookRetainsSlotEvenAfterWorkerAndLogicalResultComplete() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
        ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
            val runtime = ProviderUpdateRuntime(repository, ProviderUpdateSource { object : ProviderUpdateCall {
                override fun execute() = ByteArrayInputStream(byteArrayOf())
                override fun cancel() { entered.countDown(); awaitIgnoringInterrupt(release) }
            } }, EpochClock { now })
            try {
                runtime.execute(now + 1000) { completed.countDown() }
                assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(completed.await(2, TimeUnit.SECONDS))
                repeat(10) {
                    val retry = AtomicReference<ProviderUpdateOutcome?>()
                    runtime.execute(now + 1000) { retry.set(it) }
                    assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.BUSY), retry.get())
                }
            } finally { release.countDown(); runtime.close() }
        }
    }

    @Test fun hungResultCallbackRetainsSlotAndClosePreventsAnyNewSource() {
        val directory = temp(); val verifier = ProviderPackageFixtures.verifier()
        ProviderRepository(directory, verifier, EpochClock { now }).use { repository ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val requests = AtomicInteger()
            val runtime = ProviderUpdateRuntime(repository, ProviderUpdateSource {
                requests.incrementAndGet()
                object : ProviderUpdateCall {
                    override fun execute() = ByteArrayInputStream(byteArrayOf())
                    override fun cancel() {}
                }
            }, EpochClock { now })
            try {
                runtime.execute(now + 1000) { entered.countDown(); awaitIgnoringInterrupt(release) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                repeat(10) {
                    val retry = AtomicReference<ProviderUpdateOutcome?>()
                    runtime.execute(now + 1000) { retry.set(it) }
                    assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.BUSY), retry.get())
                }
                runtime.close()
                val closed = AtomicReference<ProviderUpdateOutcome?>()
                runtime.execute(now + 1000) { closed.set(it) }
                assertEquals(ProviderUpdateOutcome.Failure(ProviderUpdateError.CANCELLED), closed.get())
                assertEquals(1, requests.get())
            } finally { release.countDown(); runtime.close() }
        }
    }
}
