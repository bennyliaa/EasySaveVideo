package com.easysavevideo.app.recording

import java.util.concurrent.atomic.AtomicReference

/** Source observation only. It does not attest selected scope or change the encoder canvas. */
internal data class BasicCaptureSize(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        fun valid(width: Int, height: Int, densityDpi: Int): Boolean =
            width in 1..8192 && height in 1..8192 && densityDpi > 0
    }
}

internal enum class BasicCaptureResizeResult { NOT_READY, UNCHANGED, RESIZED, RETIRED }

/**
 * One instance belongs to one native recording session. Observers retain only the newest bounded
 * size; no observer performs media work or waits for a native call. The native owner binds itself
 * once, supplies the actual created display size, and alone applies pending observations.
 *
 * Retiring is irreversible. An already admitted native call can finish after Stop; retirement
 * never blocks Stop on a driver, retries that call, or claims that native resources were released.
 * Actual display release stays on the same owner thread, after any admitted resize returns.
 */
internal class BasicCaptureGeometry {
    private data class Observation(val generation: Long, val size: BasicCaptureSize)
    private data class State(val latest: Observation? = null, val retired: Boolean = false)
    private val state = AtomicReference(State())
    private val owner = AtomicReference<Thread?>()
    private var applied: BasicCaptureSize? = null
    private var appliedGeneration = 0L

    fun bindOwner() {
        check(owner.compareAndSet(null, Thread.currentThread()))
    }

    /** A malformed/resource-excessive observation is rejected; ordinary rotation is valid. */
    fun observe(width: Int, height: Int, densityDpi: Int): Boolean {
        if (!BasicCaptureSize.valid(width, height, densityDpi)) return state.get().retired
        val size = BasicCaptureSize(width, height, densityDpi)
        while (true) {
            val prior = state.get()
            if (prior.retired || prior.latest?.size == size) return true
            val generation = prior.latest?.generation ?: 0L
            if (generation == Long.MAX_VALUE) return false
            if (state.compareAndSet(prior, State(Observation(generation + 1, size)))) return true
        }
    }

    /** API34+ density observation must not replace the system-selected content dimensions. */
    fun observeDensity(densityDpi: Int): Boolean {
        if (densityDpi <= 0) return state.get().retired
        while (true) {
            val prior = state.get()
            val latest = prior.latest
            if (prior.retired || latest == null || latest.size.densityDpi == densityDpi) return true
            if (latest.generation == Long.MAX_VALUE) return false
            val next = Observation(latest.generation + 1, latest.size.copy(densityDpi = densityDpi))
            if (state.compareAndSet(prior, State(next))) return true
        }
    }

    fun displayCreated(width: Int, height: Int, densityDpi: Int) {
        requireOwner()
        check(applied == null)
        require(BasicCaptureSize.valid(width, height, densityDpi))
        applied = BasicCaptureSize(width, height, densityDpi)
    }

    /** Runs the supplied native resize synchronously on the bound owner, never under a lock. */
    fun applyLatest(ownerIsLive: () -> Boolean, resize: (BasicCaptureSize) -> Unit): BasicCaptureResizeResult {
        requireOwner()
        if (state.get().retired) return BasicCaptureResizeResult.RETIRED
        if (!ownerIsLive()) { retire(); return BasicCaptureResizeResult.RETIRED }
        if (applied == null) return BasicCaptureResizeResult.NOT_READY
        val snapshot = state.get()
        if (snapshot.retired) return BasicCaptureResizeResult.RETIRED
        val next = snapshot.latest ?: return BasicCaptureResizeResult.UNCHANGED
        if (next.generation == appliedGeneration) return BasicCaptureResizeResult.UNCHANGED
        if (next.size == applied) {
            appliedGeneration = next.generation
            return BasicCaptureResizeResult.UNCHANGED
        }
        // Final adjacent admission: Stop/Discard/lease loss wins if observed before this operation.
        if (!ownerIsLive() || state.get().retired) { retire(); return BasicCaptureResizeResult.RETIRED }
        try {
            resize(next.size)
        } catch (failure: Throwable) {
            // A native allocation/resize failure is real; never mark it applied or auto-retry it.
            retire()
            throw failure
        }
        applied = next.size
        appliedGeneration = next.generation
        return BasicCaptureResizeResult.RESIZED
    }

    /** Metadata only, callable by Stop/Discard and projection callbacks without native waits. */
    fun retire() { state.set(State(retired = true)) }

    private fun requireOwner() { check(owner.get() === Thread.currentThread()) }
}
