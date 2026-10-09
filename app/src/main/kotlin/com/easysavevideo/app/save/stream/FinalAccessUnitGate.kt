package com.easysavevideo.app.save.stream

import java.io.IOException

/** Exactly-once asset EOF transition. Counts are observations, never repaired or normalized. */
internal class FinalAccessUnitGate(
    private val expectedVideo: Long,
    private val expectedAudio: Long,
    private val pendingVcl: Boolean,
) {
    private enum class State { READING, FINISHING, FINISHED, FAILED }
    private var state = State.READING
    var callbackCount = 0
        private set

    fun readingAllowed() { if (state != State.READING) reject() }

    fun finish(video: () -> Long, audio: () -> Long, finishAvc: () -> Unit) {
        if (state == State.FINISHED) return
        if (state != State.READING) reject()
        state = State.FINISHING
        try {
            if (expectedVideo <= 0 || expectedAudio < 0 || audio() != expectedAudio) reject()
            val before = video()
            if (pendingVcl) {
                if (before != expectedVideo - 1) reject()
                callbackCount++ // Mark before invoking, including when the callback throws.
                finishAvc()
            } else if (before != expectedVideo) reject()
            if (video() != expectedVideo || audio() != expectedAudio) reject()
            state = State.FINISHED
        } catch (failure: Throwable) { state = State.FAILED; throw failure }
    }

    private fun reject(): Nothing { state = State.FAILED; throw IOException("LOCAL_SAMPLE_MISMATCH") }
}
