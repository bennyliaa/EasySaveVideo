package com.easysavevideo.app.browser.privacy

import com.easysavevideo.app.browser.SystemBrowserHost
import org.junit.Assert.*
import org.junit.Test

/** Tests the RAM-only algorithm, not Android navigation, renderer lifecycle, or POST restoration. */
class PrivateNavigationTest {
    @Test fun freshHistoryHasNoNavigationTargets() {
        val h = SystemBrowserHost.PrivateNavigation()
        assertNull(h.target(0)); assertNull(h.target(-1)); assertFalse(h.canBack()); assertFalse(h.canForward())
    }
    @Test fun acceptedNavigationAndBackForwardUseExactIndexedAddresses() {
        val h = SystemBrowserHost.PrivateNavigation()
        h.committed("https://fixture.invalid/a", null); h.committed("https://fixture.invalid/b", null)
        assertEquals(0 to "https://fixture.invalid/a", h.target(-1))
        h.committed("https://fixture.invalid/a", 0)
        assertFalse(h.canBack()); assertTrue(h.canForward()); assertEquals(1 to "https://fixture.invalid/b", h.target(1))
    }
    @Test fun newBranchDropsOnlyForwardHistory() {
        val h = SystemBrowserHost.PrivateNavigation()
        h.committed("https://fixture.invalid/a", null); h.committed("https://fixture.invalid/b", null)
        h.committed("https://fixture.invalid/a", 0); h.committed("https://fixture.invalid/c", null)
        assertEquals(listOf("https://fixture.invalid/a", "https://fixture.invalid/c"), h.addresses)
        assertFalse(h.canForward())
    }
    @Test fun redirectedHistoryTargetReplacesExactSlot() {
        val h = SystemBrowserHost.PrivateNavigation()
        h.committed("https://fixture.invalid/a", null); h.committed("https://fixture.invalid/b", null)
        h.committed("https://fixture.invalid/redirect", 0)
        assertEquals("https://fixture.invalid/redirect", h.target(0)?.second)
        assertEquals("https://fixture.invalid/b", h.target(1)?.second)
    }
    @Test fun ramHistoryIsBoundedWithoutChangingNewestTarget() {
        val h = SystemBrowserHost.PrivateNavigation()
        repeat(200) { h.committed("https://fixture.invalid/$it", null) }
        assertEquals(128, h.addresses.size); assertEquals(127, h.index)
        assertEquals("https://fixture.invalid/199", h.target(0)?.second)
        assertEquals("https://fixture.invalid/72", h.target(-127)?.second); assertNull(h.target(-128))
    }
}
