package com.easysavevideo.app.discovery

import com.easysavevideo.commons.CommonsLimits
import com.easysavevideo.commons.maintenance.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

/** Pure UI-state controller review; this is not Compose/device acceptance. */
class IndependentProviderManagementTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun run() { tasks.removeFirst().run() }
    }
    private fun snapshot(version: Long = 1, pending: Boolean = true, rollback: Boolean = false) = ProviderRepositorySnapshot(
        ProviderPolicy(version, 0, Long.MAX_VALUE, true, CommonsLimits()), version,
        if (pending) ProviderRepositoryState.CANDIDATE else ProviderRepositoryState.ACTIVE, version,
        if (pending) version else null, rollback, canRejectCandidate = pending)
    private class Backend(var value: ProviderRepositorySnapshot) : ProviderManagementBackend {
        var inspections = 0; var rejects = 0; var rollbacks = 0
        var onInspect: (() -> Unit)? = null
        var rejection: ProviderRepositoryResult? = null
        override fun inspect(): ProviderRepositorySnapshot { inspections++; onInspect?.invoke(); return value }
        override fun rejectCandidate(expectedVersion: Long, checkCancelled: () -> Unit): ProviderRepositoryResult {
            checkCancelled(); rejects++; assertEquals(value.pendingVersion, expectedVersion)
            return rejection ?: ProviderRepositoryResult.Success(value.copy(pendingVersion = null, canRejectCandidate = false))
        }
        override fun rollback(expectedGeneration: Long, checkCancelled: () -> Unit): ProviderRepositoryResult {
            checkCancelled(); assertEquals(value.generation, expectedGeneration); rollbacks++; return ProviderRepositoryResult.Success(value)
        }
    }
    private class Fixture(val backend: Backend = Backend(IndependentProviderManagementTest().snapshot())) {
        val worker = Queue(); val callbacks = ArrayDeque<() -> Unit>(); val published = mutableListOf<ProviderManagementState>()
        val controller = ProviderManagementController(backend, worker, { callbacks += it }, { published += it })
        fun complete() { worker.run(); callbacks.removeFirst()() }
        fun open() { controller.setActive(true); complete() }
    }
    @Test fun closingBeforeQueuedInspectionPreventsBackendAndAnyResult() {
        val f = Fixture(); f.controller.setActive(true); f.controller.close(); f.complete()
        assertEquals(0, f.backend.inspections); assertFalse(f.controller.state.active); assertNull(f.controller.state.snapshot)
        val count = f.published.size; f.controller.setActive(true); f.controller.inspect()
        assertEquals(count, f.published.size); assertTrue(f.worker.tasks.isEmpty())
    }
    @Test fun oldCompletionCannotOverwriteAReopenedSession() {
        val f = Fixture(); f.controller.setActive(true); f.worker.run()
        f.controller.setActive(false); f.backend.value = snapshot(8); f.controller.setActive(true)
        f.callbacks.removeFirst()(); assertTrue(f.controller.state.busy); assertNull(f.controller.state.snapshot)
        f.complete(); assertEquals(8L, f.controller.state.snapshot?.highestAcceptedVersion)
        assertEquals(2, f.backend.inspections)
    }
    @Test fun repeatedActionAndRefreshWhileBusyProduceOneMutation() {
        val f = Fixture(); f.open(); f.controller.rejectCandidate()
        repeat(20) { f.controller.rejectCandidate(); f.controller.inspect(); f.controller.rollback() }
        assertEquals(1, f.worker.tasks.size); f.complete()
        assertEquals(1, f.backend.rejects); assertEquals(1, f.backend.inspections)
        assertEquals(ProviderManagementOperation.REJECT_CANDIDATE, f.controller.state.completed)
    }
    @Test fun hiddenMutationBeforeWorkerStartsIsCancelledWithoutFalseCompletion() {
        val f = Fixture(); f.open(); f.controller.rejectCandidate(); f.controller.setActive(false); f.complete()
        assertEquals(0, f.backend.rejects); assertNull(f.controller.state.completed); assertFalse(f.controller.state.active)
        f.controller.setActive(true); f.complete(); assertEquals(1L, f.controller.state.snapshot?.pendingVersion)
    }
    @Test fun unavailableWorkerLeavesNoSuccessAndCanRefreshLater() {
        val backend = Backend(snapshot()); var reject = true; val queue = Queue()
        val worker = Executor { if (reject) throw RejectedExecutionException() else queue.execute(it) }
        val controller = ProviderManagementController(backend, worker, { it() }, {})
        controller.setActive(true); assertFalse(controller.state.busy)
        assertEquals(ProviderManagementProblem.WORKER_BUSY, controller.state.problem); assertNull(controller.state.completed)
        reject = false; controller.inspect(); queue.run(); assertNotNull(controller.state.snapshot)
    }
    @Test fun repositoryDenialIsVisibleAndNeverRenderedAsSuccess() {
        val f = Fixture(); f.open(); f.backend.rejection = ProviderRepositoryResult.Failure(ProviderRepositoryError.STALE_CANDIDATE)
        f.controller.rejectCandidate(); f.complete()
        assertEquals(ProviderManagementProblem.ACTION_REJECTED, f.controller.state.problem)
        assertEquals(ProviderRepositoryError.STALE_CANDIDATE, f.controller.state.repositoryError)
        assertNull(f.controller.state.completed); assertNotNull(f.controller.state.snapshot)
    }
    @Test fun legalActionFlagsPreventInventedCandidateOrRollback() {
        val f = Fixture(Backend(snapshot(pending = false, rollback = false))); f.open()
        repeat(5) { f.controller.rejectCandidate(); f.controller.rollback() }
        assertTrue(f.worker.tasks.isEmpty()); assertEquals(0, f.backend.rejects); assertEquals(0, f.backend.rollbacks)
    }
    @Test fun unreadableRepositoryNeverPresentsDefaultZerosAsVerifiedEvidence() {
        for (error in listOf(ProviderRepositoryError.CORRUPT_STATE, ProviderRepositoryError.STORAGE_FAILURE,
            ProviderRepositoryError.BUSY, ProviderRepositoryError.CLOSED)) {
            val snap = snapshot().copy(error = error, canRollback = true, canRejectCandidate = true)
            val view = ProviderManagementPresentationPolicy.present(ProviderManagementState(active = true, snapshot = snap),
                ProviderManagementConfiguration(contactConfigured = false), localEnabled = true)
            assertFalse(view.canRejectCandidate); assertFalse(view.canRollback)
            assertFalse(view.versions.any { it.contains("高水位：v0") || it.contains("包 v1") })
            assertFalse(view.health.any { it.contains("0 / 3") }); assertTrue(view.configuration.any { it.contains("零来源") })
        }
    }
    @Test fun missingRootsAndEndpointCannotBePresentedAsRemoteUpdateSuccess() {
        val view = ProviderManagementPresentationPolicy.present(ProviderManagementState(active = true, snapshot = snapshot()),
            ProviderManagementConfiguration(contactConfigured = false), localEnabled = true)
        assertTrue(view.configuration.any { it.contains("包验签根：缺失") })
        assertTrue(view.configuration.any { it.contains("策略验签根：缺失") })
        assertTrue(view.configuration.any { it.contains("更新端点：缺失") }); assertNull(view.notice)
        assertTrue(view.summary.contains("零来源"))
    }
    @Test fun backendCancellationWithoutSessionCancellationIsNotSuccessfulInspection() {
        val f = Fixture(); f.backend.onInspect = { throw java.util.concurrent.CancellationException("synthetic") }
        f.open(); assertNull(f.controller.state.completed); assertNull(f.controller.state.snapshot)
        assertEquals(ProviderManagementProblem.ACTION_REJECTED, f.controller.state.problem)
        assertEquals(ProviderRepositoryError.CANCELLED, f.controller.state.repositoryError)
    }
    @Test fun inspectionExceptionPublishesUnavailableNotOldStateOrSuccess() {
        val f = Fixture(); f.open(); f.backend.onInspect = { error("synthetic") }
        f.controller.inspect(); f.complete(); assertNull(f.controller.state.snapshot)
        assertNull(f.controller.state.completed); assertEquals(ProviderManagementProblem.LOCAL_UNAVAILABLE, f.controller.state.problem)
    }
}
