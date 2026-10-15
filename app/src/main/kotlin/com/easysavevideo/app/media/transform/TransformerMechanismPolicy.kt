package com.easysavevideo.app.media.transform

internal object TransformerMechanismPolicy {
    const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
    const val MAX_SOURCE_SAMPLES = 2_000_000
    const val POLL_MILLIS = 50L
    const val OUTPUT_RESERVE = 8L * 1024 * 1024
    fun outputRangeAllowed(position: Long, bytes: Long, limit: Long): Boolean =
        position >= 0 && bytes >= 0 && limit > 0 && position <= limit && bytes <= limit - position
    fun timestampAllowed(ptsUs: Long, previousUs: Long?, video: Boolean, durationUs: Long, toleranceUs: Long, audioPrimingUs: Long): Boolean {
        val lower = if (video) 0L else -audioPrimingUs
        return ptsUs >= lower && ptsUs <= durationUs + toleranceUs + (if (video) 0 else audioPrimingUs) &&
            (previousUs == null || ptsUs > previousUs)
    }
    fun codecCreationProvenAbsent(ownPolicyRejection: Boolean, formatUnsupported: Boolean, hasCodecInfo: Boolean, hasCodecName: Boolean): Boolean =
        ownPolicyRejection || (formatUnsupported && hasCodecInfo && !hasCodecName)
}

/** Cancellation fences publication, but cannot prevent bounded MP4 final tables/FD cleanup. */
internal class TransformerOutputIoPolicy(private val limit: Long) {
    private var finalizing = false
    private var closed = false
    @Synchronized fun beginFinalization() { if (!closed) finalizing = true }
    @Synchronized fun activeCheckpoint(checkpoint: () -> Unit) {
        if (closed) throw TransformerFailure(TransformerError.INVALID_STATE)
        if (!finalizing) checkpoint()
    }
    @Synchronized fun range(position: Long, bytes: Long) {
        if (closed || !TransformerMechanismPolicy.outputRangeAllowed(position, bytes, limit)) throw TransformerFailure(TransformerError.SOURCE_LIMIT)
    }
    @Synchronized fun confirmedClosed() { closed = true }
}

/** Prevent decoder/encoder release while terminal GL teardown can still own codec buffers. */
internal class TransformerNativeReleaseOrder {
    private var glOwners = 0
    private var glReleased = 0
    private var glReleaseStarted = false
    private var poisoned = false
    @Synchronized fun registerGl() { check(!glReleaseStarted); glOwners++ }
    @Synchronized fun beginGlRelease() { glReleaseStarted = true }
    @Synchronized fun confirmGlReleased() { check(glReleased < glOwners); glReleased++ }
    @Synchronized fun glFailed() { poisoned = true }
    @Synchronized fun allowsCodecRelease(): Boolean = !poisoned && (!glReleaseStarted || glReleased == glOwners)
    @Synchronized fun isPoisoned(): Boolean = poisoned
}

/** submit() hides Errors inside FutureTask; afterExecute must inspect completed futures too. */
internal object TransformerTaskFailureProbe {
    fun failed(task: Runnable, uncaught: Throwable?): Boolean {
        if (uncaught != null) return true
        if (task !is java.util.concurrent.Future<*> || !task.isDone) return false
        return try { task.get(); false }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); true }
        catch (_: Throwable) { true }
    }
}
