package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.*
import org.junit.Assert.*
import org.junit.Test

class OrdinaryDestinationSelectionTest {
    @Test fun defaultAndExplicitPreferenceResolveBeforeCreation() {
        assertEquals(OrdinaryDestinationChoice.APP_LIBRARY, automaticDestination(OrdinaryDestinationPreference.APP_LIBRARY))
        assertNull(automaticDestination(OrdinaryDestinationPreference.ASK))
        assertEquals(OrdinaryDestinationChoice.ALBUM_COPY, automaticDestination(OrdinaryDestinationPreference.ALBUM_COPY))
    }
    @Test fun oneIntentRejectsRepeatedTapAndConsumesCompletionOnce() {
        val gate = OrdinaryDestinationSelection()
        val ticket = gate.begin(OrdinaryDestinationPreference.ASK)!!
        assertNull(gate.begin(OrdinaryDestinationPreference.ALBUM_COPY))
        assertTrue(gate.choose(ticket)); assertFalse(gate.choose(ticket))
        assertTrue(gate.finish(ticket)); assertFalse(gate.finish(ticket)); assertFalse(gate.busy)
    }
    @Test fun cancelBeforeChoicePreventsAdmission() {
        val gate = OrdinaryDestinationSelection(); val ticket = gate.begin(OrdinaryDestinationPreference.ASK)!!
        gate.cancel(); assertFalse(gate.choose(ticket)); assertFalse(gate.finish(ticket)); assertFalse(gate.busy)
    }
    @Test fun cancelDuringDurableWriteRejectsLateCompletionAndCannotConsumeNewIntent() {
        val gate = OrdinaryDestinationSelection(); val old = gate.begin(OrdinaryDestinationPreference.ASK)!!
        assertTrue(gate.choose(old)); gate.cancel()
        val next = gate.begin(OrdinaryDestinationPreference.APP_LIBRARY)!!
        assertFalse(gate.active(old)); assertFalse(gate.finish(old)); assertTrue(gate.active(next))
        assertTrue(gate.choose(next)); assertTrue(gate.finish(next))
    }
    @Test fun preferenceIsSnapshottedAndCompletionCannotPrecedeChoice() {
        val gate = OrdinaryDestinationSelection(); val ticket = gate.begin(OrdinaryDestinationPreference.ASK)!!
        assertFalse(gate.finish(ticket)); assertEquals(OrdinaryDestinationPreference.ASK, ticket.preference)
        assertNull(gate.begin(OrdinaryDestinationPreference.APP_LIBRARY)); assertTrue(gate.active(ticket))
    }
    @Test fun unrecognizedSettingIsNeverInterpretedAsAlbumPermission() {
        assertThrows(IllegalArgumentException::class.java) { OrdinaryDestinationPreference.valueOf("unknown") }
        assertThrows(IllegalArgumentException::class.java) { OrdinaryDestinationChoice.valueOf("ASK") }
    }
    @Test fun staleFailureCannotCancelNewSelectionOrProduceCurrentFailure() {
        val gate = OrdinaryDestinationSelection(); val old = gate.begin(OrdinaryDestinationPreference.ASK)!!
        gate.choose(old); gate.cancel()
        val fresh = gate.begin(OrdinaryDestinationPreference.ALBUM_COPY)!!
        assertFalse(gate.cancel(old)); assertTrue(gate.active(fresh))
        assertTrue(gate.choose(fresh)); assertTrue(gate.finish(fresh))
    }
}
