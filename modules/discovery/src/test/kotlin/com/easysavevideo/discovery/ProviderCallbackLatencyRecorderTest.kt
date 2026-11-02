package com.easysavevideo.discovery

import org.junit.Assert.*
import org.junit.Test

class ProviderCallbackLatencyRecorderTest {
    @Test fun structurallyEqualOperationCannotCompleteExactOpaqueIdentity() {
        var reads = 0
        val recorder = ProviderCallbackLatencyRecorder(true, ProviderLatencyTickClock { (++reads).toLong() })
        val session = recorder.beginSession()
        val original = operation()
        val equalCopy = original.copy()
        assertEquals(original, equalCopy)
        assertNotSame(original, equalCopy)
        recorder.started(session, original, "private-source", 0)
        recorder.callback(equalCopy, ProviderCallbackTerminal.SUCCESS_REPORTED)
        assertEquals(1, reads)
        assertEquals(1, recorder.snapshot().pendingAttempts)
        recorder.callback(original, ProviderCallbackTerminal.SUCCESS_REPORTED)
        recorder.callback(original, ProviderCallbackTerminal.FAILURE_REPORTED)
        assertEquals(2, reads)
        assertEquals(1L, recorder.snapshot().callbacksObserved)
        assertEquals(1L, recorder.snapshot().samples.single().elapsedNanos)
    }

    @Test fun sourceAliasTableAndPendingIdentitiesHaveFiniteIndependentBounds() {
        var reads = 0
        val recorder = ProviderCallbackLatencyRecorder(true, ProviderLatencyTickClock { (++reads).toLong() }, 64)
        val session = recorder.beginSession()
        val identities = (1..33).map { Any() }
        identities.forEachIndexed { index, identity -> recorder.started(session, identity, "source-$index", 0) }
        assertEquals(32, recorder.snapshot().pendingAttempts)
        assertEquals(32, reads)
        assertEquals(1L, recorder.snapshot().droppedAttempts)
        identities.forEach(recorder::cancelled)
        recorder.started(session, Any(), "another-source", 0)
        assertEquals(2L, recorder.snapshot().droppedAttempts)
        assertEquals(32L, recorder.snapshot().cancelledUnmeasured)
        assertEquals((1..32).toList(), recorder.snapshot().samples.map { it.sourceAlias }.sorted())
    }

    @Test fun endingSessionDiscardsRawBindingsAndRejectsNewStartsUntilExplicitNewSession() {
        var reads = 0
        val recorder = ProviderCallbackLatencyRecorder(true, ProviderLatencyTickClock { (++reads).toLong() })
        val session = recorder.beginSession()
        val operation = Any()
        recorder.started(session, operation, "private-source", 0)
        recorder.endSession(session)
        recorder.started(session, Any(), "private-new-source", 0)
        recorder.callback(operation, ProviderCallbackTerminal.SUCCESS_REPORTED)
        assertEquals(1, reads)
        assertEquals(0, recorder.snapshot().pendingAttempts)
        assertEquals(1L, recorder.snapshot().cancelledUnmeasured)
        assertNull(recorder.snapshot().samples.single().elapsedNanos)
        val replacement = recorder.beginSession()
        recorder.started(replacement, Any(), "new-source", 0)
        recorder.endSession(session)
        assertEquals(1, recorder.snapshot().pendingAttempts)
        assertEquals(2, reads)
        assertTrue(recorder.snapshot().samples.isEmpty())
    }

    @Test fun retainedSnapshotsAreDetachedFromTheRecorderRing() {
        val recorder = ProviderCallbackLatencyRecorder(true, ProviderLatencyTickClock { 0 }, 2)
        val session = recorder.beginSession()
        repeat(2) {
            val operation = Any()
            recorder.started(session, operation, "source", 0)
            recorder.callback(operation, ProviderCallbackTerminal.SUCCESS_REPORTED)
        }
        val snapshot = recorder.snapshot()
        @Suppress("UNCHECKED_CAST")
        (snapshot.samples as MutableList<ProviderCallbackLatencySample>).clear()
        assertEquals(2, recorder.snapshot().samples.size)
    }

    @Test fun capacityMustBeExplicitlyBounded() {
        for (capacity in listOf(0, -1, 1025, Int.MAX_VALUE)) {
            try {
                ProviderCallbackLatencyRecorder(capacity = capacity)
                fail("Unbounded capacity was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
