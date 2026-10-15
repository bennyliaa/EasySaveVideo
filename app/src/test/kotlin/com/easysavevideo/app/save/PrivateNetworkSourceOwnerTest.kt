package com.easysavevideo.app.save

import com.easysavevideo.app.browser.privacy.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import com.easysavevideo.core.model.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.easysavevideo.app.save.PrivateNetworkSourceOwner.Retirement

class PrivateNetworkSourceOwnerTest {
    private class Fixture {
        val session = PrivateBrowsingSession(PrivateProfileName.random())
        val lease = PrivateSessionLease(session)
        val claim = Any()
        val seed = PrivateNetworkSourceSeed.create()
        var clears = 0
        var clearFails = false
        var drains = 0
        val owner = PrivateNetworkSourceOwner(lease, seed, claim) {
            clears++; if (clearFails) error("synthetic close uncertainty")
        }
        fun exit() = lease.revokeAndDrain { drains++ }
    }
    @Test fun exitBeforePreparationStopInstallationIsReplayedButNeverAcknowledged() {
        val f = Fixture(); f.exit(); var stops = 0
        f.owner.installPreparationStop { stops++ }
        assertEquals(1, stops); assertEquals(0, f.drains)
        assertFalse(f.owner.startQueued { fail("late source"); true })
        assertTrue(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(1, f.drains)
    }
    @Test fun preparationToTaskHandoffKeepsSameOutstandingRegistration() {
        val f = Fixture(); var preparationStops = 0; var taskStops = 0
        f.owner.installPreparationStop { preparationStops++ }
        assertTrue(f.owner.startQueued { true })
        val binding = f.owner.bindTask("task", "operation") { taskStops++; assertEquals("task", it.taskId) }
        f.exit()
        assertEquals(0, preparationStops); assertEquals(1, taskStops)
        assertFalse(binding.isLive()); assertEquals(0, f.drains)
        assertTrue(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(1, f.drains)
    }
    @Test fun exitBeforeHandoffCannotInstallTaskOrReviveSeed() {
        val f = Fixture(); f.owner.installPreparationStop {}; f.exit()
        assertThrows(SaveFailure::class.java) { f.owner.bindTask("task", "operation") {} }
        assertEquals(0, f.drains)
    }
    @Test fun sourceDetachMatchesCompleteBindingAndOperationBeforeClearingReferences() {
        val f = Fixture(); val exact = f.owner.bindTask("task", "operation") { fail("detached output") }
        val foreign = PrivateNetworkSourceSeed.create().bindTask("task")
        assertFalse(f.owner.sourceDetached("task", "operation", foreign))
        assertFalse(f.owner.sourceDetached("task", "other", exact))
        assertFalse(f.owner.sourceDetached("other", "operation", exact))
        assertEquals(0, f.clears)
        assertTrue(f.owner.sourceDetached("task", "operation", exact)); f.exit()
        assertEquals(1, f.drains); assertEquals(1, f.clears)
        assertTrue(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(1, f.clears)
    }
    @Test fun unrelatedClaimCannotRetireSourceEvenAfterExit() {
        val f = Fixture(); f.owner.installPreparationStop {}; f.exit()
        assertFalse(f.owner.executionRetired(Any(), Retirement.RELEASED_SOURCE)); assertEquals(0, f.drains)
        assertEquals(0, f.clears); assertTrue(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE))
    }
    @Test fun failedReferenceRetirementPermanentlyRetainsDrainObligation() {
        val f = Fixture(); f.clearFails = true; f.exit()
        assertFalse(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(0, f.drains)
        f.clearFails = false
        assertFalse(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(1, f.clears)
        assertEquals(0, f.drains)
    }
    @Test fun partialQueueFailureCannotAcknowledgeRelease() {
        val f = Fixture(); f.owner.installPreparationStop {}
        assertFalse(f.owner.startQueued { error("partial accepted owner") })
        f.exit(); assertEquals(0, f.drains)
        assertTrue(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(1, f.drains)
    }
    @Test fun exitDuringReferenceRetirementCannotReopenTaskOrDuplicateCompletion() {
        val session = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
        val claim = Any(); var drains = 0; var stops = 0; var clears = 0
        val owner = PrivateNetworkSourceOwner(session, PrivateNetworkSourceSeed.create(), claim) {
            clears++; session.revokeAndDrain { drains++ }
        }
        val binding = owner.bindTask("task", "operation") { stops++ }
        assertTrue(owner.sourceDetached("task", "operation", binding))
        assertEquals(1, stops); assertEquals(1, drains); assertEquals(1, clears)
        assertTrue(owner.executionRetired(claim, Retirement.RELEASED_SOURCE)); assertEquals(1, clears)
        assertFalse(owner.startQueued { fail("released source"); true })
    }
    @Test fun dormantSourceKeepsExitRegistrationAndOldClaimCannotRetireResumedOwner() {
        val f = Fixture(); val binding = f.owner.bindTask("task", "operation") {}
        assertTrue(f.owner.startQueued { true })
        assertTrue(f.owner.executionRetired(f.claim, Retirement.LIVE_RESUMABLE_SOURCE))
        assertEquals(0, f.clears)
        val fresh = Any()
        assertTrue(f.owner.resumeQueued(fresh, "resume", {}) { true })
        assertFalse(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE))
        assertFalse(f.owner.sourceDetached("task", "operation", binding))
        f.exit(); assertEquals(0, f.drains)
        assertTrue(f.owner.executionRetired(fresh, Retirement.RELEASED_SOURCE))
        assertEquals(1, f.drains)
    }
    @Test fun dormantExitRetiresOnlyAfterSynchronousCoreSourceRevocation() {
        val f = Fixture(); var stopped = false
        f.owner.bindTask("task", "operation") { stopped = true }
        assertTrue(f.owner.startQueued { true })
        f.owner.executionRetired(f.claim, Retirement.LIVE_RESUMABLE_SOURCE)
        f.exit(); assertTrue(stopped); assertEquals(1, f.drains)
        assertFalse(f.owner.resumeQueued(Any(), "resume", {}) { fail("revoked resume"); true })
    }
    @Test fun partialResumeQueueFailureRetainsNewClaimDrainObligation() {
        val f = Fixture(); f.owner.bindTask("task", "operation") {}
        f.owner.startQueued { true }; f.owner.executionRetired(f.claim, Retirement.LIVE_RESUMABLE_SOURCE)
        val fresh = Any()
        assertFalse(f.owner.resumeQueued(fresh, "resume", {}) { error("partial queued owner") })
        f.exit(); assertEquals(0, f.drains)
        assertFalse(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE))
        assertTrue(f.owner.executionRetired(fresh, Retirement.RELEASED_SOURCE)); assertEquals(1, f.drains)
    }
    @Test fun blockedStopCannotRetireReferencesEarlyAndThrowRetainsRegistration() {
        for (throws in listOf(false, true)) {
            val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.owner.bindTask("task", "operation") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); if (throws) error("stop uncertainty") }
            val exiting = thread { f.exit() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE))
                assertEquals(0, f.clears); assertEquals(0, f.drains)
            } finally { release.countDown(); exiting.join(5000) }
            assertFalse(exiting.isAlive)
            assertEquals(if (throws) 0 else 1, f.clears)
            assertEquals(if (throws) 0 else 1, f.drains)
        }
    }
    @Test fun explicitSourceRetirementUsesDormantReleaseAndActiveClaimStillWaits() {
        for (dormant in listOf(false, true)) {
            val f = Fixture(); var stopped = false
            f.owner.bindTask("task", "operation") { stopped = true }
            assertTrue(f.owner.startQueued { true })
            if (dormant) assertTrue(f.owner.executionRetired(f.claim, Retirement.LIVE_RESUMABLE_SOURCE))
            f.owner.requestSourceRetirement()
            assertTrue(stopped); assertFalse(f.seed.isLive())
            f.exit(); assertEquals(if (dormant) 1 else 0, f.drains)
            if (!dormant) { assertTrue(f.owner.executionRetired(f.claim, Retirement.RELEASED_SOURCE)); assertEquals(1, f.drains) }
            f.owner.requestSourceRetirement(); assertEquals(1, f.clears)
        }
    }
    @Test fun duplicateTaskBindingIsRejectedWithoutChangingOriginalStopOwner() {
        val f = Fixture(); var originalStops = 0
        f.owner.bindTask("task", "operation") { originalStops++ }
        assertThrows(SaveFailure::class.java) { f.owner.bindTask("other", "other") { fail("replacement") } }
        f.exit(); assertEquals(1, originalStops)
    }
    @Test fun actualCoordinatorWaitingClaimRetiresDormantAndExitPreventsResumeWithoutLosingCleanup() {
        val h = CoreHarness()
        assertEquals(TaskState.WAITING_USER, h.run().task.state)
        assertNotNull(h.coordinator.sourceForTask("task")); assertTrue(h.binding.isLive())
        assertFalse(h.admission.owns(h.claim)); assertEquals(0, h.clears)
        h.lease.revokeAndDrain { h.drains++ }
        assertEquals(1, h.drains); assertEquals(1, h.clears)
        assertNull(h.coordinator.sourceForTask("task")); assertFalse(h.binding.isLive())
        val fresh = h.admission.tryAcquireDirect()!!
        assertFalse(h.owner.resumeQueued(fresh, "resume", {}) { fail("source already exited"); true })
        assertNotEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("resume"), fresh)!!.task.state)
        h.admission.release(fresh)
        assertEquals(1, h.transfers)
    }
    @Test fun actualCoordinatorSameSessionResumeUsesFreshClaimAndLateOldRetirementCannotReleaseIt() {
        val h = CoreHarness(); assertEquals(TaskState.WAITING_USER, h.run().task.state)
        val fresh = h.admission.tryAcquireDirect()!!
        assertTrue(h.owner.resumeQueued(fresh, "resume", { h.coordinator.signalPrivateSourceExit("task", it) }) { true })
        assertFalse(h.owner.executionRetired(h.claim, Retirement.RELEASED_SOURCE))
        assertEquals(TaskState.RUNNING, h.coordinator.resume("task", h.operation("resume"), fresh)!!.task.state)
        h.pause = false
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task", fresh)!!.task.state)
        h.retirement.request(fresh)
        h.lease.revokeAndDrain { h.drains++ }
        assertEquals(1, h.drains); assertEquals(1, h.clears); assertEquals(2, h.transfers)
    }
    @Test fun coreDetachedOutputContinuesWhileAcceptedObserverWaitsForExitStopDispatch() {
        val h = CoreHarness(); h.pause = false
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var exiting: Thread? = null
        h.afterStop = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        h.beforeDetachObserver = {
            exiting = thread { h.lease.revokeAndDrain { h.drains++ } }
            check(entered.await(5, TimeUnit.SECONDS))
        }
        try {
            assertEquals(TaskState.SUCCEEDED, h.run().task.state)
            assertEquals(0, h.clears); assertEquals(0, h.drains)
        } finally { release.countDown(); exiting?.join(5000) }
        assertEquals(1, h.clears); assertEquals(1, h.drains)
    }
    /** Real Coordinator/admission/retirement/owner; memory journal and fake media ports. */
    private class CoreHarness {
        val lease = PrivateSessionLease(PrivateBrowsingSession(PrivateProfileName.random()))
        val seed = PrivateNetworkSourceSeed.create()
        val limits = SaveLimits(16384, 4L * 1024 * 1024)
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes)
        val claim = admission.tryAcquireDirect()!!
        var row: SaveRecord? = null
        var pause = true; var transfers = 0; var clears = 0; var drains = 0
        var afterStop: () -> Unit = {}; var beforeDetachObserver: () -> Unit = {}
        lateinit var owner: PrivateNetworkSourceOwner
        lateinit var binding: PrivateNetworkSourceBinding
        val journal = object : SaveJournal {
            override fun read(taskId: String) = row?.takeIf { it.task.taskId == taskId }
            override fun findByIdempotencyKey(key: String) = row?.takeIf { it.request.idempotencyKey == key }
            override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord { check(row == null); row = record; return record }
            override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean {
                if (row?.task?.revision != expectedRevision) return false
                row = next; return true
            }
            override fun listUnfinished(limit: Int) = listOfNotNull(row?.takeUnless { it.task.state.isTerminal }).take(limit)
            override fun listRecent(limit: Int) = listOfNotNull(row).take(limit)
        }
        val coordinator = SaveCoordinator(journal, object : DirectTransferPort {
            override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact, checkpoint: DownloadCheckpoint?,
                context: OperationContext, limits: SaveLimits, progress: TransferProgress): TransferResult {
                transfers++; if (pause) throw SaveFailure(SaveError.WIFI_REQUIRED)
                progress.onBytes(1024); return TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
            }
        }, ArtifactVerifier { artifact, transfer, _ -> VerificationEvidence(artifact.artifactId, transfer.bytesOnDisk,
            "a".repeat(64), true, 1, 0, 1000, 16, 16, false, DecodePosition.entries.toSet(), false) },
            object : PrivateMediaStore {
                private var receipt: MediaCommitReceipt? = null
                override fun prepare(taskId: String, commitId: String, context: OperationContext) = StagingArtifact("artifact", commitId)
                override fun commit(artifact: VerifiedArtifact, context: OperationContext) = MediaCommitReceipt(
                    artifact.artifact.commitId, artifact.artifact.artifactId, "media", artifact.evidence.sizeBytes, artifact.evidence.sha256).also { receipt = it }
                override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? =
                    receipt?.takeIf { it.commitId == commitId }?.let { CommitInspection(it, true, true, true) }
                override fun cleanup(artifact: StagingArtifact, committed: Boolean) = Unit
            }, EpochClock { 0 }, limits, admission = admission,
            privateSourceDetachmentObserver = PrivateSourceDetachmentObserver { task, operation, exact ->
                beforeDetachObserver()
                check(owner.sourceDetached(task, operation, exact))
            })
        val retirement = SaveExecutionRetirement(admission::owns, { false }, admission::release) { exact ->
            val live = coordinator.snapshot("task")?.state == TaskState.WAITING_USER && coordinator.sourceForTask("task") != null
            owner.executionRetired(exact, if (live) Retirement.LIVE_RESUMABLE_SOURCE else Retirement.RELEASED_SOURCE)
        }
        init {
            owner = PrivateNetworkSourceOwner(lease, seed, claim) { clears++; check(coordinator.sourceForTask("task") == null) }
            binding = owner.bindTask("task", "operation") { coordinator.signalPrivateSourceExit("task", it); afterStop() }
        }
        fun operation(id: String) = OperationContext(id, 100000, CancellationSource(), resourceBudget = ResourceBudget(1, limits.maxTotalTransferBytes))
        fun run(): SaveRecord {
            val resource = SelectedMediaResource("candidate", "variant", SourceUrl("https://private.invalid/secret"),
                sourceIdentity = binding.sourceIdentity.encoded, privateSource = binding)
            check(owner.startQueued { true })
            check(coordinator.submit(SaveRequest("task", "task", resource), operation("operation"), claim) is SaveSubmission.Accepted)
            return try { coordinator.execute("task", claim)!! } finally { retirement.request(claim) }
        }
    }

}
