package com.easysavevideo.app.library.destination

import org.junit.Assert.*
import org.junit.Test

class DirectoryPickerLifecycleTest {
    @Test fun intentionalPickerPauseDoesNotAuthorizeCopyAndResultNeedsExplicitCurrentConfirmation() {
        val gate=DirectoryPickerLifecycle();assertTrue(gate.expectedPickerPause());assertFalse(gate.confirm(true))
        assertTrue(gate.receive());assertFalse(gate.expectedPickerPause());assertFalse(gate.confirm(false))
        assertTrue(gate.canConfirm(true));assertTrue(gate.confirm(true));assertTrue(gate.confirmedOnce)
        assertFalse(gate.confirm(true));assertFalse(gate.receive())
    }
    @Test fun cancellationAbandonmentAndLateDuplicateResultsCannotAdoptAPathOrRebindConsent() {
        for(afterResult in listOf(false,true)) {
            val gate=DirectoryPickerLifecycle();if(afterResult)assertTrue(gate.receive())
            gate.cancel();assertFalse(gate.active());assertFalse(gate.receive());assertFalse(gate.confirm(true));assertFalse(gate.expectedPickerPause())
        }
    }
    @Test fun confirmedCommandCancellationIsIrreversibleAndDoesNotBecomePickerWaiting() {
        val gate=DirectoryPickerLifecycle();gate.receive();gate.confirm(true);gate.cancel()
        assertTrue(gate.confirmedOnce);assertFalse(gate.active());assertFalse(gate.expectedPickerPause());assertFalse(gate.confirm(true))
    }
    @Test fun freshProcessHasNoAuthorityToAdoptOldResultOrConfirmWithoutAnOwnedTicket() {
        val prior=FolderSelectionGate();val ticket=prior.begin(Any())!!
        val fresh=FolderSelectionGate();assertFalse(fresh.receive(ticket));assertFalse(fresh.active(ticket))
        val lifecycle=DirectoryPickerLifecycle();assertFalse(lifecycle.canConfirm(true));assertFalse(lifecycle.confirm(true))
    }
}
