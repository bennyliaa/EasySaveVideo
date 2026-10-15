package com.easysavevideo.app.ui

/**
 * Main-thread UI admission, subordinate to the exact captured root publication and route owner.
 * Never replace either callback with a latest-value bridge: a queued old click must stay old.
 * This does not grant runtime authority or claim that already-admitted work has drained.
 */
internal class CapturedPrivateActions(
    private val privateMode: Boolean,
    private val inputAdmission: () -> Boolean,
    private val ownerCurrent: () -> Boolean,
) {
    private var closed = false

    /** Only the input capability is private-only; stale ordinary callbacks cannot enter a new owner. */
    fun isCurrent(): Boolean = !closed && ownerCurrent() && (!privateMode || inputAdmission())

    fun run(action: () -> Unit) {
        if (isCurrent()) action()
    }

    fun close() { closed = true }
}
