package com.easysavevideo.app.browser.history

/** In-memory panel callback authority; no browsing payload or saved-state data. */
internal class HistoryPanelActionOwner {
    var token: Any = Any(); private set
    private var active = true
    fun invalidate() { token = Any() }
    fun retire() { active = false; invalidate() }
    fun current(expected: Any): Boolean = active && token === expected
}

/** Read current page authority and current row authority immediately before native navigation. */
internal fun openHistoryRow(token: HistoryRowToken, canAct: () -> Boolean,
    addressForOpen: (HistoryRowToken) -> String?, open: (String) -> Boolean,
    stillOwnsPanel: () -> Boolean, onAccepted: () -> Unit): Boolean {
    if (!canAct()) return false
    val address = addressForOpen(token) ?: return false
    if (!canAct() || !open(address)) return false
    if (stillOwnsPanel()) onAccepted()
    return true
}
