package com.easysavevideo.app.recording

/**
 * Owner-thread consent handback bookkeeping. This is not capture permission: the Runtime, private
 * source lease, one-shot surface permit and native latch are independently checked at submission.
 * No lifecycle callback identifies why the Activity lost focus. Every suspension needs a new click.
 */
internal class PrivateRecordingContinuation(
    private val owner: Any,
    private val activity: Any,
    private val deadlineMillis: Long,
) {
    private var stopped = false
    private var submitted = false
    private var service: Any? = null
    private var suspended = false
    private var confirmedGeneration: Long? = null
    var generation: Long = 0; private set

    fun suspendProtected(exactOwner: Any, exactActivity: Any): Boolean {
        if (stopped || submitted || exactOwner !== owner || exactActivity !== activity) return false
        suspended = true; confirmedGeneration = null; generation++
        return true
    }
    fun serviceParked(exactOwner: Any, exactService: Any, nowMillis: Long): Boolean {
        if (!live(exactOwner, nowMillis) || submitted || service != null) return false
        service = exactService
        return true
    }
    fun confirmContinue(exactOwner: Any, exactActivity: Any, capturedGeneration: Long,
        resumedFocusedAndExactTarget: Boolean, nowMillis: Long): Boolean {
        if (!live(exactOwner, nowMillis) || submitted || exactActivity !== activity ||
            capturedGeneration != generation || !resumedFocusedAndExactTarget) return false
        confirmedGeneration = generation
        return true
    }
    fun mayPrepareSurface(exactOwner: Any, exactActivity: Any, resumedFocusedAndExactTarget: Boolean,
        nowMillis: Long): Boolean = live(exactOwner, nowMillis) && !submitted && service != null &&
        exactActivity === activity && resumedFocusedAndExactTarget &&
        (!suspended || confirmedGeneration == generation)

    fun claimSubmission(exactOwner: Any, exactActivity: Any, exactService: Any,
        resumedFocusedAndExactTarget: Boolean, nowMillis: Long): Boolean {
        if (service !== exactService || !mayPrepareSurface(exactOwner, exactActivity,
                resumedFocusedAndExactTarget, nowMillis)) return false
        submitted = true
        return true
    }
    fun stop() { stopped = true; generation++; confirmedGeneration = null; service = null }
    private fun live(exactOwner: Any, nowMillis: Long): Boolean =
        !stopped && exactOwner === owner && nowMillis < deadlineMillis
}
