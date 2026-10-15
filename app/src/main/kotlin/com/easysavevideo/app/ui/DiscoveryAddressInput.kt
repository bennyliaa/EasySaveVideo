package com.easysavevideo.app.ui

import com.easysavevideo.app.browser.BrowserInputPolicy

internal enum class DiscoveryAddressFeedback { INVALID, UNAVAILABLE, ROUTE_VETO }

/** Ephemeral editor sequencing only. The Host remains the navigation owner. */
internal class DiscoveryAddressInput {
    val gate = InputClearGate()
    private var submitting = false

    fun edit(interactive: Boolean, changed: (Any) -> Unit): Boolean {
        if (!gate.active || !interactive) return false
        // A live text connection may deliver several edits before the next frame.
        // Each edit retires commands, but does not retire that editor's next text update.
        changed(gate.invalidate())
        return true
    }

    fun clear(expected: Any, interactive: Boolean, hasText: Boolean, changed: (Any) -> Unit): Boolean {
        if (!gate.accept(expected, interactive, hasText)) return false
        changed(gate.token)
        return true
    }

    fun submit(text: String, expected: Any, interactive: () -> Boolean,
        tokenChanged: (Any) -> Unit, clearFocus: () -> Unit,
        open: (String, () -> Boolean) -> Boolean, feedback: (DiscoveryAddressFeedback) -> Unit,
        actionCurrent: () -> Boolean = interactive) {
        if (submitting || !gate.active || gate.token !== expected || !interactive() || !actionCurrent() || text.isEmpty()) return
        // Validate raw input before trimming: controls at either edge must not disappear.
        val valid = text.length <= 8192 && text.none { it.code < 32 || it.code == 127 } &&
            BrowserInputPolicy.address(text, allowBareHost = true) != null
        if (!gate.accept(expected, interactive() && actionCurrent(), true)) return
        tokenChanged(gate.token)
        val attempt = gate.token
        val draftCurrent = { gate.active && gate.token === attempt && interactive() }
        if (!valid) {
            if (draftCurrent()) feedback(DiscoveryAddressFeedback.INVALID)
            return
        }
        submitting = true
        try {
            clearFocus()
            val accepted = draftCurrent() && open(text, draftCurrent)
            if (draftCurrent()) feedback(if (accepted) DiscoveryAddressFeedback.ROUTE_VETO else DiscoveryAddressFeedback.UNAVAILABLE)
        } finally { submitting = false }
    }
}
