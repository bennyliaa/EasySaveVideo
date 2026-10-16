package com.easysavevideo.app.vault

import com.easysavevideo.library.MediaReadRef

/** Main-lane only. Losing a route never fabricates or discards an outstanding retirement ACK. */
internal class VaultOrdinaryRetirementQueue(
    private val presentationAbsent: () -> Boolean,
    private val retireCaches: (MediaReadRef, () -> Unit) -> Unit,
) {
    private class Entry(val ref: MediaReadRef, val acknowledged: () -> Unit) {
        var requested = false
        var cacheRetired = false
    }
    private val entries = mutableListOf<Entry>()
    fun request(ref: MediaReadRef, acknowledged: () -> Unit) {
        entries.add(Entry(ref, acknowledged)); progress()
    }
    fun progress() {
        if (!presentationAbsent()) return
        entries.toList().forEach { entry ->
            if (entry !in entries) return@forEach
            if (entry.cacheRetired) {
                if (presentationAbsent()) { entries.remove(entry); entry.acknowledged() }
            } else if (!entry.requested) {
                entry.requested = true
                retireCaches(entry.ref) { entry.cacheRetired = true; progress() }
            }
        }
    }
}
