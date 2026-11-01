package com.easysavevideo.app.save

import com.easysavevideo.core.model.SaveSettings
import com.easysavevideo.core.model.TaskMethod
import org.junit.Assert.*
import org.junit.Test

class SaveMethodSettingsOwnerTest {
    private val methods = listOf(TaskMethod.DIRECT, TaskMethod.STREAM, TaskMethod.RECORD)

    @Test fun failedUnfinishedAndTimedOutLoadsCannotGrantNewTaskPermissionOrWriteDefaults() {
        val owner = SaveSettingsOwner()
        assertFalse(owner.methodsKnown); assertNull(owner.acceptedMethodSettings())
        for (method in methods) {
            assertFalse(owner.isMethodEnabled(method))
            assertNull(owner.updateMethodEnabled(method, true))
            assertNull(owner.updateMethodEnabled(method, false))
        }
        owner.updateWifiOnly(true)
        assertTrue(owner.wifiKnown); assertFalse(owner.concurrencyKnown); assertFalse(owner.methodsKnown)
        assertNull(owner.acceptedMethodSettings())
        assertTrue(owner.isMethodEnabled(TaskMethod.BROWSER)); assertTrue(owner.isMethodEnabled(TaskMethod.EDIT))
    }

    @Test fun allEightLoadedCombinationsReachTheCurrentAdmissionPolicy() {
        for (direct in listOf(false, true)) for (stream in listOf(false, true)) for (record in listOf(false, true)) {
            val owner = SaveSettingsOwner()
            val loaded = SaveSettings(true, 2, direct, stream, record)
            assertEquals(loaded, owner.initialize(loaded)); assertTrue(owner.methodsKnown)
            assertEquals(loaded, owner.acceptedMethodSettings())
            for (method in methods) assertEquals(loaded.isMethodEnabled(method), owner.isMethodEnabled(method))
        }
    }

    @Test fun eachEditTouchesOneMethodWithoutChangingNetworkOrConcurrency() {
        val owner = SaveSettingsOwner()
        owner.initialize(SaveSettings(true, 2))
        var expected = SaveSettings(true, 2)
        for (method in methods) {
            expected = expected.withMethodEnabled(method, false)
            assertEquals(expected, owner.updateMethodEnabled(method, false))
            assertEquals(expected, owner.acceptedMethodSettings())
            assertTrue(owner.wifiKnown); assertTrue(owner.concurrencyKnown)
        }
        assertTrue(owner.isMethodEnabled(TaskMethod.BROWSER)); assertTrue(owner.isMethodEnabled(TaskMethod.EDIT))
        for (method in listOf(TaskMethod.BROWSER, TaskMethod.EDIT)) assertNull(owner.updateMethodEnabled(method, false))
        assertEquals(expected, owner.snapshot())
    }

    @Test fun earlyWifiChoiceAndLoadedMethodsMergeIndependently() {
        val owner = SaveSettingsOwner()
        owner.updateWifiOnly(true)
        assertEquals(SaveSettings(true, 2, false, true, false),
            owner.initialize(SaveSettings(false, 2, false, true, false)))
        assertEquals(SaveSettings(true, 1, false, true, false), owner.updateConcurrency(1))
        assertEquals(SaveSettings(false, 1, false, true, false), owner.updateWifiOnly(false))
    }

    @Test fun activityRecreationAndDelayedOldLoadsCannotRollBackAcceptedChoices() {
        val owner = SaveSettingsOwner()
        owner.initialize(SaveSettings(false, 1))
        for (method in methods) owner.updateMethodEnabled(method, false)
        owner.updateWifiOnly(true); owner.updateConcurrency(2)
        val accepted = SaveSettings(true, 2, false, false, false)
        repeat(3) { assertEquals(accepted, owner.initialize(SaveSettings())) }
        assertEquals(accepted.copy(streamEnabled = true), owner.updateMethodEnabled(TaskMethod.STREAM, true))
        assertEquals(accepted.copy(streamEnabled = true), owner.initialize(accepted))
    }
}
