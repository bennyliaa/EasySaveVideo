package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real Coordinator and local gate, with restart-persistent fake SQL and typed cleanup IO. */
class ControlledDirectCleanupSweepTest {
    @Test fun restartFindsTerminalResiduesWithoutRuntimeOrExpiredWaitAndUsesOnlyTypedCleanup() {
        val h = Harness()
        val records = listOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED).mapIndexed { i, state ->
            h.seed((i + 1).toLong(), state)
        }
        h.coordinator = h.newCoordinator() // No accepted Runtime or in-memory pending cleanup survives.
        val context = h.context()
        assertTrue(h.coordinator.recover(context).isEmpty())
        h.coordinator.sweepTerminalCleanup(context)
        assertEquals(records, h.cleaned)
        assertEquals(records, records.map { h.journal.read(it.task.taskId) })
        assertEquals(0, h.journal.backing.writes)
        assertEquals(0, h.unexpectedCalls); assertFalse(h.admission.snapshot().busy)
        assertEquals(0, h.store.cleanup.size)
        h.permits.forEach { (permit, bound) ->
            assertSame(context.cancelToken, bound.cancelToken)
            assertTrue(bound.deadlineEpochMillis <= context.deadlineEpochMillis)
            assertThrows(SaveFailure::class.java) { permit.requireLive(bound) }
        }
    }

    @Test fun ineligibleAndUnreadableKeysConsumeOneBoundedPageWithoutStarvingLaterTerminal() {
        val h = Harness()
        repeat(32) { i -> h.entries[(i + 1).toLong()] = if (i % 2 == 0) null else h.seed((i + 1).toLong(), TaskState.RUNNING) }
        val terminal = h.seed(33, TaskState.CANCELLED)
        h.coordinator.sweepTerminalCleanup(h.context())
        assertTrue(h.cleaned.isEmpty()); assertEquals(listOf(0L), h.requestedAfter)
        h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(listOf(0L, 32L), h.requestedAfter); assertEquals(listOf(terminal), h.cleaned)
        assertEquals(listOf(32, 32), h.requestedLimits)
    }

    @Test fun pageTailWrapFindsPreviouslyActiveRowsAndRetriesEarlierConflicts() {
        val h = Harness(); val active = h.seed(1, TaskState.RUNNING); val blocked = h.seed(2, TaskState.FAILED)
        h.onCleanup = { if (it.task.taskId == blocked.task.taskId) throw SaveFailure(SaveError.BUSY) }
        h.coordinator.sweepTerminalCleanup(h.context())
        assertTrue(h.cleaned.isEmpty())
        val terminal = active.copy(task = active.task.copy(revision = 2, state = TaskState.CANCELLED))
        h.journal.backing.records[terminal.task.taskId] = terminal; h.entries[1] = terminal
        h.onCleanup = {}
        h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(listOf(0L, 0L), h.requestedAfter)
        assertEquals(listOf(terminal, blocked), h.cleaned)
        assertEquals(listOf(blocked, terminal, blocked), h.attempted)
    }

    @Test fun wholeSweepDeadlineDoesNotRenewAndLeavesUnstartedKeysForNextTick() {
        val h = Harness(); val first = h.seed(1, TaskState.FAILED); val second = h.seed(2, TaskState.CANCELLED)
        h.onCleanup = { h.clock.now = 100 }
        h.coordinator.sweepTerminalCleanup(h.context(deadline = 100))
        assertEquals(listOf(first), h.cleaned)
        assertEquals(listOf(100L), h.permits.map { it.second.deadlineEpochMillis })
        h.onCleanup = {}
        h.coordinator.sweepTerminalCleanup(h.context(deadline = 200))
        assertEquals(listOf(0L, 1L), h.requestedAfter); assertEquals(listOf(first, second), h.cleaned)
    }

    @Test fun longCallerBudgetIsNarrowedOnceAndNotRenewedBetweenEntries() {
        val h = Harness(); h.seed(1, TaskState.FAILED); h.seed(2, TaskState.CANCELLED)
        h.onCleanup = { h.clock.now += 4_000 }
        h.coordinator.sweepTerminalCleanup(h.context(deadline = 90_000))
        assertEquals(listOf(10_000L, 10_000L), h.permits.map { it.second.deadlineEpochMillis })
    }

    @Test fun cancelledExpiredOrDrainingSweepDoesNotEvenQueryPage() {
        val h = Harness(); h.seed(1, TaskState.SUCCEEDED)
        val cancel = CancellationSource().also { it.cancel() }
        h.coordinator.sweepTerminalCleanup(h.context(token = cancel))
        h.coordinator.sweepTerminalCleanup(h.context(deadline = 0))
        h.draining = true; h.coordinator.sweepTerminalCleanup(h.context())
        assertTrue(h.requestedAfter.isEmpty()); assertTrue(h.attempted.isEmpty())
    }

    @Test fun sqlFailurePreservesCursorAndEmptyExhaustedPageResetsIt() {
        val h = Harness(); h.entries[7] = null
        h.page = { _, _ -> DirectTerminalCleanupPage.Readable(listOf(DirectTerminalCleanupEntry(7, null)), true) }
        h.coordinator.sweepTerminalCleanup(h.context())
        h.page = { _, _ -> DirectTerminalCleanupPage.Unreadable }; h.coordinator.sweepTerminalCleanup(h.context())
        h.page = { _, _ -> throw IllegalStateException() }; h.coordinator.sweepTerminalCleanup(h.context())
        h.page = { _, _ -> DirectTerminalCleanupPage.Readable(emptyList(), false) }; h.coordinator.sweepTerminalCleanup(h.context())
        h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(listOf(0L, 7L, 7L, 7L, 0L), h.requestedAfter)
        assertTrue(h.attempted.isEmpty())
    }

    @Test fun malformedPagesAreRejectedBeforeAnyCleanupAndCannotAdvanceCursor() {
        val badPages = listOf(
            listOf(1L, 1L), listOf(2L, 1L), listOf(0L), listOf(-1L), (1L..33L).toList(), emptyList(),
        )
        for (keys in badPages) {
            val h = Harness(); val terminal = h.seed(1, TaskState.SUCCEEDED)
            h.page = { _, _ -> DirectTerminalCleanupPage.Readable(keys.map { DirectTerminalCleanupEntry(it, terminal) }, true) }
            h.coordinator.sweepTerminalCleanup(h.context())
            assertTrue(h.attempted.isEmpty())
            h.page = { _, _ -> DirectTerminalCleanupPage.Readable(emptyList(), false) }
            h.coordinator.sweepTerminalCleanup(h.context())
            assertEquals(listOf(0L, 0L), h.requestedAfter)
        }
    }

    @Test fun staleSaveSnapshotAndAbsentOrUnreadableLedgerNeverFallBackToGenericCleanup() {
        for (presence in DirectWorkspacePresence.entries) {
            val h = Harness(); val old = h.seed(1, TaskState.SUCCEEDED)
            h.presence = presence
            if (presence == DirectWorkspacePresence.PRESENT)
                h.journal.backing.records[old.task.taskId] = old.copy(task = old.task.copy(revision = 2))
            h.coordinator.sweepTerminalCleanup(h.context())
            assertTrue(h.attempted.isEmpty()); assertTrue(h.store.cleanup.isEmpty())
            assertEquals(0, h.unexpectedCalls)
        }
    }

    @Test fun cleanupExceptionDoesNotStarveLaterKeyAndSuccessRecordIsNeverRewritten() {
        val h = Harness(); val first = h.seed(1, TaskState.SUCCEEDED); val second = h.seed(2, TaskState.CANCELLED)
        h.onCleanup = { if (it == first) throw IllegalStateException() }
        h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(listOf(first, second), h.attempted); assertEquals(listOf(second), h.cleaned)
        assertEquals(first, h.journal.read(first.task.taskId)); assertEquals(0, h.journal.backing.writes)
        h.onCleanup = {}; h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(2, h.attempted.count { it == first })
    }

    @Test fun blockedCleanupKeepsSameGateButMemorySignalsAndOtherJournalReadsStayAvailable() {
        val h = Harness(); h.seed(1, TaskState.SUCCEEDED)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val otherEntered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        h.onCleanup = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val worker = thread { try { h.coordinator.sweepTerminalCleanup(h.context()) } catch (t: Throwable) { failure.set(t) } }
        var contender: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.coordinator.signalUserCancel("task-1"); h.coordinator.signalBackgroundPauseAll()
            assertEquals(TaskState.SUCCEEDED, h.coordinator.record("task-1")!!.task.state)
            h.coordinator.sweepTerminalCleanup(h.context()) // Reentrant maintenance is refused immediately.
            assertEquals(1, h.requestedAfter.size)
            contender = thread { h.gate.withBoundPermit(Any(), h.context()) { otherEntered.countDown() } }
            assertFalse(otherEntered.await(100, TimeUnit.MILLISECONDS))
            assertFalse(h.admission.snapshot().busy)
        } finally { release.countDown() }
        worker.join(5_000); contender?.join(5_000)
        assertFalse(worker.isAlive); assertFalse(contender?.isAlive ?: false); assertNull(failure.get())
        assertEquals(1, h.cleaned.size); assertEquals(0L, otherEntered.count)
    }

    @Test fun cancelledWaitForOccupiedGateCannotRunCleanupOrIssueAFreshDeadline() {
        val h = Harness(); h.seed(1, TaskState.FAILED)
        val occupied = CountDownLatch(1); val release = CountDownLatch(1); val queried = CountDownLatch(1)
        val token = CancellationSource()
        val owner = thread { h.gate.withBoundPermit(Any(), h.context()) { occupied.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        assertTrue(occupied.await(5, TimeUnit.SECONDS))
        h.afterPage = { queried.countDown() }
        val sweep = thread { h.coordinator.sweepTerminalCleanup(h.context(token = token)) }
        try {
            assertTrue(queried.await(5, TimeUnit.SECONDS)); token.cancel()
            sweep.join(5_000); assertFalse(sweep.isAlive); assertTrue(h.attempted.isEmpty())
        } finally { release.countDown() }
        owner.join(5_000); assertFalse(owner.isAlive)
        h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(1, h.cleaned.size)
    }

    @Test fun retainedOrdinaryPendingPrefixCannotStarveALaterGenericResidue() {
        val h = Harness(); h.presence = DirectWorkspacePresence.UNREADABLE
        val order = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val records = (1L..40L).map { h.seed(it, TaskState.RUNNING).also { record -> order[record.task.taskId] = true } }
        val eligible = order.keys.last()
        assertFalse(order.keys.take(32).contains(eligible))
        for (record in records) {
            h.journal.backing.records[record.task.taskId] = record.copy(
                phase = if (record.task.taskId == eligible) SavePhase.CLEANUP else SavePhase.COMMITTING)
            assertEquals(TaskState.CANCELLED, h.coordinator.cancel(record.task.taskId)!!.state)
        }
        assertTrue(h.store.cleanup.isEmpty()) // All first attempts were preserved as unknown.
        h.presence = DirectWorkspacePresence.ABSENT
        repeat(2) { h.coordinator.recover(h.context()) }
        assertEquals(listOf(StagingArtifact(eligible, eligible) to false), h.store.cleanup)
        assertTrue(h.attempted.isEmpty()) // This generic residue has no normalization ledger.
        assertEquals(TaskState.CANCELLED, h.journal.read(eligible)!!.task.state)
    }

    @Test fun freshCoordinatorSweepsHistoricalConvertedAudioWithoutResumingOrGrantingPublication() {
        val h = Harness()
        val records = listOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED).mapIndexed { i, state ->
            h.seedHistoricalConversion((i + 1).toLong(), state)
        }
        assertTrue(records.all { it.directConversion?.isCurrentPolicy() == false })
        h.coordinator = h.newCoordinator()
        assertTrue(h.coordinator.recover(h.context()).isEmpty())
        h.coordinator.sweepTerminalCleanup(h.context())
        assertEquals(records, h.cleaned); assertEquals(records, records.map { h.journal.read(it.task.taskId) })
        assertEquals(0, h.journal.backing.writes); assertEquals(0, h.unexpectedCalls)
        assertTrue(h.store.cleanup.isEmpty()); assertFalse(h.admission.snapshot().busy)
    }

    private class Journal(val backing: MemorySaveJournal = MemorySaveJournal()) : ControlledDirectJournal {
        override fun findByIdempotencyKey(key: String) = backing.findByIdempotencyKey(key)
        override fun read(taskId: String) = backing.read(taskId)
        override fun insert(record: SaveRecord, maxEntries: Int) = backing.insert(record, maxEntries)
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord) = backing.compareAndSet(expectedRevision, next)
        override fun listUnfinished(limit: Int) = backing.listUnfinished(limit)
        override fun listRecent(limit: Int) = backing.listRecent(limit)
    }

    private class Harness : ControlledDirectStorage {
        val journal = Journal(); val clock = FakeSaveClock(); val limits = SaveLimits(16_384, 65_536)
        val gate = DirectLocalProcessingGate(clock); val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes)
        val store = FakeMediaStore(); val entries = sortedMapOf<Long, SaveRecord?>()
        val requestedAfter = mutableListOf<Long>(); val requestedLimits = mutableListOf<Int>()
        val attempted = mutableListOf<SaveRecord>(); val cleaned = mutableListOf<SaveRecord>()
        val permits = mutableListOf<Pair<DirectLocalProcessingGate.LocalPermit, OperationContext>>()
        var unexpectedCalls = 0; var draining = false; var presence = DirectWorkspacePresence.PRESENT
        var onCleanup: (SaveRecord) -> Unit = {}; var afterPage: () -> Unit = {}
        var page: ((Long, Int) -> DirectTerminalCleanupPage)? = null
        var coordinator = newCoordinator()
        private fun forbidden(): Nothing { unexpectedCalls++; error("UNEXPECTED_EXECUTION") }
        fun context(deadline: Long = 2_000, token: CancellationToken = CancellationSource()) = OperationContext("sweep", deadline, token)
        fun newCoordinator() = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult = forbidden()
        }, ArtifactVerifier { _, _, _ -> forbidden() }, store, clock, limits,
            networkGate = SaveNetworkGate { forbidden() }, admission = admission,
            controlledDirect = ControlledDirectPipeline(gate, this)).also {
            it.acceptedAttemptInstallationObserver = { _, _ -> forbidden() }
        }
        fun seed(key: Long, state: TaskState): SaveRecord {
            val id = "task-$key"; val artifact = StagingArtifact(id, id)
            val transfer = TransferResult(id, 1024, "video/mp4", 1024)
            val evidence = if (state == TaskState.SUCCEEDED) goodEvidence(artifact, transfer) else null
            val receipt = evidence?.let { MediaCommitReceipt(id, id, "media-$key", 1024, it.sha256) }
            val request = SaveRequest(id, "key-$key", SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/raw"))).asDurableReference()
            val record = SaveRecord(request, TaskSnapshot(id, 1, state, TaskMethod.DIRECT), id, "old-operation", 1_000, 0,
                phase = SavePhase.CLEANUP, artifact = artifact, transfer = transfer, verification = evidence, receipt = receipt)
            journal.backing.records[id] = record; entries[key] = record; return record
        }
        fun seedHistoricalConversion(key: Long, state: TaskState): SaveRecord {
            val base = seed(key, state); val artifact = base.artifact!!
            val transfer = TransferResult(artifact.artifactId, 1024, "video/webm", 1024)
            val expected = DirectConversionExpectation(DirectConversionVideoExpectation(16, 16, 30, 1_000_000, 30, 0, 966_666, 0),
                DirectConversionAudioExpectation(0, 1_000_000, 48_000, 48_000, 2, 0, 0, 0))
            val proof = DirectConversionProof("00000000-0000-4000-8000-000000000001", base.task.taskId,
                base.request.networkSourceMetadata!!.sourceIdentity, artifact.artifactId, artifact.commitId,
                1024, "b".repeat(64), sourceTransferFingerprint(transfer), 512, "c".repeat(64), expected,
                DirectConversionEncodingSettings.forExpectation(expected),
                DirectConversionCompletionFacts(true, true, true, true, true, true, true, true, true, true, true, true))
            val checked = DirectConversionVerificationProof(proof.normalizationId, proof.taskId, proof.sourceIdentity,
                proof.artifactId, proof.commitId, proof.digest(), 512, proof.outputSha256, 30, 0, 966_666, 1_000_000, 0,
                DirectConversionAudioVerification(0, 1_000_000, 48_000, 48_000, 2, true, true), true, true, true)
            val evidence = if (state == TaskState.SUCCEEDED) VerificationEvidence(artifact.artifactId, 512, proof.outputSha256,
                true, 1, 1, 1000, 16, 16, false, DecodePosition.entries.toSet(), true, directConversionProof = checked) else null
            val record = base.copy(phase = if (state == TaskState.SUCCEEDED) SavePhase.CLEANUP else SavePhase.VERIFYING,
                transfer = transfer, directConversion = proof, verification = evidence,
                receipt = evidence?.let { MediaCommitReceipt(artifact.commitId, artifact.artifactId, "media-$key", 512, it.sha256) })
            journal.backing.records[record.task.taskId] = record; entries[key] = record; return record
        }
        override fun terminalCleanupPage(after: Long, limit: Int): DirectTerminalCleanupPage {
            requestedAfter += after; requestedLimits += limit
            val result = page?.invoke(after, limit) ?: entries.entries.filter { it.key > after }.take(limit + 1).let { rows ->
                DirectTerminalCleanupPage.Readable(rows.take(limit).map { DirectTerminalCleanupEntry(it.key, it.value) }, rows.size > limit)
            }
            afterPage(); return result
        }
        override fun cleanupTerminal(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit,
            invocationContext: OperationContext, ioContext: OperationContext) {
            gate.assertIssuedHere(permit, invocationContext)
            check(invocationContext === ioContext); check(ioContext.stopReason(clock.nowMillis()) == null)
            check(record.task.state.isTerminal); check(journal.read(record.task.taskId) == record)
            permits += permit to invocationContext; attempted += record; onCleanup(record); cleaned += record
        }
        override fun onTransferReleased(execution: AcceptedDirectAttempt): Unit = forbidden()
        override fun inspectBeforeNetwork(taskId: String, artifact: StagingArtifact?, commitId: String) = presence
        override fun requiresProcessRecovery() = draining
        override fun selectRaw(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRawSelection = forbidden()
        override fun normalize(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectProofCompletion = forbidden()
        override fun inspectRecovery(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRecoveryAction = forbidden()
        override fun adopt(record: SaveRecord, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): Unit = forbidden()
        override fun verifyTransferred(record: SaveRecord, output: ArtifactOutputDescriptor.Transferred, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): VerificationEvidence = forbidden()
        override fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion = forbidden()
        override fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedCommitHandle = forbidden()
        override fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle = forbidden()
    }
}
