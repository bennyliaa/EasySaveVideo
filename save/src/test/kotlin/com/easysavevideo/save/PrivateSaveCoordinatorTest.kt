package com.easysavevideo.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PrivateSaveCoordinatorTest {
    @Test fun exactRawReleaseDetachesSourceBeforeLocalWorkAndQueuedExitCannotCancelOutput() {
        val h = Harness()
        h.onDetached = { assertNull(h.coordinator.sourceForTask("task")) }
        h.onVerify = { context ->
            assertTrue(h.coordinator.isPrivateSourceDetached("task", h.binding))
            assertEquals(PrivateSourceExitResult.OUTPUT_ONLY, h.coordinator.signalPrivateSourceExit("task", h.binding))
            assertFalse(context.cancelToken.isCancelled)
            assertTrue(h.transferContext!!.cancelToken.isCancelled) // Source scope no longer usable.
        }
        val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state)
        assertTrue(result.request.input is SaveInput.PrivateNetwork); assertNull(result.request.networkResource)
        assertEquals(h.binding.sourceIdentity.encoded, result.request.networkSourceMetadata!!.sourceIdentity)
        assertEquals(1, h.detachments); assertEquals(1, h.transfers); assertEquals(1, h.verifications)
        assertNull(result.checkpoint)
        assertTrue(h.coordinator.isPrivateSourceDetached("task", h.binding)) // Runtime retired, historical fact remains.
        assertEquals(PrivateSourceExitResult.OUTPUT_ONLY, h.coordinator.signalPrivateSourceExit("task", h.binding))
    }

    @Test fun privateCheckpointIsOnlyInRuntimeAcrossTransientRetryAndNeverInJournal() {
        val h = Harness(); h.retryFirst = true
        val result = h.run()
        assertEquals(TaskState.SUCCEEDED, result.task.state); assertEquals(2, h.transfers)
        assertNull(h.receivedCheckpoints.first()); assertEquals(h.emittedCheckpoint, h.receivedCheckpoints.last())
        assertTrue(h.journal.written.all { it.checkpoint == null })
        assertEquals(h.binding.sourceIdentity.encoded, result.request.networkSourceMetadata!!.sourceIdentity)
        assertThrows(SaveFailure::class.java) { h.progress!!.onCheckpoint(h.emittedCheckpoint!!) }
        assertNull(h.journal.read("task")!!.checkpoint)
    }

    @Test fun sourceExitDuringBlockedTransferIsImmediateAndRetainsClaimUntilActualReturn() {
        val h = Harness(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        h.beforeTransferReturn = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.submit(); val result = AtomicReference<SaveRecord>()
        val worker = thread { result.set(h.coordinator.execute("task")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(PrivateSourceExitResult.STOP_REQUESTED, h.coordinator.signalPrivateSourceExit("task", h.binding))
            assertTrue(h.transferContext!!.cancelToken.isCancelled)
            assertTrue(h.admission.snapshot().busy); assertNull(h.coordinator.sourceForTask("task"))
            assertEquals(0, h.verifications); assertEquals(0, h.detachments)
        } finally { release.countDown() }
        worker.join(5_000); assertFalse(worker.isAlive)
        assertEquals(TaskState.CANCELLED, result.get().task.state); assertEquals(0, h.detachments)
        assertFalse(h.coordinator.isPrivateSourceDetached("task", h.binding))
    }

    @Test fun exitWinningAfterRawCasButBeforeDetachCannotBeOverriddenByRawCompletion() {
        val h = Harness()
        h.journal.afterRaw = { assertEquals(PrivateSourceExitResult.STOP_REQUESTED, h.coordinator.signalPrivateSourceExit("task", h.binding)) }
        val result = h.run()
        assertEquals(TaskState.CANCELLED, result.task.state); assertNotNull(result.transfer)
        assertEquals(0, h.detachments); assertEquals(0, h.verifications)
        assertFalse(h.coordinator.isPrivateSourceDetached("task", h.binding))
    }

    @Test fun writeThenThrowUsesExactRawReadbackButUnknownCasNeverDetaches() {
        val exact = Harness(); exact.journal.throwAfterRaw = true
        assertEquals(TaskState.SUCCEEDED, exact.run().task.state); assertEquals(1, exact.detachments)
        val unknown = Harness(); unknown.journal.throwBeforeRaw = true
        assertEquals(TaskState.WAITING_USER, unknown.run().task.state)
        assertEquals(0, unknown.detachments); assertEquals(0, unknown.verifications)
        assertFalse(unknown.coordinator.isPrivateSourceDetached("task", unknown.binding))
    }

    @Test fun unconfirmedTransferReleaseCannotDetachOrBeginOutput() {
        val h = Harness(); h.beforeTransferReturn = { h.draining = true }
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        assertEquals(0, h.detachments); assertEquals(0, h.verifications)
        assertTrue(h.admission.snapshot().busy)
    }

    @Test fun stoppedRuntimeDropsItsCheckpointBeforeExplicitLiveSessionResume() {
        val h = Harness()
        h.beforeTransferReturn = { throw SaveFailure(SaveError.WIFI_REQUIRED) }
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        assertNull(h.journal.read("task")!!.checkpoint)
        h.beforeTransferReturn = {}
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("resume"))!!.task.state)
        val resumed = h.coordinator.execute("task")!!
        assertEquals("error=${resumed.error}, phase=${resumed.phase}", TaskState.SUCCEEDED, resumed.task.state)
        assertTrue(h.receivedCheckpoints.all { it == null })
    }

    @Test fun coldPartialPrivateRecoveryCannotAcquireANewSourceOrOfferAuthenticationResume() {
        val h = Harness(); h.submit()
        h.coordinator = h.newCoordinator()
        val result = h.coordinator.recover(h.operation()).single()
        assertEquals(TaskState.FAILED, result.task.state); assertEquals(SaveError.SOURCE_RESELECTION_REQUIRED, result.error)
        assertNull(result.waitReason); assertNull(h.coordinator.sourceForTask("task"))
        assertEquals(result, h.coordinator.resume("task", h.operation("resume"), resource = h.source))
        assertEquals(0, h.transfers); assertEquals(0, h.verifications)
    }

    @Test fun coldCompletePrivateRecoveryUsesOnlyOutputMetadataAndNeverRestoresURLAuthority() {
        val h = Harness(); h.onDetached = { throw IllegalStateException() }
        val prior = h.run(); assertEquals(TaskState.WAITING_USER, prior.task.state); assertNotNull(prior.transfer)
        h.onDetached = {}; h.coordinator = h.newCoordinator()
        h.coordinator.recover(h.operation())
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("local-resume"))!!.task.state)
        val result = h.coordinator.execute("task")!!
        assertEquals(TaskState.SUCCEEDED, result.task.state); assertEquals(1, h.transfers)
        assertEquals(prior.request, result.request); assertNull(h.coordinator.sourceForTask("task"))
    }

    @Test fun durableSeedRevokedOrForeignTaskInputsAreRejectedBeforeAnyJournalWrite() {
        val h = Harness()
        val seed = PrivateNetworkSourceSeed.create()
        val unbound = h.source.copy(sourceIdentity = seed.sourceIdentity.encoded, privateSource = seed)
        val foreign = seed.bindTask("other")
        val foreignSource = unbound.copy(privateSource = foreign)
        for (request in listOf(h.request().asDurableReference(), h.request().copy(resource = unbound), h.request().copy(resource = foreignSource)))
            assertTrue(h.coordinator.submit(request, h.operation()) is SaveSubmission.Rejected)
        h.binding.revoke()
        assertTrue(h.coordinator.submit(h.request(), h.operation()) is SaveSubmission.Rejected)
        assertTrue(h.journal.backing.records.isEmpty()); assertEquals(0, h.transfers)
    }

    @Test fun wrongTaskExitDoesNotRevokeTheActualPrivateSource() {
        val h = Harness(); h.submit()
        assertEquals(PrivateSourceExitResult.NOT_OWNED, h.coordinator.signalPrivateSourceExit("other", h.binding))
        assertTrue(h.binding.isLive()); assertNotNull(h.coordinator.sourceForTask("task"))
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
    }

    @Test fun arbitraryMimeParametersAreRejectedBeforePrivateRawPersistence() {
        val h = Harness(); h.mime = "video/mp4; source=private-secret"
        assertNotEquals(TaskState.SUCCEEDED, h.run().task.state)
        assertTrue(h.journal.written.all { it.transfer?.responseMimeType?.contains("private-secret") != true })
        assertEquals(0, h.detachments); assertEquals(0, h.verifications)
    }

    @Test fun exactSourceCellDetachAndExitHaveOnlyOneWinnerAndNeverRestoreCheckpoints() {
        repeat(48) {
            val seed = PrivateNetworkSourceSeed.create(); val binding = seed.bindTask("task")
            val source = SelectedMediaResource("candidate", "variant", SourceUrl("https://example.invalid/x"),
                sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding)
            val cell = PrivateRuntimeSource(binding, source)
            cell.checkpoint(DownloadCheckpoint("task", "a".repeat(64), 1, "\"etag\"", 2))
            val start = CountDownLatch(1); val detached = AtomicReference<Boolean>(); val exited = AtomicReference<Boolean>()
            val a = thread { start.await(); detached.set(cell.detach()) }
            val b = thread { start.await(); exited.set(cell.revokeSource()) }
            start.countDown(); a.join(5_000); b.join(5_000)
            assertFalse(a.isAlive); assertFalse(b.isAlive)
            assertNotEquals(detached.get(), exited.get()); assertEquals(detached.get(), cell.isDetached())
            assertNull(cell.resource()); assertNull(cell.checkpoint())
            assertThrows(SaveFailure::class.java) { cell.checkpoint(DownloadCheckpoint("task", "a".repeat(64), 1, "\"etag\"", 2)) }
        }
    }

    private class Journal(val backing: MemorySaveJournal = MemorySaveJournal()) : SaveJournal by backing {
        val written = mutableListOf<SaveRecord>()
        var afterRaw: () -> Unit = {}; var throwBeforeRaw = false; var throwAfterRaw = false
        override fun insert(record: SaveRecord, maxEntries: Int) = backing.insert(record, maxEntries).also { written += it }
        override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
            val isRaw = backing.read(next.task.taskId)?.transfer == null && next.transfer != null
            if (isRaw && throwBeforeRaw) throw IllegalStateException()
            val saved = backing.compareAndSet(expectedRevision, next)
            if (saved) written += next
            if (saved && isRaw) { afterRaw(); if (throwAfterRaw) throw IllegalStateException() }
            return saved
        }
    }
    private class Harness {
        // Each explicit Resume conservatively charges a new 1 MiB reservation; retain room for both attempts.
        val journal = Journal(); val clock = FakeSaveClock(); val limits = SaveLimits(16_384, 4L * 1024 * 1024)
        val binding = PrivateNetworkSourceSeed.create().bindTask("task")
        val source = SelectedMediaResource("live-candidate", "live-variant", SourceUrl("https://private.invalid/path?secret=value"),
            requestContextRef = RequestContextRef("private-lease"), sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding)
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes)
        val store = FakeMediaStore()
        var transfers = 0; var verifications = 0; var detachments = 0; var retryFirst = false; var draining = false
        var mime = "video/mp4"; var progress: TransferProgress? = null; var transferContext: OperationContext? = null
        var emittedCheckpoint: DownloadCheckpoint? = null
        val receivedCheckpoints = mutableListOf<DownloadCheckpoint?>()
        var beforeTransferReturn: () -> Unit = {}; var onDetached: () -> Unit = {}; var onVerify: (OperationContext) -> Unit = {}
        var coordinator = newCoordinator()
        fun newCoordinator() = SaveCoordinator(journal, object : DirectTransferPort {
            override fun requiresProcessRecovery() = draining
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                transfers++; receivedCheckpoints += checkpoint; this@Harness.progress = progress; transferContext = context
                progress.onBytes(1024)
                emittedCheckpoint = DownloadCheckpoint(artifact.artifactId, "d".repeat(64), 1024, "\"private-server-token\"", 1024)
                progress.onCheckpoint(emittedCheckpoint!!)
                if (retryFirst && transfers == 1) throw SaveFailure(SaveError.NETWORK_TIMEOUT)
                beforeTransferReturn()
                return TransferResult(artifact.artifactId, 1024, mime, 1024)
            }
        }, ArtifactVerifier { artifact, transfer, context -> verifications++; onVerify(context); goodEvidence(artifact, transfer) },
            store, clock, limits, SaveRetryDelay { _, _ -> }, admission = admission,
            privateSourceDetachmentObserver = PrivateSourceDetachmentObserver { taskId, _, actual ->
                check(taskId == "task" && actual === binding); detachments++; onDetached()
            })
        fun request() = SaveRequest("task", "task", source)
        fun operation(id: String = "operation") = OperationContext(id, 10_000, CancellationSource(), 2,
            resourceBudget = ResourceBudget(maxBytes = limits.maxTotalTransferBytes))
        fun submit() { assertTrue(coordinator.submit(request(), operation()) is SaveSubmission.Accepted) }
        fun run(): SaveRecord { submit(); return coordinator.execute("task")!! }
    }
}
