package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RecordingControlOwnerTest {
    @Test fun clockSamplingCannotRaceDuplicateStopAheadOfRelease() {
        val owner = RecordingControlOwner(Any())
        val clock = AtomicLong()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val observed = mutableListOf<Long>()
        val first = Thread {
            owner.transition {
                val now = clock.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
                observed += now
            }
        }
        val duplicateStop = Thread {
            waiting.countDown()
            owner.transition { observed += clock.incrementAndGet() }
        }
        first.start(); assertTrue(entered.await(3, TimeUnit.SECONDS))
        duplicateStop.start(); assertTrue(waiting.await(3, TimeUnit.SECONDS))
        // The second transition cannot sample its clock until the first finishes mutation.
        assertEquals(1L, clock.get())
        release.countDown(); first.join(3_000); duplicateStop.join(3_000)
        assertFalse(first.isAlive); assertFalse(duplicateStop.isAlive)
        assertEquals(listOf(1L, 2L), observed)
    }
}
