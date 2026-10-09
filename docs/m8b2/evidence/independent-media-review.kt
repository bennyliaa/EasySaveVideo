package com.easysavevideo.app.save

import com.easysavevideo.app.discovery.*
import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Pure JVM injections into production coordinator/association/tracker. No Android/native claim. */
class IndependentMediaReviewTest {
    private class Origin {
        val outcomes = mutableListOf<Pair<String, Boolean>>()
        var abandoned = 0
    }
    private fun links(onComplete: ((Origin, String, Boolean) -> Unit)? = null) =
        MediaTaskActivitySaveLinks<Origin>(bind = { _, _ -> true },
            complete = onComplete ?: { o, id, committed -> o.outcomes += id to committed },
            abandon = { it.abandoned++ })
    private fun await(latch: CountDownLatch) = assertTrue("host injection timed out", latch.await(5, TimeUnit.SECONDS))
    private fun join(worker: Thread) { worker.join(5000); assertFalse("host worker remained active", worker.isAlive) }

    @Test fun provisionalCommitRemainsUnknownUntilFinalAtomicSuccessAndExecutionExit() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = object : FakeMediaStore() {
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                val receipt = super.commit(artifact, context)
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return receipt
            }
        }
        val h = SaveHarness(store = store); h.submit()
        val origin = Origin(); val links = links(); links.accepted("task", origin)
        val worker = thread { h.coordinator.execute("task"); links.afterExecution("task") { h.coordinator.record("task") } }
        try {
            await(entered)
            assertEquals(SavePhase.COMMITTING, h.coordinator.record("task")!!.phase)
            assertEquals(1, store.committed.size)
            assertFalse(mediaTaskHasCommittedSave("task", h.coordinator.record("task")))
            assertTrue(origin.outcomes.isEmpty())
        } finally { release.countDown(); join(worker) }
        assertEquals(listOf("task" to true), origin.outcomes)
        repeat(2) { links.afterExecution("task") { error("one shot") } }
        assertEquals(1, store.commitCalls)
    }

    @Test fun commitInspectionFailureCannotPublishSavedDespiteProvisionalFile() {
        val h = SaveHarness(store = FakeMediaStore().apply { inspected = false })
        h.submit(); val origin = Origin(); val links = links(); links.accepted("task", origin)
        h.coordinator.execute("task"); links.afterExecution("task") { h.coordinator.record("task") }
        assertEquals(listOf("task" to false), origin.outcomes)
        assertEquals(1, h.store.committed.size)
        assertFalse(mediaTaskHasCommittedSave("task", h.coordinator.record("task")))
    }

    @Test fun cancellationDuringProvisionalCommitRejectsLateSuccessfulAdapterReturn() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = object : FakeMediaStore() {
            override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.commit(artifact, context)
            }
        }
        val h = SaveHarness(store = store); h.submit()
        val origin = Origin(); val links = links(); links.accepted("task", origin)
        val worker = thread { h.coordinator.execute("task"); links.afterExecution("task") { h.coordinator.record("task") } }
        try { await(entered); links.cancel("task"); h.coordinator.signalUserCancel("task") }
        finally { release.countDown(); join(worker) }
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("task")!!.state)
        assertTrue(origin.outcomes.isEmpty()); assertEquals(1, origin.abandoned)
    }

    @Test fun queuedAndRunningWithCompleteProvisionalProofDoNotQualify() {
        val h = SaveHarness(); val accepted = h.submit() as SaveSubmission.Accepted
        assertFalse(mediaTaskHasCommittedSave("task", accepted.record))
        val success = h.coordinator.execute("task")!!
        assertTrue(mediaTaskHasCommittedSave("task", success))
        assertFalse(mediaTaskHasCommittedSave("other", success))
        for (state in listOf(TaskState.RUNNING, TaskState.QUEUED, TaskState.CANCELLED, TaskState.FAILED, TaskState.INTERRUPTED))
            assertFalse(mediaTaskHasCommittedSave("task", success.copy(task = success.task.copy(state = state), phase = SavePhase.COMMITTING)))
    }

    @Test fun duplicateIdempotentAcceptanceCannotCaptureAnExistingTask() {
        val h = SaveHarness(); h.submit()
        val original = Origin(); val duplicate = Origin(); val links = links()
        assertTrue(links.accepted("task", original))
        val result = h.coordinator.submit(h.request().copy(requestId = "new-click"), h.operation("other")) as SaveSubmission.Accepted
        assertTrue(result.existing)
        assertFalse(links.accepted(result.record.task.taskId, duplicate, result.existing))
        h.coordinator.execute("task"); links.afterExecution("task") { h.coordinator.record("task") }
        assertEquals(listOf("task" to true), original.outcomes)
        assertTrue(duplicate.outcomes.isEmpty()); assertEquals(1, duplicate.abandoned)
        assertEquals(1, h.store.commitCalls)
    }

    @Test fun explicitResumeAfterInterruptedTransferDoesNotRecreateOrigin() {
        var attempts = 0
        val h = SaveHarness(transferBlock = { _, artifact, _, _, progress ->
            if (attempts++ == 0) throw SaveFailure(SaveError.NETWORK_UNAVAILABLE)
            progress.onBytes(1024)
            TransferResult(artifact.artifactId, 1024, "video/mp4", 1024)
        })
        h.submit(); val origin = Origin(); val links = links(); links.accepted("task", origin)
        h.coordinator.execute("task"); links.afterExecution("task") { h.coordinator.record("task") }
        assertEquals(TaskState.WAITING_USER, h.coordinator.snapshot("task")!!.state)
        h.coordinator.resume("task", h.operation("fresh-operation"))
        assertEquals(TaskState.SUCCEEDED, h.coordinator.execute("task")!!.task.state)
        links.afterExecution("task") { error("resumption must not create an association") }
        assertEquals(listOf("task" to false), origin.outcomes)
        assertEquals(1, h.store.commitCalls)
    }

    @Test fun restartedAssociationStoreCannotInferSavedFromRecoveredSuccess() {
        val h = SaveHarness(); h.run()
        val restarted = links()
        restarted.afterExecution("task") { error("historical successes must not be queried") }
        val old = Origin(); assertFalse(restarted.accepted("task", old, existing = true))
        assertEquals(1, old.abandoned)
        assertEquals(1, h.store.commitCalls)
    }

    @Test fun historyFailureCannotChangeCoordinatorMediaSuccessOrRetryExecution() {
        val h = SaveHarness(); h.submit()
        val origin = Origin(); var completions = 0
        val links = links { _, _, committed -> assertTrue(committed); completions++; throw IllegalStateException() }
        links.accepted("task", origin); h.coordinator.execute("task")
        links.afterExecution("task") { h.coordinator.record("task") }
        links.afterExecution("task") { error("already consumed") }
        assertEquals(1, completions); assertEquals(1, origin.abandoned)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("task")!!.state)
        assertEquals(1, h.transfers); assertEquals(1, h.store.commitCalls)
        assertEquals("媒体保存已完成；发现历史未记录。", mediaTaskDiscoveryOutcomeLabel(
            DiscoveryOriginSaveOutcome(DiscoveryOriginMediaOutcome.COMMITTED, DiscoveryOriginHistoryOutcome.HISTORY_FAILED)))
    }

    @Test fun delayedOutcomeRetainsOnlyItsTaskKeyAndCannotOverwriteAnotherTask() {
        val h = SaveHarness(); val success = h.run()
        val deliveries = mutableListOf<() -> Unit>(); val outcomes = mutableMapOf<String, Boolean>()
        val links = links { _, id, committed -> deliveries += { outcomes[id] = committed } }
        links.accepted("task", Origin()); links.afterExecution("task") { success }
        links.accepted("another", Origin()); links.afterExecution("another") { null }
        deliveries[1](); deliveries[0]()
        assertEquals(mapOf("task" to true, "another" to false), outcomes)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("task")!!.state)
        assertEquals(1, h.store.commitCalls)
    }

    @Test fun expiryActuallyRunsMediaCleanupAndRequiresAnApplicationOwner() {
        val cleanupEntered = CountDownLatch(1); val cleanupRelease = CountDownLatch(1)
        val store = object : FakeMediaStore() {
            override fun cleanup(artifact: StagingArtifact, committed: Boolean) {
                cleanupEntered.countDown(); check(cleanupRelease.await(5, TimeUnit.SECONDS)); super.cleanup(artifact, committed)
            }
        }
        val h = SaveHarness(store = store, transferBlock = { _, _, _, _, _ -> throw SaveFailure(SaveError.NETWORK_UNAVAILABLE) })
        h.run(); assertEquals(TaskState.WAITING_USER, h.coordinator.snapshot("task")!!.state)
        h.clock.now = 101
        val worker = thread { h.coordinator.expireWaitingUsers(h.operation("expiry")) }
        try { await(cleanupEntered); assertTrue(store.cleanup.isEmpty()) }
        finally { cleanupRelease.countDown(); join(worker) }
        assertEquals(TaskState.CANCELLED, h.coordinator.snapshot("task")!!.state)
        assertEquals(1, store.cleanup.size)
    }

    @Test fun allSignalCombinationsKeepEveryIndependentOwnerActive() {
        for (bits in 0 until 1024) {
            fun bit(n: Int) = bits and (1 shl n) != 0
            val signal = MediaTaskActivitySignals(initializing = bit(9), executing = bit(0), preparingStream = bit(1), selectedStream = bit(2),
                pendingCommandCount = if (bit(3)) 1 else 0, pendingRecording = bit(4), recordingOwner = bit(5),
                recordingService = bit(6), segmentRecording = bit(7), resourcesDraining = bit(8))
            assertEquals("owner combination $bits", bits != 0, signal.snapshot().active)
            assertEquals(bit(4) || bit(5) || bit(6) || bit(7), signal.snapshot().recordingActive)
        }
    }

    @Test fun pausedRecordingRemainsOwnedWithoutExecutionAndObserverCloseCannotReleaseIt() {
        val tracker = MediaTaskActivityTracker(); val seen = mutableListOf<MediaTaskActivitySnapshot>()
        val old = tracker.addListener { seen += it }
        tracker.update(MediaTaskActivitySignals(recordingOwner = true))
        old.close(); tracker.update(MediaTaskActivitySignals(recordingOwner = true, resourcesDraining = true))
        val recreated = mutableListOf<MediaTaskActivitySnapshot>(); tracker.addListener { recreated += it }
        assertTrue(recreated.single().recordingActive); assertTrue(recreated.single().resourcesDraining)
        tracker.update(MediaTaskActivitySignals(resourcesDraining = true))
        assertTrue(recreated.last().active)
        tracker.update(MediaTaskActivitySignals())
        assertFalse(recreated.last().active); assertEquals(2, seen.size)
    }

    @Test fun delayedMainInstallationKeepsStartupBusyBeforeSegmentRecoveryIsVisible() {
        val queue = ArrayDeque<() -> Unit>()
        val tracker = MediaTaskActivityTracker()
        lateinit var handoff: MediaTaskActivityHandoff
        var segmentInstalled = false
        var recovering = true
        fun publish() = tracker.update(MediaTaskActivitySignals(initializing = handoff.active,
            segmentRecording = segmentInstalled && recovering))
        handoff = MediaTaskActivityHandoff(::publish)
        publish()
        handoff.enqueue({ queue.addLast(it); true }) { segmentInstalled = true; publish() }
        handoff.preparationFailed()
        val replay = mutableListOf<Boolean>(); tracker.addListener { replay += it.active }
        assertEquals(listOf(true), replay)
        queue.removeFirst()()
        assertFalse(handoff.active); assertTrue(tracker.snapshot.recordingActive)
        assertTrue(replay.all { it })
        recovering = false; publish()
        assertTrue(replay.dropLast(1).all { it }); assertFalse(replay.last())
    }

    @Test fun asynchronousExpiryPublishesMainPriorityBeforeWorkerCleanupAndSurvivesObserverDetach() {
        val mainQueue = ArrayDeque<() -> Unit>(); val controlQueue = ArrayDeque<() -> Unit>()
        val tracker = MediaTaskActivityTracker(); lateinit var expiry: MediaTaskActivityExpiry
        var pendingMainCommands = 0
        fun publish() = tracker.update(MediaTaskActivitySignals(pendingCommandCount = pendingMainCommands + if (expiry.active) 1 else 0))
        expiry = MediaTaskActivityExpiry { mainQueue.addLast { publish() } }
        val observed = mutableListOf<Boolean>(); val observer = tracker.addListener { observed += it.active }
        val waiting = TaskSnapshot("waiting", 0, TaskState.WAITING_USER, TaskMethod.DIRECT,
            waitingUser = UserWait(100, ResumeAction.REVIEW_RECOVERY))
        var cleanupCalls = 0
        expiry.scheduleIfDue(listOf(waiting), 100, { cleanup, _ ->
            mainQueue.addLast {
                pendingMainCommands++; publish()
                controlQueue.addLast { try { cleanup() } finally { pendingMainCommands--; mainQueue.addLast { publish() } } }
            }
        }) {
            assertTrue(tracker.snapshot.active)
            assertEquals(listOf(false, true), observed)
            cleanupCalls++
        }
        assertTrue(controlQueue.isEmpty())
        mainQueue.removeFirst()(); assertTrue(tracker.snapshot.active)
        mainQueue.removeFirst()(); observer.close()
        controlQueue.removeFirst()(); while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
        assertEquals(1, cleanupCalls); assertFalse(tracker.snapshot.active)
        assertEquals(listOf(false, true), observed)
    }

    @Test fun emptyExpiryScansHaveNoDemandEdgesOrMediaCommands() {
        var edges = 0
        val expiry = MediaTaskActivityExpiry { edges++ }
        repeat(50) { assertFalse(expiry.scheduleIfDue(emptyList(), 1_000,
            { _, _ -> error("No work is due") }) { error("No cleanup") }) }
        assertFalse(expiry.active); assertEquals(0, edges)
    }

    @Test fun finalRecordExceptionIsConsumedWithoutRetryOrMediaMutation() {
        val h = SaveHarness(); h.run(); val origin = Origin(); val links = links()
        links.accepted("task", origin); var reads = 0
        links.afterExecution("task") { reads++; throw IllegalStateException() }
        links.afterExecution("task") { error("no second journal read") }
        assertEquals(1, reads); assertEquals(listOf("task" to false), origin.outcomes)
        assertEquals(TaskState.SUCCEEDED, h.coordinator.snapshot("task")!!.state)
    }
}
