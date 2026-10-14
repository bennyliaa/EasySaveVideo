package com.easysavevideo.app.save

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** New-task dispatch only. This capability never grants Resume journal-repair authority. */
internal class SaveWorkerHandoff {
    private enum class State { UNACCEPTED, ACCEPTED, SUPPRESSED }
    private val state = AtomicReference(State.UNACCEPTED)
    private val entered = AtomicBoolean(false)
    private val offered = AtomicBoolean(false)

    /** True transfers all completion/release responsibility to the one accepted worker. */
    fun launch(submit: (Runnable) -> Unit, work: () -> Unit): Boolean {
        if (!offered.compareAndSet(false, true)) return false
        return try {
            submit(Runnable {
                if (accept() && entered.compareAndSet(false, true)) work()
            })
            accept()
        } catch (_: Exception) {
            // A provider may enqueue and then throw. Suppress late entry before caller releases.
            if (state.compareAndSet(State.UNACCEPTED, State.SUPPRESSED)) false
            else state.get() == State.ACCEPTED
        }
    }
    private fun accept(): Boolean = state.compareAndSet(State.UNACCEPTED, State.ACCEPTED) ||
        state.get() == State.ACCEPTED
}
