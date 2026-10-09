package com.easysavevideo.app.discovery

import com.easysavevideo.commons.maintenance.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ProviderManagementControllerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun snapshot() = ProviderRepository(temporary.newFolder()).use { it.snapshot() }

    private class Lane : Executor {
        var queued: Runnable? = null
        var running = false
        var submissions = 0
        override fun execute(command: Runnable) {
            if (queued != null || running) throw RejectedExecutionException()
            queued = command; submissions++
        }
        fun run() {
            val task = checkNotNull(queued); queued = null; running = true
            try { task.run() } finally { running = false }
        }
    }
    private class Main {
        val callbacks = mutableListOf<() -> Unit>()
        fun dispatch(work: () -> Unit) { callbacks += work }
        fun drain() { while (callbacks.isNotEmpty()) callbacks.removeAt(0).invoke() }
    }
    private class Backend(var value: ProviderRepositorySnapshot) : ProviderManagementBackend {
        var inspections = 0
        var rejections = mutableListOf<Long>()
        var rollbacks = 0
        var onInspect: (() -> Unit)? = null
        var onReject: (() -> Unit)? = null
        var rejectionError: ProviderRepositoryError? = null
        override fun inspect(): ProviderRepositorySnapshot { inspections++; onInspect?.invoke(); return value }
        override fun rejectCandidate(expectedVersion: Long, checkCancelled: () -> Unit): ProviderRepositoryResult {
            checkCancelled(); rejections += expectedVersion; onReject?.invoke()
            return rejectionError?.let { ProviderRepositoryResult.Failure(it) } ?: ProviderRepositoryResult.Success(value)
        }
        override fun rollback(expectedGeneration: Long, checkCancelled: () -> Unit): ProviderRepositoryResult {
            checkCancelled(); rollbacks++
            if (expectedGeneration != value.generation) return ProviderRepositoryResult.Failure(ProviderRepositoryError.STALE_CANDIDATE)
            return ProviderRepositoryResult.Success(value)
        }
    }
    private class Fixture(val backend: Backend) {
        val lane = Lane()
        val main = Main()
        val published = mutableListOf<ProviderManagementState>()
        val controller = ProviderManagementController(backend, lane, main::dispatch, published::add)
        fun open() { controller.setActive(true); finish() }
        fun finish() { lane.run(); main.drain() }
    }

    @Test fun inspectionIsBackgroundAndRepeatedClicksAdmitOnlyOneTask() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.controller.setActive(true)
        repeat(10) { fixture.controller.inspect(); fixture.controller.setActive(true) }
        assertEquals(0, fixture.backend.inspections)
        assertEquals(1, fixture.lane.submissions)
        assertTrue(fixture.controller.state.busy)
        fixture.finish()
        assertEquals(1, fixture.backend.inspections)
        assertFalse(fixture.controller.state.busy)
        assertNotNull(fixture.controller.state.snapshot)
    }

    @Test fun onlyRepositoryAuthorizedActionsAreAdmitted() {
        val fixture = Fixture(Backend(snapshot().copy(pendingVersion = 4, canRejectCandidate = false, canRollback = false)))
        fixture.open()
        fixture.controller.rejectCandidate(); fixture.controller.rollback()
        assertEquals(1, fixture.lane.submissions)
        assertTrue(fixture.backend.rejections.isEmpty())
        assertEquals(0, fixture.backend.rollbacks)
    }

    @Test fun rejectionUsesExactCandidateAndRepeatedClicksDoNotQueue() {
        val fixture = Fixture(Backend(snapshot().copy(pendingVersion = 7, canRejectCandidate = true)))
        fixture.open()
        repeat(8) { fixture.controller.rejectCandidate() }
        assertEquals(2, fixture.lane.submissions)
        fixture.finish()
        assertEquals(listOf(7L), fixture.backend.rejections)
    }

    @Test fun rollbackIsGatedAndUsesSinglePhysicalTask() {
        val fixture = Fixture(Backend(snapshot().copy(canRollback = true)))
        fixture.open()
        repeat(8) { fixture.controller.rollback() }
        fixture.finish()
        assertEquals(1, fixture.backend.rollbacks)
    }

    @Test fun closedBeforeWorkStartsDoesNotInspectOrPublish() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.controller.setActive(true)
        fixture.controller.close()
        val publications = fixture.published.size
        fixture.finish()
        assertEquals(0, fixture.backend.inspections)
        assertEquals(publications, fixture.published.size)
        assertFalse(fixture.controller.state.active)
    }

    @Test fun hidingBeforeQueuedMutationStartsCancelsIt() {
        val fixture = Fixture(Backend(snapshot().copy(pendingVersion = 8, canRejectCandidate = true)))
        fixture.open(); fixture.controller.rejectCandidate(); fixture.controller.setActive(false)
        fixture.finish()
        assertTrue(fixture.backend.rejections.isEmpty())
        assertNull(fixture.controller.state.snapshot)
    }

    @Test fun completedOldReadCannotOverwriteReopenedRead() {
        val old = snapshot().copy(highestAcceptedVersion = 10)
        val fixture = Fixture(Backend(old))
        fixture.controller.setActive(true); fixture.lane.run()
        val delayed = fixture.main.callbacks.removeAt(0)
        fixture.controller.setActive(false)
        fixture.backend.value = old.copy(highestAcceptedVersion = 12)
        fixture.controller.setActive(true); fixture.finish()
        delayed()
        assertEquals(12L, fixture.controller.state.snapshot?.highestAcceptedVersion)
    }

    @Test fun reopeningWhileOldPhysicalWorkRunsShowsBusyWithoutNewWorker() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.backend.onInspect = {
            fixture.controller.setActive(false)
            fixture.controller.setActive(true)
        }
        fixture.controller.setActive(true); fixture.finish()
        assertEquals(1, fixture.lane.submissions)
        assertEquals(ProviderManagementProblem.WORKER_BUSY, fixture.controller.state.problem)
        assertFalse(fixture.controller.state.busy)
        assertNull(fixture.controller.state.snapshot)
        fixture.backend.onInspect = null
        fixture.controller.inspect(); fixture.finish()
        assertNotNull(fixture.controller.state.snapshot)
        assertNull(fixture.controller.state.problem)
    }

    @Test fun closedDuringMutationCannotPublishEvenIfTransactionReturnedSuccess() {
        val fixture = Fixture(Backend(snapshot().copy(pendingVersion = 9, canRejectCandidate = true)))
        fixture.open()
        fixture.backend.onReject = fixture.controller::close
        fixture.controller.rejectCandidate(); fixture.finish()
        assertEquals(listOf(9L), fixture.backend.rejections)
        assertFalse(fixture.controller.state.active)
        assertNull(fixture.controller.state.completed)
        assertNull(fixture.controller.state.snapshot)
    }

    @Test fun successfulTransactionKeepsDisabledSnapshotAndHighWater() {
        val fixture = Fixture(Backend(snapshot().copy(pendingVersion = 11, canRejectCandidate = true)))
        fixture.open()
        fixture.backend.value = fixture.backend.value.copy(state = ProviderRepositoryState.DISABLED,
            pendingVersion = null, canRejectCandidate = false, highestAcceptedVersion = 11,
            highestAcceptedPolicyVersion = 5, minimumSafePackageVersion = 10, error = ProviderRepositoryError.UNSAFE_VERSION)
        fixture.controller.rejectCandidate(); fixture.finish()
        assertEquals(ProviderManagementOperation.REJECT_CANDIDATE, fixture.controller.state.completed)
        assertEquals(ProviderRepositoryState.DISABLED, fixture.controller.state.snapshot?.state)
        assertEquals(11L, fixture.controller.state.snapshot?.highestAcceptedVersion)
        assertEquals(5L, fixture.controller.state.snapshot?.highestAcceptedPolicyVersion)
        assertEquals(10L, fixture.controller.state.snapshot?.minimumSafePackageVersion)
    }

    @Test fun rejectedActionReadsLatestStateAndDoesNotReportCompletion() {
        val fixture = Fixture(Backend(snapshot().copy(pendingVersion = 2, canRejectCandidate = true)))
        fixture.open()
        fixture.backend.rejectionError = ProviderRepositoryError.STALE_CANDIDATE
        fixture.backend.value = fixture.backend.value.copy(pendingVersion = 3, highestAcceptedVersion = 3)
        fixture.controller.rejectCandidate(); fixture.finish()
        assertEquals(ProviderManagementProblem.ACTION_REJECTED, fixture.controller.state.problem)
        assertEquals(ProviderRepositoryError.STALE_CANDIDATE, fixture.controller.state.repositoryError)
        assertEquals(3L, fixture.controller.state.snapshot?.pendingVersion)
        assertNull(fixture.controller.state.completed)
    }

    @Test fun rawExceptionTextNeverEntersPublishedState() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.backend.onInspect = { throw IllegalStateException("https://private.invalid/?query=secret-title") }
        fixture.open()
        assertEquals(ProviderManagementProblem.LOCAL_UNAVAILABLE, fixture.controller.state.problem)
        assertEquals("ProviderManagementState([redacted])", fixture.controller.state.toString())
        assertNull(fixture.controller.state.snapshot)
    }

    @Test fun closedControllerCannotReopenOrAdmitActions() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.controller.close()
        fixture.controller.setActive(true); fixture.controller.inspect(); fixture.controller.rollback(); fixture.controller.rejectCandidate()
        assertEquals(0, fixture.lane.submissions)
    }
    @Test fun rollbackUsesDisplayedGenerationAndRejectsChangedTarget() {
        val fixture = Fixture(Backend(snapshot().copy(generation = 4, canRollback = true)))
        fixture.open()
        fixture.backend.value = fixture.backend.value.copy(generation = 5)
        fixture.controller.rollback(); fixture.finish()
        assertEquals(ProviderRepositoryError.STALE_CANDIDATE, fixture.controller.state.repositoryError)
        assertNull(fixture.controller.state.completed)
        assertEquals(5L, fixture.controller.state.snapshot?.generation)
    }

    @Test fun automaticRefreshRetainsTransactionNoticeAndReadsNewAuthority() {
        val fixture = Fixture(Backend(snapshot().copy(canRollback = true)))
        fixture.open(); fixture.controller.rollback(); fixture.finish()
        fixture.backend.value = fixture.backend.value.copy(state = ProviderRepositoryState.DISABLED,
            error = ProviderRepositoryError.EXPIRED)
        fixture.controller.refreshAutomatically(); fixture.finish()
        assertEquals(ProviderManagementOperation.ROLLBACK, fixture.controller.state.completed)
        assertEquals(ProviderRepositoryState.DISABLED, fixture.controller.state.snapshot?.state)
        assertNotNull(fixture.controller.state.observedAtEpochMillis)
    }

    @Test fun automaticRefreshDoesNotRetryMissingInitialSnapshotOrRunHidden() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.controller.refreshAutomatically()
        fixture.open(); fixture.controller.setActive(false); fixture.controller.refreshAutomatically()
        assertEquals(1, fixture.lane.submissions)
    }

    @Test fun successfulAutomaticRefreshClearsOldWorkerBusyAndRestoresAvailableActions() {
        val fixture = Fixture(Backend(snapshot().copy(canRollback = true)))
        fixture.open()
        fixture.lane.running = true
        fixture.controller.refreshAutomatically()
        assertEquals(ProviderManagementProblem.WORKER_BUSY, fixture.controller.state.problem)
        fixture.lane.running = false
        fixture.controller.refreshAutomatically(); fixture.finish()
        assertNull(fixture.controller.state.problem)
        assertTrue(ProviderManagementPresentationPolicy.present(fixture.controller.state,
            ProviderManagementConfiguration(true), true).canRollback)
    }

    @Test fun backendCancellationDoesNotBecomeNullSnapshotSuccess() {
        val fixture = Fixture(Backend(snapshot()))
        fixture.backend.onInspect = { throw java.util.concurrent.CancellationException("PRIVATE_DETAIL") }
        fixture.open()
        assertEquals(ProviderManagementProblem.ACTION_REJECTED, fixture.controller.state.problem)
        assertEquals(ProviderRepositoryError.CANCELLED, fixture.controller.state.repositoryError)
        assertNull(fixture.controller.state.completed)
        assertFalse(fixture.controller.state.busy)
    }

}
