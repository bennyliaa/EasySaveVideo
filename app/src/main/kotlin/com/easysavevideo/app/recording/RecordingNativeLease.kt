package com.easysavevideo.app.recording

/**
 * Application-lifetime admission for one recording native owner. A timeout or cancelled Future
 * never proves that MediaCodec/MediaMuxer or a duplicated descriptor has been released.
 */
class RecordingNativeLease {
    private enum class State { FREE, QUEUED, RUNNING, DRAINING, UNCERTAIN }
    private var state = State.FREE
    private var retainedOwner: Any? = null

    @get:Synchronized val occupied: Boolean get() = state != State.FREE
    @get:Synchronized val releaseUnconfirmed: Boolean
        get() = state == State.DRAINING || state == State.UNCERTAIN

    @Synchronized fun tryAcquire(): Boolean {
        if (state != State.FREE) return false
        state = State.QUEUED
        return true
    }

    @Synchronized fun started(): Boolean {
        if (state == State.RUNNING) return true
        if (state != State.QUEUED) return false
        state = State.RUNNING
        return true
    }

    /** Queued work still owns its admission until its worker actually acknowledges cancellation. */
    @Synchronized fun abandon() {
        state = when (state) {
            State.QUEUED, State.RUNNING -> State.DRAINING
            else -> state
        }
    }

    /** Only an executor rejection, before scheduling, proves that no native worker can start. */
    @Synchronized fun rejectQueued() {
        check(state == State.QUEUED)
        state = State.FREE
        retainedOwner = null
    }

    /** Keep failed native releases reachable, including the storage descriptor fence. */
    @Synchronized fun keepAlive(owner: Any) {
        check(state != State.FREE)
        retainedOwner = owner
    }

    /** Called only by the actual native owner, after its entire cleanup body has returned. */
    @Synchronized fun released(confirmed: Boolean) {
        check(state != State.FREE && state != State.UNCERTAIN)
        if (confirmed) {
            retainedOwner = null
            state = State.FREE
        } else {
            // Do not allow a later UI timeout/cancel to turn uncertainty into free admission.
            state = State.UNCERTAIN
        }
    }
}
