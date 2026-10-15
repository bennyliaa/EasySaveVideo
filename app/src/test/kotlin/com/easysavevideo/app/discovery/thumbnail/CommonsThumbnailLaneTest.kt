package com.easysavevideo.app.discovery.thumbnail

import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

/** Executes the production lane with deterministic queues, without Handler/Looper or controllers. */
class CommonsThumbnailLaneTest {
    private class Harness {
        val workers = ArrayDeque<Runnable>()
        val deliveries = ArrayDeque<Runnable>()
        var acceptsDelivery = true
        val lane = CommonsThumbnailLane(
            execute = { workers.addLast(it) },
            deliver = { deliveries.addLast(it); acceptsDelivery },
        )
        fun runWorker() = workers.removeFirst().run()
    }

    @Test fun replacementOwnerWaitsForPhysicalReturnAndDeliveryThenReceivesOneAvailableSignal() {
        val h = Harness()
        val trace = mutableListOf<String>()
        var oldNotifications = 0
        val oldSubscription = h.lane.subscribeAvailable { oldNotifications++ }
        val replacement = Runnable { h.lane.postCompletion(Runnable { trace += "new complete" }) }
        assertTrue(h.lane.submit(Runnable {
            trace += "old read"
            h.lane.postCompletion(Runnable { trace += "old complete" })
            // Registering completion does not mean the response/decoder cleanup has returned.
            assertFalse(h.lane.submit(replacement))
            assertTrue(h.deliveries.isEmpty())
            trace += "old physical return"
        }))
        // Models controller close/reopen: old subscription retires, process lane remains shared.
        oldSubscription.close()
        val replacementSubscription = h.lane.subscribeAvailable {
            trace += "new available"
            assertTrue(h.lane.submit(replacement))
        }
        assertFalse(h.lane.submit(replacement))
        assertEquals(1, h.workers.size)
        h.runWorker()
        assertEquals(listOf("old read", "old physical return"), trace)
        assertFalse(h.lane.submit(replacement))
        assertTrue(h.workers.isEmpty())
        val oldDelivery = h.deliveries.removeFirst()
        oldDelivery.run()
        assertEquals(listOf("old read", "old physical return", "old complete", "new available"), trace)
        assertEquals(0, oldNotifications)
        assertEquals(1, h.workers.size)
        oldDelivery.run()
        assertEquals(4, trace.size)
        assertEquals(1, h.workers.size)
        replacementSubscription.close()
        h.runWorker()
        h.deliveries.removeFirst().run()
        assertEquals("new complete", trace.last())
    }

    @Test fun completionIsExactlyOnceAndAnOldDeliveryCannotReleaseANewerAdmission() {
        val h = Harness()
        var completions = 0
        val work = Runnable { h.lane.postCompletion(Runnable { completions++ }) }
        assertTrue(h.lane.submit(work))
        h.runWorker()
        val first = h.deliveries.removeFirst()
        first.run()
        first.run()
        assertEquals(1, completions)
        assertTrue(h.lane.submit(work))
        first.run()
        assertEquals(1, completions)
        assertFalse(h.lane.submit(work))
        assertEquals(1, h.workers.size)
        h.runWorker()
        assertFalse(h.lane.submit(work))
        val second = h.deliveries.removeFirst()
        second.run()
        second.run()
        assertEquals(2, completions)
        assertTrue(h.lane.submit(work))
    }

    @Test fun aCompletionCanScheduleNextWorkWithoutASpuriousAvailableNotification() {
        val h = Harness()
        var notifications = 0
        val subscription = h.lane.subscribeAvailable { notifications++ }
        val next = Runnable { h.lane.postCompletion(Runnable {}) }
        assertTrue(h.lane.submit(Runnable {
            h.lane.postCompletion(Runnable { assertTrue(h.lane.submit(next)) })
        }))
        h.runWorker()
        h.deliveries.removeFirst().run()
        assertEquals(0, notifications)
        assertEquals(1, h.workers.size)
        h.runWorker()
        h.deliveries.removeFirst().run()
        assertEquals(1, notifications)
        subscription.close()
    }

    @Test fun poisonDuringWorkSurvivesCompletionAndPreventsNewQueuedAdmission() {
        val h = Harness()
        var completions = 0
        var notifications = 0
        val subscription = h.lane.subscribeAvailable { notifications++ }
        val work = Runnable { h.lane.postCompletion(Runnable { completions++ }) }
        assertTrue(h.lane.submit(work))
        h.lane.poison()
        repeat(3) { assertFalse(h.lane.submit(work)) }
        assertEquals(1, h.workers.size)
        h.runWorker()
        h.deliveries.removeFirst().run()
        assertEquals(1, completions)
        assertEquals(0, notifications)
        h.lane.poison()
        repeat(3) { assertFalse(h.lane.submit(work)) }
        assertTrue(h.workers.isEmpty())
        subscription.close()
    }

    @Test fun poisonBeforeAdmissionCannotBeResetByNewObserversOrRepeatedSubmit() {
        val h = Harness()
        h.lane.poison()
        var notifications = 0
        val subscription = h.lane.subscribeAvailable { notifications++ }
        repeat(3) { assertFalse(h.lane.submit(Runnable { fail("Poisoned work ran") })) }
        assertTrue(h.workers.isEmpty())
        assertEquals(0, notifications)
        subscription.close()
    }

    @Test fun rejectedDeliveryPermanentlyBlocksNewWorkAndDoesNotSignalAvailable() {
        val h = Harness()
        var completions = 0
        var notifications = 0
        val subscription = h.lane.subscribeAvailable { notifications++ }
        h.acceptsDelivery = false
        assertTrue(h.lane.submit(Runnable { h.lane.postCompletion(Runnable { completions++ }) }))
        h.runWorker()
        assertEquals(1, h.deliveries.size)
        assertEquals(0, completions)
        assertEquals(0, notifications)
        // A new accepting dispatcher or a new observer must not heal a poisoned process lane.
        h.acceptsDelivery = true
        repeat(3) { assertFalse(h.lane.submit(Runnable { fail("Rejected-delivery lane reopened") })) }
        assertTrue(h.workers.isEmpty())
        subscription.close()
    }

    @Test fun returningWithoutACompletionPoisonsTheLaneInsteadOfFreeingItsSlot() {
        val h = Harness()
        assertTrue(h.lane.submit(Runnable {}))
        h.runWorker()
        assertTrue(h.deliveries.isEmpty())
        repeat(3) { assertFalse(h.lane.submit(Runnable { fail("Missing completion released slot") })) }
        assertTrue(h.workers.isEmpty())
    }

    @Test fun executorRejectionBlocksTheLaneAndDoesNotDeliverOrNotify() {
        var submissions = 0
        var deliveries = 0
        var notifications = 0
        val lane = CommonsThumbnailLane(
            execute = { submissions++; throw RejectedExecutionException("OWNED_TEST_REJECTION") },
            deliver = { deliveries++; true },
        )
        val subscription = lane.subscribeAvailable { notifications++ }
        assertThrows(IllegalStateException::class.java) { lane.submit(Runnable {}) }
        assertFalse(lane.submit(Runnable {}))
        assertEquals(1, submissions)
        assertEquals(0, deliveries)
        assertEquals(0, notifications)
        subscription.close()
    }

    @Test fun onlyOneCompletionCanBeRegisteredByTheActiveWorker() {
        val h = Harness()
        var first = 0
        var duplicate = 0
        assertTrue(h.lane.submit(Runnable {
            h.lane.postCompletion(Runnable { first++ })
            assertThrows(IllegalStateException::class.java) {
                h.lane.postCompletion(Runnable { duplicate++ })
            }
        }))
        h.runWorker()
        assertEquals(1, h.deliveries.size)
        h.deliveries.removeFirst().run()
        assertEquals(1, first)
        assertEquals(0, duplicate)
    }

    @Test fun completionCannotBeRegisteredOutsideThePhysicalWorker() {
        val h = Harness()
        assertThrows(IllegalStateException::class.java) { h.lane.postCompletion(Runnable {}) }
        assertTrue(h.lane.submit(Runnable { h.lane.postCompletion(Runnable {}) }))
        assertThrows(IllegalStateException::class.java) { h.lane.postCompletion(Runnable {}) }
        h.runWorker()
        assertThrows(IllegalStateException::class.java) { h.lane.postCompletion(Runnable {}) }
        h.deliveries.removeFirst().run()
    }
}
