package com.easysavevideo.app.library

import com.easysavevideo.app.library.export.UnverifiedExportView
import com.easysavevideo.library.MediaReadRef
import org.junit.Assert.*
import org.junit.Test

class LibraryUnverifiedCleanupTest {
    private val first = UnverifiedExportView("export-a", MediaReadRef("item-a", 1, 1), "Video A · export-a", true, "Needs review")
    private val second = UnverifiedExportView("export-b", MediaReadRef("item-b", 1, 1), "Video B · export-b", true, "Needs review")
    @Test fun independentlyListedEarlierOperationKeepsItsExactCleanupIdentity() {
        assertTrue(unverifiedCleanupStillCurrent(first, listOf(first, second), true, false))
        assertTrue(unverifiedCleanupStillCurrent(second, listOf(first, second), true, false))
    }
    @Test fun laterExportNeverSubstitutesItsIdentityForEarlierConfirmation() {
        assertFalse(unverifiedCleanupStillCurrent(first, listOf(second), true, false))
    }
    @Test fun changedSourceOrDescriptorRequiresFreshConfirmation() {
        assertFalse(unverifiedCleanupStillCurrent(first, listOf(first.copy(source = MediaReadRef("item-a", 2, 1))), true, false))
        assertFalse(unverifiedCleanupStillCurrent(first, listOf(first.copy(label = "Renamed A")), true, false))
    }
    @Test fun backgroundOrBusyCannotSubmitCleanup() {
        assertFalse(unverifiedCleanupStillCurrent(first, listOf(first), false, false))
        assertFalse(unverifiedCleanupStillCurrent(first, listOf(first), true, true))
    }
    @Test fun lostProcessGrantRemainsVisibleButIsNotCleanupAuthority() {
        val manual = first.copy(canAttemptCleanup = false, status = "Check the original provider manually")
        assertFalse(unverifiedCleanupStillCurrent(manual, listOf(manual), true, false))
        assertFalse(unverifiedCleanupStillCurrent(first, listOf(manual), true, false))
    }
}
