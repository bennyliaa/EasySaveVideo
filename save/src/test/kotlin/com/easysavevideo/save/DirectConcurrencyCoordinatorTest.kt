package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DirectConcurrencyCoordinatorTest {
    private class ConcurrentStore : PrivateMediaStore {
        val receipts = ConcurrentHashMap<String, MediaCommitReceipt>()
        val removedStaging = ConcurrentHashMap.newKeySet<String>()
        @Volatile var beforeInspection: ((String) -> Unit)? = null
        override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact(taskId, commitId)
        override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt =
            MediaCommitReceipt(artifact.artifact.commitId, artifact.artifact.artifactId,
                "media-${artifact.artifact.commitId}", artifact.evidence.sizeBytes, artifact.evidence.sha256)
                .also { receipts[it.commitId] = it }
        override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? {
            beforeInspection?.invoke(commitId)
            return receipts[commitId]?.let { CommitInspection(it, true, true, true) }
        }
        override fun cleanup(artifact: StagingArtifact, committed: Boolean) { if (!committed) removedStaging.add(artifact.artifactId) }
    }
    private class Port(val body: (SelectedMediaResource, StagingArtifact, DownloadCheckpoint?, OperationContext, TransferProgress) -> TransferResult) : DirectTransferPort {
        val calls = AtomicInteger(); val poison = AtomicBoolean()
        override fun requiresProcessRecovery() = poison.get()
        override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
            context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
            calls.incrementAndGet(); return body(resource, artifact, checkpoint, context, progress)
        }
    }
    private class Fixture(first: DirectTransferPort, second: DirectTransferPort, max: Int = 2,
        verify: ArtifactVerifier = ArtifactVerifier { a, r, _ -> goodEvidence(a, r) },
        delay: SaveRetryDelay = SaveRetryDelay { _, _ -> }, externalRecovery: () -> Boolean = { false }) {
        val limits = SaveLimits(2_000_000, 4_000_000)
        val clock = FakeSaveClock()
        val journal = MemorySaveJournal(); val store = ConcurrentStore()
        val pool = FixedDirectTransferPool(first, second)
        val verifier = SerialArtifactVerifier(verify, clock)
        val admission = SaveExecutionAdmission(max, limits.maxArtifactBytes, { 2 * limits.maxArtifactBytes },
            { pool.requiresProcessRecovery() || verifier.requiresProcessRecovery() || externalRecovery() })
        val coordinator = SaveCoordinator(journal, pool, verifier, store, clock, limits,
            delay, admission = admission)
        fun request(id: String) = SaveRequest(id, "key-$id", SelectedMediaResource("candidate", "variant",
            SourceUrl("https://example.invalid/same.mp4"), requestContextRef = RequestContextRef("lease-$id")))
        fun operation(id: String, budget: Long = limits.maxTotalTransferBytes) = OperationContext("operation-$id", 100_000,
            CancellationSource(), 2, resourceBudget = ResourceBudget(1, budget))
        fun submit(id: String, operation: OperationContext = operation(id)): SaveExecutionAdmission.Claim {
            val claim = admission.tryAcquireDirect()!!
            assertTrue(coordinator.submit(request(id), operation, claim) is SaveSubmission.Accepted)
            return claim
        }
    }
    private class Worker(block: () -> Unit) {
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread { try { block() } catch (error: Throwable) { failure.set(error) } }
        fun join() {
            worker.join(5000); assertFalse("Worker did not finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("Worker failed", it) }
        }
    }
    private fun CountDownLatch.awaitChecked() { check(await(5, TimeUnit.SECONDS)) }
    private fun result(a: StagingArtifact, size: Long = 1024) = TransferResult(a.artifactId, size, "video/mp4", size)

    @Test fun twoDistinctPortsReallyOverlapForSameUrlAndReceiptsLeasesAndClaimsStaySeparate() {
        val both = CountDownLatch(2); val release = CountDownLatch(1)
        val leases = ConcurrentHashMap<String, String>(); val active = AtomicInteger(); val maximum = AtomicInteger()
        fun port() = Port { source, artifact, _, context, progress ->
            leases[artifact.artifactId] = source.requestContextRef!!.leaseId
            assertEquals(1, context.resourceBudget.maxConcurrentWork)
            val concurrent = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, concurrent) }
            try { both.countDown(); release.awaitChecked(); progress.onBytes(1024); result(artifact) }
            finally { active.decrementAndGet() }
        }
        val first = port(); val second = port(); val h = Fixture(first, second)
        val a = h.submit("a"); val b = h.submit("b")
        assertNull(h.admission.tryAcquireDirect()); assertNull(h.admission.tryAcquireExclusive())
        val wa = Worker { h.coordinator.execute("a", a) }; val wb = Worker { h.coordinator.execute("b", b) }
        try {
            both.awaitChecked(); assertEquals(2, maximum.get()); assertEquals(1, first.calls.get()); assertEquals(1, second.calls.get())
        } finally { release.countDown() }
        wa.join(); wb.join()
        for ((id, claim) in listOf("a" to a, "b" to b)) {
            val record = h.coordinator.record(id)!!
            assertEquals(TaskState.SUCCEEDED, record.task.state); assertEquals(id, record.receipt!!.commitId)
            assertEquals(id, record.receipt.artifactId); assertEquals("lease-$id", leases[id])
            assertTrue(h.admission.owns(claim)) // Core only removes its executing marker.
        }
        assertTrue(h.admission.release(a)); assertTrue(h.admission.snapshot().busy)
        assertTrue(h.admission.release(b)); assertFalse(h.admission.snapshot().busy)
    }

    @Test fun capOneRejectsSecondBeforeJournalAndDowngradeDoesNotRejectAlreadyAcceptedWorker() {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        fun port() = Port { _, a, _, _, p -> entered.countDown(); release.awaitChecked(); p.onBytes(1024); result(a) }
        val h = Fixture(port(), port(), max = 1)
        val a = h.submit("a"); assertNull(h.admission.tryAcquireDirect()); assertEquals(1, h.journal.records.size)
        h.admission.updateMaxDirectTasks(2); val b = h.submit("b")
        h.admission.updateMaxDirectTasks(1)
        val wa = Worker { h.coordinator.execute("a", a) }; val wb = Worker { h.coordinator.execute("b", b) }
        try { entered.awaitChecked(); assertNull(h.admission.tryAcquireDirect()) } finally { release.countDown() }
        wa.join(); wb.join(); assertEquals(TaskState.SUCCEEDED, h.coordinator.record("b")!!.task.state)
        assertTrue(h.admission.release(a)); assertNull(h.admission.tryAcquireDirect())
        assertTrue(h.admission.release(b)); assertNotNull(h.admission.tryAcquireDirect())
    }

    @Test fun duplicateAndWrongClaimCannotExecuteOrReleaseRunningRuntimeAndExistingDoesNotRebind() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val first = Port { _, a, _, _, p -> entered.countDown(); release.awaitChecked(); p.onBytes(1024); result(a) }
        val second = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val h = Fixture(first, second); val a = h.submit("a"); val spare = h.admission.tryAcquireDirect()!!
        val duplicate = h.coordinator.submit(h.request("a").copy(requestId = "duplicate"), h.operation("duplicate"), spare)
        assertTrue(duplicate is SaveSubmission.Accepted && duplicate.existing)
        val worker = Worker { h.coordinator.execute("a", a) }
        try {
            entered.awaitChecked()
            assertEquals(TaskState.RUNNING, h.coordinator.execute("a", a)!!.task.state)
            assertEquals(TaskState.RUNNING, h.coordinator.execute("a", spare)!!.task.state)
            assertFalse(h.admission.release(a)); assertTrue(h.admission.release(spare))
            assertEquals(1, first.calls.get()); assertEquals(0, second.calls.get())
        } finally { release.countDown() }
        worker.join(); assertTrue(h.admission.release(a))
        val replacement = h.submit("b"); assertFalse(h.admission.release(a)); assertTrue(h.admission.owns(replacement))
        assertEquals(TaskState.QUEUED, h.coordinator.execute("b", a)!!.task.state)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("b", replacement)!!.task.state)
    }

    @Test fun cancellationAndCheckpointsArePerTaskWhileOtherTaskPublishesItsOwnReceipt() {
        val both = CountDownLatch(2); val releaseA = CountDownLatch(1); val releaseB = CountDownLatch(1)
        fun port() = Port { source, a, _, _, p ->
            p.onBytes(if (a.artifactId == "a") 256 else 512)
            p.onCheckpoint(DownloadCheckpoint(a.artifactId, source.sourceIdentity, if (a.artifactId == "a") 256 else 512, "\"${a.artifactId}\"", 1024))
            both.countDown(); (if (a.artifactId == "a") releaseA else releaseB).awaitChecked()
            result(a)
        }
        val h = Fixture(port(), port()); val a = h.submit("a"); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; val wb = Worker { h.coordinator.execute("b", b) }
        try {
            both.awaitChecked(); h.coordinator.cancel("a")
            assertEquals(256L, h.coordinator.record("a")!!.checkpoint!!.offset)
            assertEquals(512L, h.coordinator.record("b")!!.checkpoint!!.offset)
            releaseA.countDown(); wa.join()
            assertEquals(TaskState.CANCELLED, h.coordinator.record("a")!!.task.state)
            assertEquals(TaskState.RUNNING, h.coordinator.record("b")!!.task.state)
            assertTrue(h.admission.release(a)); assertNull(h.admission.tryAcquireExclusive())
        } finally { releaseA.countDown(); releaseB.countDown() }
        wb.join(); assertEquals(TaskState.SUCCEEDED, h.coordinator.record("b")!!.task.state)
        assertEquals(setOf("b"), h.store.receipts.keys); assertEquals("b", h.coordinator.record("b")!!.receipt!!.commitId)
    }

    @Test fun retryCopiesContextButKeepsSamePortOwnerAndDoesNotResetOtherTaskCheckpointOrBudget() {
        val aFirst = CountDownLatch(1); val bEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val aContexts = mutableListOf<OperationContext>()
        val first = Port { source, a, checkpoint, context, p ->
            assertEquals("a", a.artifactId); aContexts.add(context)
            if (context.attempt == 0) {
                p.onBytes(256); p.onCheckpoint(DownloadCheckpoint(a.artifactId, source.sourceIdentity, 256, "\"a\"", 1024))
                aFirst.countDown(); bEntered.awaitChecked(); throw SaveFailure(SaveError.NETWORK_TIMEOUT)
            }
            assertEquals(256L, checkpoint!!.offset); p.onRestart(); p.onBytes(1024); release.awaitChecked(); result(a)
        }
        val second = Port { source, a, _, _, p ->
            assertEquals("b", a.artifactId); p.onBytes(1024)
            p.onCheckpoint(DownloadCheckpoint(a.artifactId, source.sourceIdentity, 1024, "\"b\"", 1024))
            bEntered.countDown(); release.awaitChecked(); result(a)
        }
        val h = Fixture(first, second); val a = h.submit("a"); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; aFirst.awaitChecked()
        val wb = Worker { h.coordinator.execute("b", b) }
        try { bEntered.awaitChecked() } finally { release.countDown() }
        wa.join(); wb.join()
        assertEquals(2, first.calls.get()); assertEquals(1, second.calls.get())
        assertEquals(2, aContexts.size); assertNotSame(aContexts[0], aContexts[1])
        assertEquals(aContexts[0].operationId, aContexts[1].operationId)
        assertEquals(1, h.coordinator.record("a")!!.attempt); assertEquals(0, h.coordinator.record("b")!!.attempt)
        assertNull(h.coordinator.record("a")!!.checkpoint); assertEquals(1024L, h.coordinator.record("b")!!.checkpoint!!.offset)
        assertEquals(1_048_576L, h.coordinator.record("a")!!.bytesTransferred)
        assertEquals(1_048_576L, h.coordinator.record("b")!!.bytesTransferred)
    }

    @Test fun oneTaskExceedingItsBudgetDoesNotConsumeTheOthersAllowance() {
        val both = CountDownLatch(2); val release = CountDownLatch(1)
        fun port() = Port { _, a, _, _, p -> both.countDown(); release.awaitChecked(); p.onBytes(1024); result(a) }
        val h = Fixture(port(), port()); val a = h.submit("a", h.operation("a", 512)); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; val wb = Worker { h.coordinator.execute("b", b) }
        try { both.awaitChecked() } finally { release.countDown() }
        wa.join(); wb.join()
        assertEquals(SaveError.BYTE_LIMIT_EXCEEDED, h.coordinator.record("a")!!.error)
        assertEquals(0L, h.coordinator.record("a")!!.bytesTransferred)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.record("b")!!.task.state)
        assertEquals(1_048_576L, h.coordinator.record("b")!!.bytesTransferred)
    }

    @Test fun poisonedFirstPortGloballyBlocksOtherPublicationAndClaimBatchRelease() {
        val aEntered = CountDownLatch(1); val bEntered = CountDownLatch(1); val releaseA = CountDownLatch(1); val releaseB = CountDownLatch(1)
        lateinit var first: Port
        first = Port { _, a, _, _, p -> aEntered.countDown(); releaseA.awaitChecked(); p.onBytes(1024); first.poison.set(true); result(a) }
        val second = Port { _, a, _, _, p -> bEntered.countDown(); releaseB.awaitChecked(); p.onBytes(1024); result(a) }
        val h = Fixture(first, second); val a = h.submit("a"); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; aEntered.awaitChecked()
        val wb = Worker { h.coordinator.execute("b", b) }
        try { bEntered.awaitChecked(); releaseA.countDown(); wa.join(); releaseB.countDown(); wb.join() }
        finally { releaseA.countDown(); releaseB.countDown() }
        for (id in listOf("a", "b")) {
            assertEquals(TaskState.WAITING_USER, h.coordinator.record(id)!!.task.state)
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, h.coordinator.record(id)!!.error)
        }
        assertTrue(h.store.receipts.isEmpty()); assertFalse(h.admission.release(a)); assertFalse(h.admission.release(b))
        first.poison.set(false); assertTrue(h.pool.requiresProcessRecovery()); assertNull(h.admission.tryAcquireDirect())
    }

    @Test fun finalizedResumeUsesNoNetworkOwnerAndStaleClaimCannotBindReplacementRuntime() {
        val first = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }; val second = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val verifies = AtomicInteger()
        val h = Fixture(first, second, verify = ArtifactVerifier { a, r, _ ->
            if (verifies.incrementAndGet() == 1) throw SaveFailure(SaveError.STORAGE_FULL)
            goodEvidence(a, r)
        })
        val old = h.submit("a")
        assertEquals(TaskState.WAITING_USER, h.coordinator.execute("a", old)!!.task.state)
        assertTrue(h.admission.release(old))
        val hold1 = Any(); val hold2 = Any()
        assertNotNull(h.pool.acquire(hold1)); assertNotNull(h.pool.acquire(hold2))
        val next = h.admission.tryAcquireDirect()!!; val operation = h.operation("resume")
        assertEquals(TaskState.RUNNING, h.coordinator.resume("a", operation, next)!!.task.state)
        assertEquals(TaskState.RUNNING, h.coordinator.execute("a", old)!!.task.state)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("a", next)!!.task.state)
        assertEquals(1, first.calls.get()); assertEquals(0, second.calls.get())
        assertTrue(h.admission.owns(next)); assertTrue(h.admission.release(next))
    }
    @Test fun legacyExecutionCapOneNeverEntersSecondPortWhileFirstIsBlocked() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val first = Port { _, a, _, _, p -> entered.countDown(); release.awaitChecked(); p.onBytes(1024); result(a) }
        val second = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val h = Fixture(first, second, max = 1)
        assertTrue(h.coordinator.submit(h.request("a"), h.operation("a")) is SaveSubmission.Accepted)
        assertTrue(h.coordinator.submit(h.request("b"), h.operation("b")) is SaveSubmission.Accepted)
        val worker = Worker { h.coordinator.execute("a") }
        try {
            entered.awaitChecked()
            assertEquals(TaskState.QUEUED, h.coordinator.execute("b")!!.task.state)
            assertEquals(1, first.calls.get()); assertEquals(0, second.calls.get())
            assertEquals(1, h.admission.snapshot().directCount)
        } finally { release.countDown() }
        worker.join(); assertFalse(h.admission.snapshot().busy)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("b")!!.task.state)
        assertFalse(h.admission.snapshot().busy)
    }

    @Test fun externalNativePoisonFencesCorePublicationEvenWhenBothTransferPortsAreHealthy() {
        val both = CountDownLatch(2); val release = CountDownLatch(1)
        fun port() = Port { _, a, _, _, p -> both.countDown(); release.awaitChecked(); p.onBytes(1024); result(a) }
        val h = Fixture(port(), port()); val a = h.submit("a"); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; val wb = Worker { h.coordinator.execute("b", b) }
        try {
            both.awaitChecked(); h.admission.markRecoveryRequired()
            assertTrue(h.admission.recoveryRequired)
        } finally { release.countDown() }
        wa.join(); wb.join()
        assertTrue(h.store.receipts.isEmpty())
        for (id in listOf("a", "b")) {
            assertEquals(TaskState.WAITING_USER, h.coordinator.record(id)!!.task.state)
            assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, h.coordinator.record(id)!!.error)
        }
        assertFalse(h.admission.release(a)); assertFalse(h.admission.release(b))
    }

    @Test fun globalPoisonDuringRetryDelayPreventsAnotherTransferThroughCachedOwner() {
        val aEntered = CountDownLatch(1); val releaseA = CountDownLatch(1)
        val retryEntered = CountDownLatch(1); val releaseRetry = CountDownLatch(1)
        val first = Port { _, a, _, _, p -> aEntered.countDown(); releaseA.awaitChecked(); p.onBytes(1024); result(a) }
        val second = Port { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_TIMEOUT) }
        val h = Fixture(first, second, delay = SaveRetryDelay { _, _ -> retryEntered.countDown(); releaseRetry.awaitChecked() })
        val a = h.submit("a"); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; aEntered.awaitChecked()
        val wb = Worker { h.coordinator.execute("b", b) }
        try {
            retryEntered.awaitChecked(); first.poison.set(true)
            releaseA.countDown(); wa.join(); releaseRetry.countDown(); wb.join()
        } finally { releaseA.countDown(); releaseRetry.countDown() }
        assertEquals(1, second.calls.get())
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, h.coordinator.record("b")!!.error)
        assertEquals(TaskState.WAITING_USER, h.coordinator.record("b")!!.task.state)
    }

    @Test fun recoveryCannotRewriteAnAcceptedRuntimeBeforeItsWorkerEnters() {
        val first = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val second = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val h = Fixture(first, second); val claim = h.submit("a")
        assertTrue(h.coordinator.recover(h.operation("recovery")).isEmpty())
        assertEquals(TaskState.QUEUED, h.coordinator.record("a")!!.task.state)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("a", claim)!!.task.state)
        assertTrue(h.admission.release(claim))
        val neverAccepted = h.submit("b")
        assertTrue(h.admission.release(neverAccepted))
        assertEquals(TaskState.WAITING_USER, h.coordinator.recover(h.operation("recovery2")).single().task.state)
    }

    @Test fun legacyTemporarilyDrainingClaimRetiresAfterActualReleaseBeforeExplicitResume() {
        var draining = false; var calls = 0
        val h = SaveHarness(transferBlock = { _, a, _, _, p ->
            calls++
            if (calls == 1) { draining = true; throw SaveFailure(SaveError.CANCELLED) }
            p.onBytes(1024); result(a)
        }, releaseUncertain = { draining })
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        draining = false // Actual release confirmation, not Future completion.
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("explicit-resume"))!!.task.state)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
        assertEquals(2, calls)
    }

    @Test fun cancellationWhileRetryDelayIsUnwindingStillPrioritizesGlobalRecoveryAndPreservesMaterial() {
        val aEntered = CountDownLatch(1); val releaseA = CountDownLatch(1)
        val retryEntered = CountDownLatch(1); val releaseRetry = CountDownLatch(1)
        val first = Port { _, a, _, _, p -> aEntered.countDown(); releaseA.awaitChecked(); p.onBytes(1024); result(a) }
        val second = Port { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_TIMEOUT) }
        val h = Fixture(first, second, delay = SaveRetryDelay { _, context ->
            retryEntered.countDown(); releaseRetry.awaitChecked()
            if (context.cancelToken.isCancelled) throw SaveFailure(SaveError.CANCELLED)
        })
        val a = h.submit("a"); val b = h.submit("b")
        val wa = Worker { h.coordinator.execute("a", a) }; aEntered.awaitChecked()
        val wb = Worker { h.coordinator.execute("b", b) }
        try {
            retryEntered.awaitChecked(); first.poison.set(true); h.coordinator.cancel("b")
            releaseRetry.countDown(); wb.join(); releaseA.countDown(); wa.join()
        } finally { releaseA.countDown(); releaseRetry.countDown() }
        assertEquals(TaskState.WAITING_USER, h.coordinator.record("b")!!.task.state)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, h.coordinator.record("b")!!.error)
        assertFalse(h.store.removedStaging.contains("b"))
    }

    @Test fun legacyRetryAfterBookkeepingReadFailureReacquiresRatherThanReusingReleasedPoolSlot() {
        val failRead = AtomicBoolean()
        val first = Port { _, _, _, _, _ -> failRead.set(true); throw SaveFailure(SaveError.NETWORK_TIMEOUT) }
        val second = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val h = Fixture(first, second)
        h.journal.beforeRead = { if (failRead.compareAndSet(true, false)) throw IllegalStateException() }
        assertTrue(h.coordinator.submit(h.request("a"), h.operation("a")) is SaveSubmission.Accepted)
        assertThrows(IllegalStateException::class.java) { h.coordinator.execute("a") }
        assertEquals(TaskState.RUNNING, h.coordinator.record("a")!!.task.state)
        assertFalse(h.admission.snapshot().busy)
        val unrelatedOwner = Any()
        assertSame(first, h.pool.acquire(unrelatedOwner))
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("a")!!.task.state)
        assertEquals(1, first.calls.get()); assertEquals(1, second.calls.get())
        assertTrue(h.pool.release(unrelatedOwner))
    }

    @Test fun liveExternalRecoveryCallbackFencesPublicationAfterCommitThenAllowsExplicitRecoveryAfterRelease() {
        val inspecting = CountDownLatch(1); val releaseInspection = CountDownLatch(1)
        val external = AtomicBoolean()
        fun port() = Port { _, a, _, _, p -> p.onBytes(1024); result(a) }
        val h = Fixture(port(), port(), externalRecovery = { external.get() })
        h.store.beforeInspection = { id -> if (id == "b") { inspecting.countDown(); releaseInspection.awaitChecked() } }
        val claim = h.submit("b")
        val worker = Worker { h.coordinator.execute("b", claim) }
        try {
            inspecting.awaitChecked(); external.set(true)
            assertTrue(h.admission.recoveryRequired)
        } finally { releaseInspection.countDown() }
        worker.join()
        assertEquals(TaskState.WAITING_USER, h.coordinator.record("b")!!.task.state)
        assertEquals(SaveError.RESOURCE_RELEASE_UNCONFIRMED, h.coordinator.record("b")!!.error)
        assertNull(h.coordinator.record("b")!!.receipt) // The provisional store receipt was never published.
        assertFalse(h.admission.release(claim))
        external.set(false) // The supplying native owner confirms actual release.
        assertFalse(h.admission.recoveryRequired); assertTrue(h.admission.release(claim))
        val resumed = h.admission.tryAcquireDirect()!!
        assertEquals(TaskState.RUNNING, h.coordinator.resume("b", h.operation("explicit-resume"), resumed)!!.task.state)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("b", resumed)!!.task.state)
        assertTrue(h.admission.release(resumed))
    }

}
