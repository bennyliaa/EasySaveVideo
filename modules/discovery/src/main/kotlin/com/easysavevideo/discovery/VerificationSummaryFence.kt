package com.easysavevideo.discovery

import java.util.concurrent.atomic.AtomicReference

/** One atomic generation/value pair; invalidation cannot be undone by a late publisher. */
class VerificationSummaryFence {
    private data class State(val generation: Long, val value: VerificationSummary?)
    private val state = AtomicReference(State(0, null))
    val generation: Long get() = state.get().generation
    fun snapshot(): VerificationSummary? = state.get().value
    fun invalidate() {
        while (true) {
            val old = state.get()
            if (state.compareAndSet(old, State(old.generation + 1, null))) return
        }
    }
    fun publish(generation: Long, value: VerificationSummary): Boolean {
        while (true) {
            val old = state.get()
            if (old.generation != generation) return false
            if (state.compareAndSet(old, old.copy(value = value))) return true
        }
    }
}
