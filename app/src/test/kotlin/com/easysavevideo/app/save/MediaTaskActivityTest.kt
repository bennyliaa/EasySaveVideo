package com.easysavevideo.app.save

import org.junit.Assert.*
import org.junit.Test

class MediaTaskActivityTest {
    @Test fun noScreenOrHistoricalTaskCountCanDeclareActivity() {
        assertFalse(MediaTaskActivitySignals().snapshot().active)
    }

    @Test fun pendingCommandsKeepPriorityUntilAllCommandsExit() {
        val tracker = MediaTaskActivityTracker()
        tracker.update(MediaTaskActivitySignals(pendingCommandCount = 2))
        assertTrue(tracker.snapshot.saveActive)
        tracker.update(MediaTaskActivitySignals(pendingCommandCount = 1))
        assertTrue(tracker.snapshot.active)
        tracker.update(MediaTaskActivitySignals())
        assertFalse(tracker.snapshot.active)
    }

    @Test fun hlsPrepareVariantSelectionAndCleanupAreContinuousOwners() {
        val tracker = MediaTaskActivityTracker()
        listOf(
            MediaTaskActivitySignals(executing = true, preparingStream = true),
            MediaTaskActivitySignals(preparingStream = true, selectedStream = true),
            MediaTaskActivitySignals(selectedStream = true),
            MediaTaskActivitySignals(executing = true),
            MediaTaskActivitySignals(resourcesDraining = true),
        ).forEach { tracker.update(it); assertTrue(tracker.snapshot.saveActive) }
        tracker.update(MediaTaskActivitySignals())
        assertFalse(tracker.snapshot.active)
    }

    @Test fun rejectedPreparationBecomesIdleOnlyAfterReservationAndPendingWorkRelease() {
        val tracker = MediaTaskActivityTracker()
        tracker.update(MediaTaskActivitySignals(executing = true, preparingStream = true, pendingCommandCount = 1))
        tracker.update(MediaTaskActivitySignals(executing = true, pendingCommandCount = 1))
        assertTrue(tracker.snapshot.active)
        tracker.update(MediaTaskActivitySignals(pendingCommandCount = 1))
        assertTrue(tracker.snapshot.active)
        tracker.update(MediaTaskActivitySignals())
        assertFalse(tracker.snapshot.active)
    }

    @Test fun consentPausedRecordingFinalizingAndServiceShutdownKeepRecordingPriority() {
        listOf(
            MediaTaskActivitySignals(pendingRecording = true),
            MediaTaskActivitySignals(recordingOwner = true),
            MediaTaskActivitySignals(executing = true, recordingOwner = true),
            MediaTaskActivitySignals(recordingOwner = true, resourcesDraining = true),
            MediaTaskActivitySignals(recordingService = true),
            MediaTaskActivitySignals(segmentRecording = true),
        ).forEach { assertTrue(it.snapshot().recordingActive); assertTrue(it.snapshot().active) }
    }

    @Test fun finishingOneOwnerCannotReleaseAnotherOrAnUnconfirmedNativeDrain() {
        val tracker = MediaTaskActivityTracker()
        tracker.update(MediaTaskActivitySignals(executing = true, recordingOwner = true, pendingCommandCount = 1))
        tracker.update(MediaTaskActivitySignals(recordingOwner = true, pendingCommandCount = 1))
        assertTrue(tracker.snapshot.recordingActive)
        tracker.update(MediaTaskActivitySignals(resourcesDraining = true))
        assertTrue(tracker.snapshot.saveActive)
        assertTrue(tracker.snapshot.resourcesDraining)
    }

    @Test fun recreatedScreensGetReplayWithoutChangingApplicationOwnership() {
        val tracker = MediaTaskActivityTracker()
        val first = mutableListOf<Boolean>()
        val one = tracker.addListener { first += it.active }
        tracker.update(MediaTaskActivitySignals(executing = true))
        one.close(); one.close()
        val second = mutableListOf<Boolean>()
        val two = tracker.addListener { second += it.active }
        assertEquals(listOf(false, true), first)
        assertEquals(listOf(true), second)
        tracker.update(MediaTaskActivitySignals(resourcesDraining = true))
        tracker.update(MediaTaskActivitySignals())
        assertEquals(listOf(true, true, false), second)
        assertEquals(listOf(false, true), first)
        two.close()
    }

    @Test fun closingOneObserverDoesNotStopAnother() {
        val tracker = MediaTaskActivityTracker()
        var delivered = 0
        val first = tracker.addListener { }
        tracker.addListener { delivered++ }
        first.close()
        tracker.update(MediaTaskActivitySignals(recordingService = true))
        assertEquals(2, delivered)
    }

    @Test fun throwingObserversCannotBreakMediaUpdatesOrOtherObservers() {
        val tracker = MediaTaskActivityTracker()
        tracker.addListener { throw IllegalStateException() }
        var latest = false
        tracker.addListener { latest = it.active }
        tracker.update(MediaTaskActivitySignals(executing = true))
        assertTrue(latest)
    }

    @Test fun reentrantUpdateCannotDeliverAnOlderSnapshotAfterTheNewerOne() {
        val tracker = MediaTaskActivityTracker()
        var nested = false
        tracker.addListener {
            if (it.saveActive && !nested) {
                nested = true
                tracker.update(MediaTaskActivitySignals(recordingOwner = true))
            }
        }
        val snapshots = mutableListOf<MediaTaskActivitySnapshot>()
        tracker.addListener { snapshots += it }
        tracker.update(MediaTaskActivitySignals(executing = true))
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.last().recordingActive)
        assertFalse(snapshots.last().saveActive)
    }

    @Test fun unchangedFactsDoNotCreateAnotherDemandEdge() {
        val tracker = MediaTaskActivityTracker()
        var delivered = 0
        tracker.addListener { delivered++ }
        repeat(20) { tracker.update(MediaTaskActivitySignals(executing = true)) }
        assertEquals(2, delivered)
    }

    @Test fun restartUsesFreshProcessFactsRatherThanOldActivityOrWaitingRows() {
        val old = MediaTaskActivityTracker()
        old.update(MediaTaskActivitySignals(recordingOwner = true, resourcesDraining = true))
        val restarted = MediaTaskActivityTracker()
        restarted.update(MediaTaskActivitySignals(pendingCommandCount = 1))
        assertTrue(restarted.snapshot.active)
        restarted.update(MediaTaskActivitySignals())
        assertFalse(restarted.snapshot.active)
        assertTrue(old.snapshot.active)
    }
}
