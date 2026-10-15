package com.easysavevideo.app.save.stream

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test

/** Actual immutable HLS selection helper; port source contracts connect it to AndroidHlsStreamPort. */
class PrivateHlsSelectionBindingTest {
    private class Fixture {
        val seed = PrivateNetworkSourceSeed.create()
        val token = CancellationSource()
        val operation = OperationContext("operation", 100000, token, retryBudget = 2,
            resourceBudget = ResourceBudget(1, 1024L * 1024))
        val source = SelectedMediaResource("candidate", "initial", SourceUrl("https://private.invalid/master?synthetic=secret"),
            requestContextRef = RequestContextRef("lease"), sourceIdentity = seed.sourceIdentity.encoded, privateSource = seed)
        val binding = seed.bindTask("task")
        val selected = source.copy(variantId = "parsed-variant-hash", expectedAudio = true, privateSource = binding)
        val attachment = PrivateHlsSelectionBinding(source, operation)
        fun wrapped(attempt: Int = 0) = operation.copy(attempt = attempt, cancelToken = object : CancellationToken {
            override val isCancelled: Boolean get() = token.isCancelled
        })
        fun check(resource: SelectedMediaResource = selected, artifact: StagingArtifact = StagingArtifact("artifact", "task"),
            context: OperationContext = wrapped()) = attachment.requireTransfer(resource, artifact, context, 0)
    }

    @Test fun exactSelectionAcceptsWrappedCoreContextAndBoundedRetryButNoCopiedResource() {
        val f = Fixture(); f.attachment.attach(f.selected)
        assertTrue(f.attachment.matches(f.selected)); f.check(); f.check(context = f.wrapped(1)); f.check(context = f.wrapped(2))
        val copies = listOf(f.selected.copy(), f.selected.copy(expectedAudio = false),
            f.selected.copy(url = SourceUrl("https://other.invalid/segment")), f.selected.copy(candidateId = "other-candidate"),
            f.selected.copy(variantId = "other-variant"), f.selected.copy(requestContextRef = RequestContextRef("other-lease")))
        for (copy in copies) {
            assertFalse(f.attachment.matches(copy))
            assertThrows(SaveFailure::class.java) { f.check(copy) }
        }
    }

    @Test fun foreignBindingTaskOrDuplicateAttachmentCannotReplaceSelectedSource() {
        val f = Fixture()
        val foreign = PrivateNetworkSourceSeed.create().bindTask("task")
        assertThrows(SaveFailure::class.java) { f.attachment.attach(f.selected.copy(
            sourceIdentity = foreign.sourceIdentity.encoded, privateSource = foreign)) }
        assertThrows(SaveFailure::class.java) { f.attachment.attach(f.selected.copy(url = SourceUrl("https://other.invalid/source"))) }
        assertThrows(SaveFailure::class.java) { f.attachment.attach(f.selected.copy(candidateId = "other")) }
        f.attachment.attach(f.selected)
        assertThrows(SaveFailure::class.java) { f.attachment.attach(f.selected) }
        assertThrows(SaveFailure::class.java) { f.check(artifact = StagingArtifact("artifact", "other-task")) }
        f.check()
    }

    @Test fun originalOperationAndSourceRevocationCannotBeReplacedByFreshContext() {
        val f = Fixture(); f.attachment.attach(f.selected)
        for (context in listOf(f.wrapped().copy(operationId = "other"), f.wrapped().copy(deadlineEpochMillis = 200000),
            f.wrapped().copy(retryBudget = 1), f.wrapped().copy(resourceBudget = ResourceBudget(1, 2L * 1024 * 1024))))
            assertThrows(SaveFailure::class.java) { f.check(context = context) }
        f.token.cancel()
        assertThrows(SaveFailure::class.java) { f.check(context = f.operation.copy(cancelToken = CancellationSource())) }
        val other = Fixture(); other.attachment.attach(other.selected); other.seed.revoke()
        assertFalse(other.attachment.matches(other.selected)); assertThrows(SaveFailure::class.java) { other.check() }
    }

    @Test fun actualCoreStreamDispatchKeepsExactResourceAndWrapsOriginalTokenWithoutBreakingSelection() {
        val f = Fixture(); f.attachment.attach(f.selected)
        var row: SaveRecord? = null; var calls = 0
        val journal = object : SaveJournal {
            override fun read(taskId: String) = row?.takeIf { it.task.taskId == taskId }
            override fun findByIdempotencyKey(key: String) = row?.takeIf { it.request.idempotencyKey == key }
            override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord { row = record; return record }
            override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
                if (row?.task?.revision != expectedRevision) return false
                row = next; return true
            }
            override fun listUnfinished(limit: Int) = listOfNotNull(row?.takeUnless { it.task.state.isTerminal }).take(limit)
            override fun listRecent(limit: Int) = listOfNotNull(row).take(limit)
        }
        val stream = object : StreamSavePort {
            override fun availability() = SaveMethodAvailability(true, null)
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                assertSame(f.selected, resource); assertNotSame(f.operation.cancelToken, context.cancelToken)
                assertEquals("task", artifact.commitId)
                f.attachment.requireTransfer(resource, artifact, context, 0); calls++
                // A real Core dispatch reaches this source guard before the intentionally absent IO.
                throw SaveFailure(SaveError.WIFI_REQUIRED)
            }
        }
        val admission = SaveExecutionAdmission(maxArtifactBytes = 4096)
        val claim = admission.tryAcquireExclusive()!!
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult = error("not Direct")
        },
            ArtifactVerifier { _, _, _ -> error("no completed transfer") }, object : PrivateMediaStore {
                override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact("artifact", commitId)
                override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt = error("no output")
                override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? = null
                override fun cleanup(artifact: StagingArtifact, committed: Boolean) = Unit
            }, EpochClock { 0 }, SaveLimits(4096, 1024L * 1024), stream = stream, admission = admission)
        assertTrue(coordinator.submit(SaveRequest("task", "task", f.selected, TaskMethod.STREAM), f.operation, claim) is SaveSubmission.Accepted)
        assertEquals(TaskState.WAITING_USER, coordinator.execute("task", claim)!!.task.state)
        assertEquals(1, calls); assertTrue(admission.release(claim))
    }
}
