package com.easysavevideo.app.save

import com.easysavevideo.core.model.SaveSettings
import org.junit.Assert.*
import org.junit.Test

class SaveSettingsOwnerTest {
    @Test fun defaultsAreUnknownAndConcurrencyCannotConfirmWifi() {
        val owner = SaveSettingsOwner()
        assertEquals(SaveSettings(), owner.snapshot())
        assertFalse(owner.wifiKnown); assertFalse(owner.concurrencyKnown)
        assertNull(owner.updateConcurrency(2))
        assertFalse(owner.wifiKnown); assertFalse(owner.concurrencyKnown)
        assertEquals(SaveSettings(), owner.snapshot())
    }
    @Test fun successfulLoadInitializesBothFields() {
        for (wifi in listOf(false, true)) for (count in 1..2) {
            val owner = SaveSettingsOwner()
            assertEquals(SaveSettings(wifi, count), owner.initialize(SaveSettings(wifi, count)))
            assertTrue(owner.wifiKnown); assertTrue(owner.concurrencyKnown)
        }
    }
    @Test fun earlyWifiEditDoesNotClaimConcurrencyAndLoadedConcurrencyStillArrives() {
        for (earlyWifi in listOf(false, true)) for (loadedCount in 1..2) {
            val owner = SaveSettingsOwner()
            assertEquals(SaveSettings(earlyWifi, 1), owner.updateWifiOnly(earlyWifi))
            assertTrue(owner.wifiKnown); assertFalse(owner.concurrencyKnown)
            assertNull(owner.updateConcurrency(2))
            assertEquals(SaveSettings(earlyWifi, loadedCount), owner.initialize(SaveSettings(!earlyWifi, loadedCount)))
        }
    }
    @Test fun failedOrTimedOutLoadNeverEnablesConcurrencyEvenAfterExplicitWifiEdit() {
        // Failed startup intentionally never calls initialize: an error fallback is not a loaded preference.
        val owner = SaveSettingsOwner()
        owner.updateWifiOnly(true)
        assertNull(owner.updateConcurrency(2))
        assertEquals(SaveSettings(true, 1), owner.snapshot())
        assertFalse(owner.concurrencyKnown)
        assertEquals(SaveSettings(true, 2), owner.initialize(SaveSettings(false, 2)))
    }
    @Test fun recreationCannotRollBackLoadedOrEditedAppChoices() {
        val owner = SaveSettingsOwner()
        owner.initialize(SaveSettings(true, 1))
        assertEquals(SaveSettings(true, 2), owner.updateConcurrency(2))
        assertEquals(SaveSettings(false, 2), owner.updateWifiOnly(false))
        // Another Activity can load an old disk snapshot after a write failed or has not completed.
        repeat(3) { assertEquals(SaveSettings(false, 2), owner.initialize(SaveSettings(true, 1))) }
        assertEquals(SaveSettings(false, 1), owner.updateConcurrency(1))
        assertEquals(SaveSettings(false, 1), owner.initialize(SaveSettings(true, 2)))
    }
    @Test fun lowerLimitChangesOnlyConcurrencyAndWifiEditsPreserveLatestLimit() {
        val owner = SaveSettingsOwner()
        owner.initialize(SaveSettings(true, 2))
        assertEquals(SaveSettings(true, 1), owner.updateConcurrency(1))
        assertEquals(SaveSettings(false, 1), owner.updateWifiOnly(false))
        assertEquals(SaveSettings(false, 2), owner.updateConcurrency(2))
        assertEquals(SaveSettings(true, 2), owner.updateWifiOnly(true))
    }
    @Test fun invalidRuntimeCountsAreRejectedWithoutChangingEitherField() {
        val owner = SaveSettingsOwner()
        owner.initialize(SaveSettings(true, 2))
        for (count in listOf(Int.MIN_VALUE, -1, 0, 3, Int.MAX_VALUE)) {
            assertNull(owner.updateConcurrency(count))
            assertEquals(SaveSettings(true, 2), owner.snapshot())
        }
    }
}
