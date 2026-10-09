package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryExportSelectionTest {
    private val item = MediaItem("one", 1, 1, null, "One", 0, MediaDateKind.SAVED, 1024, 1000, 16, 9)

    @Test fun exactForegroundSelectionAllowsOpeningChoices() {
        assertTrue(exportSelectionIsCurrent(item.readRef(), listOf(item), true))
    }
    @Test fun backgroundOrMissingPageRejectsOldChoice() {
        assertFalse(exportSelectionIsCurrent(item.readRef(), listOf(item), false))
        assertFalse(exportSelectionIsCurrent(item.readRef(), emptyList(), true))
    }
    @Test fun renamedMovedOrReplacedRepresentationRejectsOldChoice() {
        assertFalse(exportSelectionIsCurrent(item.readRef(), listOf(item.copy(revision = 2)), true))
        assertFalse(exportSelectionIsCurrent(item.readRef(), listOf(item.copy(revision = 2, folderId = "folder")), true))
        assertFalse(exportSelectionIsCurrent(item.readRef(), listOf(item.copy(representationGeneration = 2)), true))
    }
    @Test fun pendingAndDeletedItemsRejectOldChoice() {
        assertFalse(exportSelectionIsCurrent(item.readRef(), listOf(item.copy(lifecycle = MediaLifecycle.DELETE_PENDING)), true))
        assertFalse(exportSelectionIsCurrent(item.readRef(), listOf(item.copy(lifecycle = MediaLifecycle.DELETED)), true))
    }
}
