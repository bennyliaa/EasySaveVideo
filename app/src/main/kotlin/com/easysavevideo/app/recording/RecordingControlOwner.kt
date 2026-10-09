package com.easysavevideo.app.recording

/** Sample clocks inside this critical section, never before queueing/waiting for the owner. */
internal class RecordingControlOwner(private val monitor: Any) {
    fun <T> transition(block: () -> T): T = synchronized(monitor, block)
}
