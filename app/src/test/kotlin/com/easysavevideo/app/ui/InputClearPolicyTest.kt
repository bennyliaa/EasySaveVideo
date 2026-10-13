package com.easysavevideo.app.ui

import com.easysavevideo.core.model.BrowserContextRef
import com.easysavevideo.core.model.BrowserTab
import org.junit.Assert.*
import org.junit.Test

class InputClearPolicyTest {
    @Test fun nonemptyAndWhitespaceDraftsCanBeCleared() {
        for (text in listOf("text", "  ", "\t", "猫")) {
            val gate = InputClearGate()
            assertTrue(gate.accept(gate.token, true, text.isNotEmpty()))
        }
    }
    @Test fun emptyAndDisabledRejectWithoutConsumingCurrentClaim() {
        val gate = InputClearGate(); val claim = gate.token
        assertFalse(gate.accept(claim, true, false))
        assertFalse(gate.accept(claim, false, true))
        assertSame(claim, gate.token)
        assertTrue(gate.accept(claim, true, true))
    }
    @Test fun acceptedClearConsumesTokenBeforeARepeatedCallback() {
        val gate = InputClearGate();val claim = gate.token
        assertTrue(gate.accept(claim, true, true))
        assertFalse(gate.accept(claim, true, true))
        assertFalse(gate.accept(gate.token, true, false))
    }
    @Test fun laterEditAndAbaEditBothRejectOldClaim() {
        val gate = InputClearGate();val a = gate.token
        gate.invalidate()
        assertFalse(gate.accept(a, true, true))
        gate.invalidate()
        assertFalse(gate.accept(a, true, true))
        assertTrue(gate.accept(gate.token, true, true))
    }
    @Test fun programmaticAddressSyncAndModeChangeUseSameInvalidation() {
        val gate = InputClearGate();val before = gate.token
        val afterSync = gate.invalidate();assertNotSame(before, afterSync)
        val afterMode = gate.invalidate();assertNotSame(afterSync, afterMode)
        assertFalse(gate.accept(before, true, true));assertFalse(gate.accept(afterSync, true, true))
        assertTrue(gate.accept(afterMode, true, true))
    }
    @Test fun retiredOrSubmittedOwnerCannotBeReactivatedByTyping() {
        val gate = InputClearGate();val claim = gate.token
        gate.retire();assertFalse(gate.active)
        assertFalse(gate.accept(claim, true, true))
        assertFalse(gate.accept(gate.invalidate(), true, true))
    }
    @Test fun independentFieldsDoNotInvalidateEachOther() {
        val address = InputClearGate();val video = InputClearGate();val claim = video.token
        address.invalidate();address.retire()
        assertTrue(video.accept(claim, true, true))
    }
    @Test fun browserOwnerAndForegroundMustStillMatchAtClick() {
        val tab = BrowserTab(BrowserContextRef("tab", "session"))
        assertTrue(canClearBrowserDraft(tab, tab, 3, 3, true))
        assertFalse(canClearBrowserDraft(tab, tab, 3, 3, false))
        assertFalse(canClearBrowserDraft(tab, tab, 3, 5, true)) // switch away and back
        assertFalse(canClearBrowserDraft(tab, tab.copy(epoch = tab.epoch + 1), 3, 3, true))
        assertFalse(canClearBrowserDraft(tab, BrowserTab(BrowserContextRef("other", "session")), 3, 3, true))
        assertFalse(canClearBrowserDraft(null, tab, 3, 3, true))
        assertFalse(canClearBrowserDraft(tab, null, 3, 3, true))
    }
}
