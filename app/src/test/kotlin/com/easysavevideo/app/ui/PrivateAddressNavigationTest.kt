package com.easysavevideo.app.ui

import com.easysavevideo.core.model.BrowserTab
import com.easysavevideo.core.model.BrowserContextRef
import org.junit.Assert.*
import org.junit.Test

class PrivateAddressNavigationTest {
    private val privateTab = BrowserTab(BrowserContextRef("private-tab", "private-session"), incognito = true)
    private val ordinaryTab = BrowserTab(BrowserContextRef("ordinary-tab", "ordinary-session"), incognito = false)
    @Test fun oldPrivateImeCannotNavigateOrdinaryAfterLastPrivateTabClosed() {
        assertFalse(canNavigateBrowserAddress(privateTab, ordinaryTab, 1, 2, true))
    }
    @Test fun privateAwayAndBackCannotReviveOldGoCallback() {
        assertFalse(canNavigateBrowserAddress(privateTab, privateTab, 1, 3, true))
    }
    @Test fun documentEpochChangeRejectsQueuedAddress() {
        assertFalse(canNavigateBrowserAddress(privateTab, privateTab.copy(epoch = privateTab.epoch + 1), 1, 1, true))
    }
    @Test fun partitionMismatchCannotBorrowEvenForgedSameIdentity() {
        assertFalse(canNavigateBrowserAddress(privateTab, privateTab.copy(incognito = false), 1, 1, true))
    }
    @Test fun currentOrdinaryAndPrivateUserNavigationRemainAllowed() {
        assertTrue(canNavigateBrowserAddress(ordinaryTab, ordinaryTab, 1, 1, true))
        assertTrue(canNavigateBrowserAddress(privateTab, privateTab, 1, 1, true))
    }
    @Test fun backgroundOrMissingTabCannotNavigate() {
        assertFalse(canNavigateBrowserAddress(privateTab, privateTab, 1, 1, false))
        assertFalse(canNavigateBrowserAddress(privateTab, null, 1, 1, true))
    }
}
