package com.easysavevideo.library.thumbnail

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ThumbnailNativeOwnershipTest {
    @Test fun issuedCellIsExactAndNeverRevivesAfterReleaseOrReplacement() {
        val gate = ThumbnailNativeResourceGate(); val a = Any(); val b = Any()
        assertNull(gate.ownership(a)); assertTrue(gate.claim(a))
        val first = gate.ownership(a)!!; assertTrue(first.isLive); assertNull(gate.ownership(b))
        gate.released(b); assertTrue(first.isLive)
        gate.released(a); assertFalse(first.isLive)
        assertTrue(gate.claim(a)); assertNotSame(first, gate.ownership(a)); assertFalse(first.isLive)
    }
    @Test fun forgedOrForeignCellCannotBecomeLive() {
        val gate = ThumbnailNativeResourceGate(); val other = ThumbnailNativeResourceGate(); val a = Any()
        assertTrue(gate.claim(a))
        assertFalse(ThumbnailNativeResourceGate.Ownership(gate, a).isLive)
        assertNull(other.ownership(a))
        gate.unconfirmed(); assertNull(gate.ownership(a))
        gate.released(a); assertFalse(gate.claim(a))
    }
    @Test fun ownershipReadsDoNotWaitForNativeGateMonitor() {
        val gate = ThumbnailNativeResourceGate(); val a = Any(); assertTrue(gate.claim(a))
        val cell = gate.ownership(a)!!
        synchronized(gate) {
            val result = FutureTask { gate.ownership(a) === cell && cell.isLive }
            val worker = Thread(result); worker.start()
            try { assertTrue(result.get(2, TimeUnit.SECONDS)) } finally { worker.join(2000) }
        }
    }
}
