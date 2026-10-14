package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

class SaveWorkerHandoffTest {
    @Test fun acceptedQueuedWorkerOwnsReleaseBeforeEntry() {
        var queued:Runnable?=null;var work=0
        assertTrue(SaveWorkerHandoff().launch({queued=it}){work++});assertEquals(0,work)
        queued!!.run();assertEquals(1,work)
    }
    @Test fun duplicateRunnableEntersOnlyOnce() {
        var queued:Runnable?=null;var work=0
        assertTrue(SaveWorkerHandoff().launch({queued=it}){work++})
        repeat(3){queued!!.run()};assertEquals(1,work)
    }
    @Test fun rejectionHasNoAcceptedWorker() {
        var work=0;assertFalse(SaveWorkerHandoff().launch({throw RejectedExecutionException()}){work++});assertEquals(0,work)
    }
    @Test fun enqueueThenUnknownExceptionSuppressesLateEntry() {
        var queued:Runnable?=null;var work=0
        assertFalse(SaveWorkerHandoff().launch({queued=it;throw IllegalStateException()}){work++})
        queued!!.run();assertEquals(0,work)
    }
    @Test fun entryThenUnknownExceptionRetainsWorkerOwnership() {
        var work=0
        assertTrue(SaveWorkerHandoff().launch({it.run();throw IllegalStateException()}){work++});assertEquals(1,work)
    }
    @Test fun entryThenRejectedExceptionStillRetainsWorkerOwnership() {
        var work=0
        assertTrue(SaveWorkerHandoff().launch({it.run();throw RejectedExecutionException()}){work++});assertEquals(1,work)
    }
    @Test fun completedWorkerCannotBeLaunchedAgain() {
        val handoff=SaveWorkerHandoff();var work=0
        assertTrue(handoff.launch({it.run()}){work++});assertFalse(handoff.launch({it.run()}){work++});assertEquals(1,work)
    }
    @Test fun suppressedWorkerCannotBeLaunchedAgain() {
        val handoff=SaveWorkerHandoff();var work=0
        assertFalse(handoff.launch({throw IllegalStateException()}){work++})
        assertFalse(handoff.launch({it.run()}){work++});assertEquals(0,work)
    }
}
