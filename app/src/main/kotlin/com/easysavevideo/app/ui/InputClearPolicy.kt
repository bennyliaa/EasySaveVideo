package com.easysavevideo.app.ui

import com.easysavevideo.core.model.BrowserTab

/** Composition-owned explicit-clear gate. No text, URLs, commands or saved state are retained. */
internal class InputClearGate {
    var token: Any = Any()
        private set
    var active: Boolean = true
        private set

    fun invalidate(): Any { token = Any(); return token }
    fun retire() { active = false; invalidate() }
    fun accept(expected: Any, enabled: Boolean, hasText: Boolean): Boolean {
        if (!active || expected !== token || !enabled || !hasText) return false
        invalidate()
        return true
    }
}

internal fun canClearBrowserDraft(expected: BrowserTab?, current: BrowserTab?,
    expectedInspectionRevision: Long, currentInspectionRevision: Long, interactive: Boolean): Boolean =
    interactive && expected != null && current != null && expected.context == current.context &&
        expected.epoch == current.epoch && expectedInspectionRevision == currentInspectionRevision
