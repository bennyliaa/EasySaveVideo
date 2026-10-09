package com.easysavevideo.app.save

import java.util.concurrent.atomic.AtomicReference

/**
 * A real initialization owner survives worker return until main installs the continuing owners.
 * Worker/queue failure ends this owner explicitly; readiness or historical records never do.
 */
internal class MediaTaskActivityHandoff(private val changed: () -> Unit) {
    private enum class State { PREPARING, INSTALL_QUEUED, COMPLETE }
    private val state = AtomicReference(State.PREPARING)
    val active: Boolean get() = state.get() != State.COMPLETE

    fun enqueue(dispatch: (() -> Unit) -> Boolean, install: () -> Unit): Boolean {
        if (!state.compareAndSet(State.PREPARING, State.INSTALL_QUEUED)) return false
        try {
            val accepted = dispatch {
                if (state.get() == State.INSTALL_QUEUED) {
                    try { install() } finally { finish() }
                }
            }
            if (!accepted) finish()
            return accepted
        } catch (failure: Exception) {
            finish()
            throw failure
        }
    }

    /** Cannot release an already enqueued installation when its worker returns. */
    fun preparationFailed() {
        if (state.compareAndSet(State.PREPARING, State.COMPLETE)) notifyChanged()
    }

    private fun finish() {
        if (state.getAndSet(State.COMPLETE) != State.COMPLETE) notifyChanged()
    }
    private fun notifyChanged() {
        try { changed() } catch (_: Exception) { /* Optional priority observation. */ }
    }
}
