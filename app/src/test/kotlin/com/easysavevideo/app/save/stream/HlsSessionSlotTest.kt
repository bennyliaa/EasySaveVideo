package com.easysavevideo.app.save.stream

import org.junit.Assert.*
import org.junit.Test

class HlsSessionSlotTest {
    @Test fun lateDismissedPreparationCannotClearNewerOne() {
        val slot = HlsSessionSlot<Any>()
        val a = Any(); val b = Any()
        slot.set(a); assertTrue(slot.clear(a))
        slot.set(b)
        // Delayed A callback runs after B starts.
        assertFalse(slot.clear(a)); assertSame(b, slot.get())
        assertTrue(slot.clear(b)); assertNull(slot.get())
    }
}
