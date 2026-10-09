package com.easysavevideo.app.recording

import org.junit.Assert.*
import org.junit.Test

class OneShotGrantCellTest {
    @Test fun claimIsExactAndOnce() {
        var now = 1L
        val cell = OneShotGrantCell<Any>({ now }, 30)
        val grant = Any()
        assertTrue(cell.offer("one", grant))
        assertFalse(cell.offer("two", Any()))
        assertNull(cell.take("two"))
        assertSame(grant, cell.take("one"))
        assertNull(cell.take("one"))
    }
    @Test fun expiresAtBoundaryAndDoesNotRefresh() {
        var now = 1L
        val cell = OneShotGrantCell<Any>({ now }, 30)
        assertTrue(cell.offer("one", Any()))
        now = 30; assertTrue(cell.contains("one"))
        now = 31; assertNull(cell.take("one"))
        assertTrue(cell.offer("two", Any()))
    }
    @Test fun oldDiscardCannotRevokeNewGrant() {
        val cell = OneShotGrantCell<Any>({ 1 }, 30)
        assertTrue(cell.offer("one", Any())); cell.take("one")
        val second = Any(); assertTrue(cell.offer("two", second)); cell.discard("one")
        assertSame(second, cell.take("two"))
    }
    @Test fun clockRollbackInvalidatesGrant() {
        var now = 10L
        val cell = OneShotGrantCell<Any>({ now }, 30)
        cell.offer("one", Any()); now = 9
        assertNull(cell.take("one"))
    }
    @Test fun processReplacementDoesNotRehydrateGrant() {
        val old = OneShotGrantCell<Any>({ 1 }, 30); old.offer("one", Any())
        assertNull(OneShotGrantCell<Any>({ 1 }, 30).take("one"))
    }
}
