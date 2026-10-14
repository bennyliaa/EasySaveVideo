package com.easysavevideo.app.discovery

import com.easysavevideo.discovery.SessionStamp
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

/** Executes the production owner with deterministic queues; these are not Android/device tests. */
class DiscoveryLocalControlOwnerTest {
    private val target = DiscoveryControlTarget("profile-one", SessionStamp("session-one", 7), 4, 3)
    private val request = DiscoveryControlRequest(target, DiscoveryLocalAction.CLEAR_CURRENT_HISTORY)

    private class Queue {
        val tasks = mutableListOf<() -> Unit>()
        fun enqueue(task: () -> Unit): Boolean { tasks += task; return true }
        fun take(): () -> Unit = tasks.removeAt(0)
        fun runNext() = take()()
    }

    private class Harness {
        val publications = mutableListOf<DiscoveryLocalControlState>()
        val worker = Queue()
        val ui = Queue()
        val owner = DiscoveryLocalControlOwner { publications += it }
        var writes = 0
        fun submit(request: DiscoveryControlRequest,
            work: (DiscoveryControlRequest) -> DiscoveryControlOutcome = { writes++; DiscoveryControlOutcome.COMMITTED },
            afterWork: (DiscoveryLocalControlResult) -> Unit = {}) = owner.submit(
            request, worker::enqueue, work, { ui.enqueue(it) }, afterWork)
        fun finish() { worker.runNext(); ui.runNext() }
    }

    @Test fun claimIsInstalledBeforeDispatchAndRejectsReentry() {
        val owner = DiscoveryLocalControlOwner()
        val ui = Queue()
        var writes = 0
        var secondDispatches = 0
        assertTrue(owner.submit(request, dispatch = { task ->
            assertTrue(owner.state.busy)
            assertSame(request, owner.state.active?.request)
            assertFalse(owner.submit(request, { secondDispatches++; true },
                { fail("A second operation must not run"); DiscoveryControlOutcome.COMMITTED }, {}))
            task(); true
        }, work = { writes++; DiscoveryControlOutcome.COMMITTED }, deliver = { ui.enqueue(it) }))
        assertEquals(1, writes)
        assertEquals(0, secondDispatches)
        assertTrue(owner.state.busy)
        ui.runNext()
        assertFalse(owner.state.busy)
        assertEquals(DiscoveryControlOutcome.COMMITTED, owner.state.lastOutcome?.outcome)
    }

    @Test fun pendingOperationIsSharedAcrossCallersUntilUiCompletion() {
        val h = Harness()
        val reset = DiscoveryControlRequest(target, DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "provider-one")
        assertTrue(h.submit(reset))
        val operation = h.owner.state.active
        assertFalse(h.submit(request))
        h.worker.runNext()
        // Reset need not change target: a second screen still cannot confirm the same request.
        assertSame(operation, h.owner.state.active)
        assertFalse(h.submit(reset))
        assertEquals(1, h.worker.tasks.size + h.ui.tasks.size)
        h.ui.runNext()
        assertEquals(1, h.writes)
        assertSame(operation, h.owner.state.lastOutcome?.operation)
    }

    @Test fun duplicateWorkerAndDeliveryCallbacksAreBothExactlyOnce() {
        val h = Harness()
        h.submit(request)
        val worker = h.worker.take()
        worker(); worker()
        assertEquals(1, h.writes)
        assertEquals(1, h.ui.tasks.size)
        assertEquals(1, h.publications.size)
        val completion = h.ui.take()
        completion(); completion()
        assertEquals(2, h.publications.size)
        assertEquals(1, h.publications.count { it.lastOutcome != null })
    }

    @Test fun rejectedDispatchCompletesFailedAndCannotRunRetainedCommand() {
        val owner = DiscoveryLocalControlOwner()
        val ui = Queue()
        var retained: (() -> Unit)? = null
        var writes = 0
        assertTrue(owner.submit(request, { retained = it; false },
            { writes++; DiscoveryControlOutcome.COMMITTED }, { ui.enqueue(it) }))
        requireNotNull(retained)()
        assertEquals(0, writes)
        assertEquals(1, ui.tasks.size)
        ui.runNext()
        assertEquals(DiscoveryControlOutcome.FAILED, owner.state.lastOutcome?.outcome)
        assertFalse(owner.state.busy)
    }

    @Test fun dispatchExceptionCompletesFailed() {
        val owner = DiscoveryLocalControlOwner()
        var writes = 0
        assertTrue(owner.submit(request, { throw RejectedExecutionException("synthetic") },
            { writes++; DiscoveryControlOutcome.COMMITTED }, { it() }))
        assertEquals(0, writes)
        assertEquals(DiscoveryControlOutcome.FAILED, owner.state.lastOutcome?.outcome)
        assertFalse(owner.state.busy)
    }

    @Test fun inlineWorkFollowedByDispatchExceptionCannotOverrideCommit() {
        val h = Harness()
        assertTrue(h.owner.submit(request, { it(); throw RejectedExecutionException("after-inline") },
            { h.writes++; DiscoveryControlOutcome.COMMITTED }, { h.ui.enqueue(it) }))
        assertEquals(1, h.writes)
        assertEquals(1, h.ui.tasks.size)
        h.ui.runNext()
        assertEquals(DiscoveryControlOutcome.COMMITTED, h.owner.state.lastOutcome?.outcome)
    }

    @Test fun workExceptionResolvesFailureWithoutRetry() {
        val h = Harness()
        h.submit(request, work = { h.writes++; throw IllegalStateException("synthetic local failure") })
        h.finish()
        assertEquals(1, h.writes)
        assertEquals(DiscoveryControlOutcome.FAILED, h.owner.state.lastOutcome?.outcome)
        assertFalse(h.owner.state.busy)
        assertTrue(h.worker.tasks.isEmpty())
    }

    @Test fun committedMutationStaysCommittedWhenReadOnlyStatisticsThrows() {
        val h = Harness()
        var refreshes = 0
        h.submit(request, afterWork = {
            assertEquals(DiscoveryControlOutcome.COMMITTED, it.outcome)
            refreshes++
            throw IllegalStateException("synthetic statistics failure")
        })
        h.finish()
        assertEquals(1, h.writes)
        assertEquals(1, refreshes)
        assertEquals(DiscoveryControlOutcome.COMMITTED, h.owner.state.lastOutcome?.outcome)
    }

    @Test fun failedMutationCannotBePromotedBySuccessfulRefresh() {
        val h = Harness()
        var refreshes = 0
        h.submit(request, work = { DiscoveryControlOutcome.FAILED }, afterWork = { refreshes++ })
        h.finish()
        assertEquals(1, refreshes)
        assertEquals(DiscoveryControlOutcome.FAILED, h.owner.state.lastOutcome?.outcome)
    }

    @Test fun closedBeforeSubmissionStillCompletesStaleWithoutDispatchOrWork() {
        val h = Harness()
        h.owner.close()
        assertTrue(h.submit(request))
        assertTrue(h.worker.tasks.isEmpty())
        assertEquals(0, h.writes)
        h.ui.runNext()
        assertEquals(DiscoveryControlOutcome.STALE, h.owner.state.lastOutcome?.outcome)
        assertFalse(h.owner.state.busy)
    }

    @Test fun acceptedQueuedWorkClosedBeforeStartCompletesStaleWithoutMutation() {
        val h = Harness()
        h.submit(request)
        h.owner.close()
        assertTrue(h.owner.state.busy)
        h.finish()
        assertEquals(0, h.writes)
        assertEquals(DiscoveryControlOutcome.STALE, h.owner.state.lastOutcome?.outcome)
    }

    @Test fun closeAfterCommitBeforeUiDeliveryPreservesCommitAndResolvesBusy() {
        val h = Harness()
        h.submit(request)
        h.worker.runNext()
        h.owner.close()
        h.ui.runNext()
        assertEquals(1, h.writes)
        assertEquals(DiscoveryControlOutcome.COMMITTED, h.owner.state.lastOutcome?.outcome)
        assertFalse(h.owner.state.busy)
    }

    @Test fun closeDuringPostCommitRefreshCannotTurnCommitIntoStaleOrFailed() {
        val h = Harness()
        h.submit(request, afterWork = {
            h.owner.close()
            throw IllegalStateException("synthetic read-only refresh failure")
        })
        h.finish()
        assertEquals(DiscoveryControlOutcome.COMMITTED, h.owner.state.lastOutcome?.outcome)
        assertEquals(1, h.writes)
    }

    @Test fun noCoordinatorOrStaleTargetUsesStaleResultWithoutWrites() {
        val h = Harness()
        h.submit(request, work = { captured ->
            DiscoveryPresentationPolicy.executeControl(captured, null, emptySet(),
                { h.writes++; true }, { _, _ -> h.writes++; true })
        })
        h.finish()
        assertEquals(0, h.writes)
        assertEquals(DiscoveryControlOutcome.STALE, h.owner.state.lastOutcome?.outcome)
    }

    @Test fun targetAndProviderAreRecheckedAtExecutionRatherThanAdmission() {
        val h = Harness()
        var current = target
        val reset = DiscoveryControlRequest(target, DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "provider-one")
        h.submit(reset, work = { captured ->
            DiscoveryPresentationPolicy.executeControl(captured, current, setOf("provider-one"),
                { h.writes++; true }, { _, _ -> h.writes++; true })
        })
        current = target.copy(controllerGeneration = target.controllerGeneration + 1)
        h.finish()
        assertEquals(0, h.writes)
        assertEquals(DiscoveryControlOutcome.STALE, h.owner.state.lastOutcome?.outcome)

        h.submit(reset, work = { captured ->
            DiscoveryPresentationPolicy.executeControl(captured, target, setOf("other-provider"),
                { h.writes++; true }, { _, _ -> h.writes++; true })
        })
        h.finish()
        assertEquals(0, h.writes)
        assertEquals(DiscoveryControlOutcome.STALE, h.owner.state.lastOutcome?.outcome)
    }

    @Test fun legitimateClearChangingViewRevisionStillPublishesCommitForOriginalOperation() {
        val h = Harness()
        var current = target
        h.submit(request, work = { captured ->
            DiscoveryPresentationPolicy.executeControl(captured, current, emptySet(), {
                h.writes++
                current = current.copy(viewRevision = current.viewRevision + 1)
                true
            }, { _, _ -> fail("History clear cannot reset progress"); false })
        })
        val operation = requireNotNull(h.owner.state.active)
        h.worker.runNext()
        assertNotEquals(target, current)
        h.ui.runNext()
        assertSame(operation, h.owner.state.lastOutcome?.operation)
        assertEquals(DiscoveryControlOutcome.COMMITTED, h.owner.state.lastOutcome?.outcome)
        assertEquals(1, h.writes)
    }

    @Test fun dismissRequiresExactReceiptIdentityAndCannotClearANewerResult() {
        val h = Harness()
        h.submit(request); h.finish()
        val first = requireNotNull(h.owner.state.lastOutcome)
        assertFalse(h.owner.dismiss(first.copy()))
        assertSame(first, h.owner.state.lastOutcome)

        h.submit(request)
        val secondOperation = requireNotNull(h.owner.state.active)
        assertNotEquals(first.operation.token, secondOperation.token)
        assertFalse(h.owner.dismiss(first))
        assertSame(secondOperation, h.owner.state.active)
        h.finish()
        val second = requireNotNull(h.owner.state.lastOutcome)
        assertFalse(h.owner.dismiss(first))
        assertSame(second, h.owner.state.lastOutcome)
        assertTrue(h.owner.dismiss(second))
        assertNull(h.owner.state.lastOutcome)
        assertFalse(h.owner.dismiss(second))
    }

    @Test fun completionCanReenterForNewOperationAndOldCallbackCannotEraseIt() {
        val worker = Queue()
        val ui = Queue()
        var writes = 0
        var received = 0
        var reentered = false
        lateinit var owner: DiscoveryLocalControlOwner
        fun submit() = owner.submit(request, worker::enqueue,
            { writes++; DiscoveryControlOutcome.COMMITTED }, { ui.enqueue(it) })
        owner = DiscoveryLocalControlOwner { state ->
            if (state.lastOutcome != null) {
                received++
                if (received == 1) reentered = submit()
            }
        }
        assertTrue(submit())
        worker.runNext()
        val oldCompletion = ui.take()
        oldCompletion()
        assertTrue(reentered)
        val second = requireNotNull(owner.state.active)
        oldCompletion()
        assertSame(second, owner.state.active)
        assertEquals(1, received)
        worker.runNext(); ui.runNext()
        oldCompletion()
        assertSame(second, owner.state.lastOutcome?.operation)
        assertEquals(2, received)
        assertEquals(2, writes)
    }

    @Test fun admissionPublicationCannotReenterForAnotherOperation() {
        val worker = Queue()
        var attempted = false
        var accepted = true
        lateinit var owner: DiscoveryLocalControlOwner
        owner = DiscoveryLocalControlOwner { state ->
            if (state.busy && !attempted) {
                attempted = true
                accepted = owner.submit(request, worker::enqueue,
                    { DiscoveryControlOutcome.COMMITTED }, { it() })
            }
        }
        assertTrue(owner.submit(request, worker::enqueue, { DiscoveryControlOutcome.COMMITTED }, { it() }))
        assertTrue(attempted)
        assertFalse(accepted)
        assertEquals(1, worker.tasks.size)
        worker.runNext()
    }

    @Test fun receiptsDoNotExposeProfileOrProviderIdentifiersInToString() {
        val h = Harness()
        h.submit(DiscoveryControlRequest(target, DiscoveryLocalAction.RESET_PROVIDER_PROGRESS, "private-provider"))
        assertEquals("DiscoveryLocalControlState([redacted])", h.owner.state.toString())
        assertEquals("DiscoveryLocalControlOperation([redacted])", h.owner.state.active.toString())
        h.finish()
        assertEquals("DiscoveryLocalControlResult([redacted])", h.owner.state.lastOutcome.toString())
    }
}
