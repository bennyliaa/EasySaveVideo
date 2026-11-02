package com.easysavevideo.save

import java.util.concurrent.atomic.AtomicReference

/** One source lifetime, not a task state machine. No IO, callbacks or upper-layer locks. */
internal class PrivateRuntimeSource(
    val binding: PrivateNetworkSourceBinding,
    resource: SelectedMediaResource,
) {
    private enum class Phase { SOURCE, REVOKED, OUTPUT_ONLY }
    private data class State(val phase: Phase, val resource: SelectedMediaResource?, val checkpoint: DownloadCheckpoint?)
    private val state = AtomicReference(State(Phase.SOURCE, resource, null))
    fun isSourceLive(): Boolean = state.get().phase == Phase.SOURCE && binding.isLive()
    fun resource(): SelectedMediaResource? = state.get().takeIf { it.phase == Phase.SOURCE && binding.isLive() }?.resource
    fun checkpoint(): DownloadCheckpoint? = state.get().takeIf { it.phase == Phase.SOURCE && binding.isLive() }?.checkpoint
    fun checkpoint(value: DownloadCheckpoint?) {
        while (true) {
            val old = state.get()
            if (old.phase != Phase.SOURCE || !binding.isLive()) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            if (state.compareAndSet(old, old.copy(checkpoint = value))) return
        }
    }
    fun clearCheckpoint() {
        while (true) {
            val old = state.get()
            if (old.checkpoint == null || state.compareAndSet(old, old.copy(checkpoint = null))) return
        }
    }
    /** Competes atomically with exit only after real transfer release and exact durable raw CAS. */
    fun detach(): Boolean {
        while (true) {
            val old = state.get()
            if (old.phase != Phase.SOURCE) return false
            if (state.compareAndSet(old, State(Phase.OUTPUT_ONLY, null, null))) return true
        }
    }
    fun isDetached() = state.get().phase == Phase.OUTPUT_ONLY
    /** false means output already won; caller must not cancel the output context in that case. */
    fun revokeSource(): Boolean {
        while (true) {
            val old = state.get()
            if (old.phase == Phase.OUTPUT_ONLY) return false
            if (old.phase == Phase.REVOKED) return true
            if (state.compareAndSet(old, State(Phase.REVOKED, null, null))) return true
        }
    }
}
