package com.easysavevideo.app.recording

import java.util.concurrent.atomic.AtomicBoolean

/** Late results never publish; actual release is reconciled on both sides of the timeout race. */
internal class RecordingResultFence(
    private val lease: RecordingNativeLease,
    private val markUnconfirmed: () -> Unit,
    private val markReleased: () -> Unit,
) {
    private val abandoned = AtomicBoolean()
    val isAbandoned: Boolean get() = abandoned.get()
    fun abandon(cancelOwner: () -> Unit) {
        abandoned.set(true)
        markUnconfirmed()
        lease.abandon()
        cancelOwner()
        // The owner may already have finished before seeing abandoned=true.
        ownerFinished()
    }
    fun ownerFinished() {
        if (abandoned.get() && !lease.occupied) markReleased()
    }
}
