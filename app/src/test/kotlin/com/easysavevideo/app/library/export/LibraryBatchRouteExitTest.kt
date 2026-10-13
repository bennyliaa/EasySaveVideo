package com.easysavevideo.app.library.export

import com.easysavevideo.app.library.destination.DirectoryPickerLifecycle
import com.easysavevideo.app.ui.shell.EsvDestination
import com.easysavevideo.app.ui.shell.dispatchEsvNavigation
import com.easysavevideo.app.ui.shell.navigation.RootNavigation
import com.easysavevideo.library.*
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

/** Observed-destination model using production policies; no NavController/device execution. */
class LibraryBatchRouteExitTest {
    private class Routes(var current: EsvDestination = EsvDestination.LIBRARY) {
        var exits = 0
        fun observed(destination: EsvDestination) {
            dispatchLibraryBatchRouteExit(current, destination) { exits++ }
            current = destination
        }
        fun navigate(destination: EsvDestination, succeeds: Boolean = true) {
            dispatchEsvNavigation(current.route, destination, {}, { if (succeeds) observed(it) })
        }
        fun back(popDestination: EsvDestination?, fallback: EsvDestination = EsvDestination.DISCOVER,
            fallbackSucceeds: Boolean = true) {
            if (popDestination != null) observed(popDestination) else navigate(fallback, fallbackSucceeds)
        }
    }

    @Test fun successfulBackPopCancelsOnActualLibraryExitOnce() {
        val routes = Routes(); routes.back(EsvDestination.DISCOVER)
        assertEquals(1, routes.exits); assertEquals(EsvDestination.DISCOVER, routes.current)
        routes.observed(EsvDestination.DISCOVER); routes.navigate(EsvDestination.SETTINGS)
        assertEquals(1, routes.exits)
    }

    @Test fun failedPopAloneDoesNotCancelAndOnlySuccessfulFallbackExitDoes() {
        val routes = Routes(); routes.back(null, fallbackSucceeds = false)
        assertEquals(EsvDestination.LIBRARY, routes.current); assertEquals(0, routes.exits)
        routes.back(null, fallback = EsvDestination.LIBRARY)
        assertEquals(0, routes.exits)
        routes.back(null)
        assertEquals(1, routes.exits); assertEquals(EsvDestination.DISCOVER, routes.current)
    }

    @Test fun rootSwitchAndEditorRouteUseSameActualExitPolicy() {
        for (target in EsvDestination.entries.filter { it != EsvDestination.LIBRARY }) {
            val routes = Routes(); routes.navigate(target, succeeds = false); assertEquals(0, routes.exits)
            routes.navigate(target); assertEquals(target.name, 1, routes.exits)
        }
        val routes = Routes()
        assertTrue(RootNavigation.navigate(EsvDestination.TASKS.route, { routes.current.route }, { false }) {
            routes.observed(EsvDestination.TASKS)
        })
        assertEquals(1, routes.exits)
    }

    @Test fun sameLibraryNavigationSuccessfulSameRoutePopAndLocalDismissalDoNotCancel() {
        val routes = Routes()
        routes.navigate(EsvDestination.LIBRARY)
        routes.back(EsvDestination.LIBRARY)
        // Detail/dialog dismissal and folder navigation keep the same observed destination.
        repeat(3) { routes.observed(EsvDestination.LIBRARY) }
        assertEquals(0, routes.exits)
    }

    @Test fun expectedPickerPauseIsNotARouteExitAndDoesNotAuthorizeCopy() {
        val routes = Routes(); val picker = DirectoryPickerLifecycle()
        assertTrue(picker.expectedPickerPause()); assertFalse(picker.confirm(true))
        routes.observed(EsvDestination.LIBRARY)
        picker.receive(); routes.observed(EsvDestination.LIBRARY)
        assertEquals(0, routes.exits); assertFalse(picker.confirmedOnce)
        routes.navigate(EsvDestination.EDITOR); assertEquals(1, routes.exits)
    }

    @Test fun enteringLibraryOrLeavingOtherRoutesDoesNotSignalLibraryBatchCancellation() {
        val routes = Routes(EsvDestination.EDITOR)
        routes.observed(EsvDestination.DISCOVER); routes.observed(EsvDestination.LIBRARY)
        assertEquals(0, routes.exits)
        routes.observed(EsvDestination.RECORD); routes.observed(EsvDestination.LIBRARY)
        routes.observed(EsvDestination.TASKS); assertEquals(2, routes.exits)
    }

    @Test fun actualExitDuringDirectoryCopyKeepsCompletedItemAndCancelsUnstartedRows() {
        val items = listOf(media("one"), media("two"), media("three"))
        val plan = DirectoryBatchPlan.create("selected-folder", items.map { it.readRef() })
        val journal = Journal(); var cancelled = false; var copies = 0
        val rows = DirectoryBatchCopy(plan).run(journal, { id -> items.single { it.itemId == id } },
            { true }, { cancelled }) { request ->
            copies++
            dispatchLibraryBatchRouteExit(EsvDestination.LIBRARY, EsvDestination.DISCOVER) { cancelled = true }
            verified(request)
        }
        assertEquals(1, copies); assertEquals(1, journal.started.size)
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY, BatchTransferStatus.CANCELLED_BEFORE_START,
            BatchTransferStatus.CANCELLED_BEFORE_START), rows.map { it.status })
        assertEquals(items.map { it.readRef() }, rows.map { it.ref })
        assertEquals(BatchTransferStatus.VERIFIED_COPY, journal.outcome(plan.requests.first().operationId))
    }

    @Test fun actualExitDuringGalleryCopyAlsoKeepsCompletedItemAndSkipsRemainingItems() {
        val items = listOf(media("one"), media("two")); var cancelled = false; var copies = 0
        val rows = BatchGalleryCopy(items.map { it.readRef() }).run({ id -> items.single { it.itemId == id } },
            { cancelled }) { request ->
            copies++
            dispatchLibraryBatchRouteExit(EsvDestination.LIBRARY, EsvDestination.EDITOR) { cancelled = true }
            verified(request)
        }
        assertEquals(1, copies)
        assertEquals(listOf(BatchTransferStatus.VERIFIED_COPY, BatchTransferStatus.CANCELLED_BEFORE_START), rows.map { it.status })
    }

    private class Journal : DirectoryBatchJournal {
        val started = mutableSetOf<String>()
        private val outcomes = mutableMapOf<String, BatchTransferStatus>()
        private var stopped = false
        override fun admit(plan: DirectoryBatchPlan) = Unit
        override fun stopped(batchId: String) = stopped
        override fun stop(batchId: String) { stopped = true }
        override fun outcome(operationId: String) = outcomes[operationId]
        override fun started(operationId: String) = operationId in started
        override fun start(plan: DirectoryBatchPlan, operationId: String) = !stopped && started.add(operationId)
        override fun finish(plan: DirectoryBatchPlan, operationId: String, status: BatchTransferStatus) { outcomes[operationId] = status }
        override fun complete(batchId: String) = Unit
    }
    private fun media(id: String) = MediaItem(id, 1, 1, null, id, 1, MediaDateKind.SAVED, 10, null, null, null)
    private fun verified(request: ExportRequest) = ExportRecord(request, phase = ExportPhase.SUCCEEDED,
        output = ExportOutput(request.operationId, request.destination.kind), evidence = ExportEvidence(10, "a".repeat(64)),
        verification = ExportVerification.DESTINATION_VERIFIED)
}
