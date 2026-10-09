package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.*
import org.junit.Assert.*
import org.junit.Test

class FolderSelectionGateTest {
    @Test fun duplicateResultsAndCommitsAreOneShot() {
        val gate = FolderSelectionGate(); val owner = Any(); val ticket = gate.begin(owner)!!
        assertNull(gate.begin(owner)); assertTrue(gate.receive(ticket)); assertFalse(gate.receive(ticket))
        var calls = 0
        assertTrue(gate.commit(ticket) { calls++ }); assertFalse(gate.commit(ticket) { calls++ }); assertEquals(1, calls)
    }
    @Test fun commitBeforeResultDoesNotChangePreference() {
        val gate = FolderSelectionGate(); val ticket = gate.begin(Any())!!
        assertFalse(gate.commit(ticket) { fail("No result") }); assertTrue(gate.active(ticket))
    }
    @Test fun cancelledResultCannotSelectWrongFolderOrCancelNewRequest() {
        val gate = FolderSelectionGate(); val first = gate.begin(Any())!!
        assertTrue(gate.receive(first)); assertTrue(gate.cancel(first)); val next = gate.begin(Any())!!
        assertFalse(gate.commit(first) { fail("Stale folder") }); assertFalse(gate.receive(first)); assertFalse(gate.cancel(first))
        assertTrue(gate.active(next)); assertTrue(gate.receive(next)); assertTrue(gate.commit(next) {})
    }
    @Test fun rotatedOwnerDropsOldResultAndCannotDetachNewOwner() {
        val gate = FolderSelectionGate(); val oldOwner = Any(); val old = gate.begin(oldOwner)!!
        assertTrue(gate.detach(oldOwner)); val newOwner = Any(); val fresh = gate.begin(newOwner)!!
        assertFalse(gate.detach(oldOwner)); assertFalse(gate.receive(old)); assertFalse(gate.commit(old) { fail("Rotated") })
        assertTrue(gate.active(fresh))
    }
    @Test fun failedDurableWriteRetainsExactTicketForFailureHandling() {
        val gate = FolderSelectionGate(); val ticket = gate.begin(Any())!!; gate.receive(ticket)
        assertThrows(IllegalStateException::class.java) { gate.commit(ticket) { error("IO") } }
        assertTrue(gate.active(ticket)); assertTrue(gate.cancel(ticket))
    }
    @Test fun creationSnapshotsRememberedFolderAndDoesNotChangeOnNewPreference() {
        val gate = OrdinaryDestinationSelection()
        val ticket = gate.begin(OrdinaryDestinationPreference.ASK, "first-folder")!!
        assertEquals("first-folder", ticket.folderGrantId)
        assertNull(gate.begin(OrdinaryDestinationPreference.FOLDER_COPY, "second-folder"))
        assertEquals(OrdinaryDestinationChoice.FOLDER_COPY, automaticDestination(OrdinaryDestinationPreference.FOLDER_COPY))
        gate.cancel()
        assertEquals("second-folder", gate.begin(OrdinaryDestinationPreference.FOLDER_COPY, "second-folder")!!.folderGrantId)
    }
    @Test fun copyCancellationOnlyOffersBeforeFinalDecision() {
        val status = OrdinaryDestinationStatus("creation", true, OrdinaryDestinationChoice.FOLDER_COPY, null)
        assertTrue(ordinaryCopyCanCancel(status))
        for (phase in com.easysavevideo.library.export.ExportPhase.entries) {
            assertEquals(phase in setOf(com.easysavevideo.library.export.ExportPhase.PREPARED,
                com.easysavevideo.library.export.ExportPhase.CREATE_INTENT, com.easysavevideo.library.export.ExportPhase.COPYING,
                com.easysavevideo.library.export.ExportPhase.VERIFYING), ordinaryCopyCanCancel(status.copy(copyPhase = phase)))
        }
        assertFalse(ordinaryCopyCanCancel(status.copy(internalSaved = false)))
        assertFalse(ordinaryCopyCanCancel(status.copy(choice = OrdinaryDestinationChoice.APP_LIBRARY)))
    }
    @Test fun settingsRefreshCannotRevertNewerChoice() {
        val order = FolderSettingsOrder()
        val initial = OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY, "old", 1)
        val invalidated = OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.APP_LIBRARY, null, 2)
        val replacement = OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY, "new", 3)
        assertTrue(order.accept(initial)); assertTrue(order.accept(invalidated)); assertTrue(order.accept(replacement))
        assertFalse(order.accept(invalidated)); assertFalse(order.accept(initial)); assertTrue(order.accept(replacement))
    }
    @Test fun sameRevisionCannotSubstituteAnotherFolder() {
        val order = FolderSettingsOrder()
        assertTrue(order.accept(OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY, "first", 5)))
        assertFalse(order.accept(OrdinaryDestinationSelectionSnapshot(OrdinaryDestinationPreference.FOLDER_COPY, "other", 5)))
    }
    @Test fun pickerCounterIsDurableBeforeLaunchAndNeverWraps() {
        val writes = mutableListOf<Int>()
        assertEquals(FolderPickerRequestCodes.FIRST, FolderPickerRequestCodes.consume(FolderPickerRequestCodes.FIRST) { writes += it; true })
        assertEquals(listOf(FolderPickerRequestCodes.FIRST + 1), writes)
        assertEquals(FolderPickerRequestCodes.LAST, FolderPickerRequestCodes.consume(FolderPickerRequestCodes.LAST) { true })
        assertThrows(FolderPickerFailure::class.java) { FolderPickerRequestCodes.consume(FolderPickerRequestCodes.LAST + 1) { fail("Must not wrap"); true } }
        assertThrows(IllegalStateException::class.java) { FolderPickerRequestCodes.consume(FolderPickerRequestCodes.FIRST) { false } }
    }
    @Test fun pickerCounterRangeCannotConsumeOtherSubsystemResults() {
        for (code in listOf(-1, 0, 41_320, 41_321, 41_999, 65_535, 65_536, Int.MAX_VALUE))
            assertFalse(FolderPickerRequestCodes.owns(code))
        assertTrue(FolderPickerRequestCodes.owns(FolderPickerRequestCodes.FIRST))
        assertTrue(FolderPickerRequestCodes.owns(FolderPickerRequestCodes.LAST))
    }
}
