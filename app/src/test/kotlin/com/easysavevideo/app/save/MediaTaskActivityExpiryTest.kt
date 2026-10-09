package com.easysavevideo.app.save

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MediaTaskActivityExpiryTest {
    private fun waiting(deadline: Long = 100) = TaskSnapshot("waiting", 0, TaskState.WAITING_USER,
        TaskMethod.DIRECT, waitingUser = UserWait(deadline, ResumeAction.REVIEW_RECOVERY))

    @Test fun repeatedEmptyOrNotDueScansDoNotPublishPriorityOrStartWork() {
        var changes = 0
        val owner = MediaTaskActivityExpiry { changes++ }
        repeat(10) {
            assertFalse(owner.scheduleIfDue(emptyList(), 200, { _, _ -> error("Empty dispatch") }) { error("Empty cleanup") })
            assertFalse(owner.scheduleIfDue(listOf(waiting(1_000)), 200, { _, _ -> error("Early dispatch") }) { error("Early cleanup") })
        }
        assertEquals(0, changes)
        assertFalse(owner.active)
    }

    @Test fun realExpiryOwnsQueuedAndExecutingCleanupWithoutDuplicateBatches() {
        val tracker = MediaTaskActivityTracker()
        lateinit var owner: MediaTaskActivityExpiry
        owner = MediaTaskActivityExpiry { tracker.update(MediaTaskActivitySignals(pendingCommandCount = if (owner.active) 1 else 0)) }
        var queued: (() -> Unit)? = null
        var cleanups = 0
        assertTrue(owner.scheduleIfDue(listOf(waiting()), 100, { work, _ -> queued = work }) {
            assertTrue(owner.active); assertTrue(tracker.snapshot.active); cleanups++
        })
        assertTrue(tracker.snapshot.active)
        assertFalse(owner.scheduleIfDue(listOf(waiting()), 100, { _, _ -> error("Duplicate batch") }) { })
        queued!!.invoke(); queued!!.invoke()
        assertEquals(1, cleanups)
        assertFalse(tracker.snapshot.active)
    }

    @Test fun queueRejectionEndsOwnershipAndOldWorkCannotAffectANewBatch() {
        val owner = MediaTaskActivityExpiry { }
        var oldWork: (() -> Unit)? = null
        owner.scheduleIfDue(listOf(waiting()), 100, { work, rejected -> oldWork = work; rejected() }) { error("Rejected cleanup") }
        assertFalse(owner.active)
        var newWork: (() -> Unit)? = null
        owner.scheduleIfDue(listOf(waiting()), 100, { work, _ -> newWork = work }) { }
        oldWork!!.invoke()
        assertTrue(owner.active)
        newWork!!.invoke()
        assertFalse(owner.active)
    }

    @Test fun cleanupAndDispatchExceptionsAlwaysReleaseTheirOwner() {
        val owner = MediaTaskActivityExpiry { }
        try { owner.scheduleIfDue(listOf(waiting()), 100, { work, _ -> work() }) { throw IllegalStateException() } }
        catch (_: IllegalStateException) { }
        assertFalse(owner.active)
        try { owner.scheduleIfDue(listOf(waiting()), 100, { _, _ -> throw IllegalStateException() }) { } }
        catch (_: IllegalStateException) { }
        assertFalse(owner.active)
    }
}
