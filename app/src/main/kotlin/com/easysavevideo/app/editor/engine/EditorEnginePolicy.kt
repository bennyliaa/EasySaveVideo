package com.easysavevideo.app.editor.engine

import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditLimits
import kotlin.math.abs

/** One process lane. A timed-out owner remains strongly reachable until process exit. */
internal class EditorReleaseFence {
    private var held: Any? = null
    @Volatile var requiresRestart = false; private set
    @Synchronized fun claim(owner: Any): Boolean {
        if (requiresRestart || held != null) return false
        held = owner; return true
    }
    @Synchronized fun release(owner: Any) {
        if (requiresRestart) return
        check(held === owner); held = null
    }
    @Synchronized fun unconfirmed() { requiresRestart = true }
    val occupied: Boolean @Synchronized get() = held != null
}

internal object EditorEnginePolicy {
    const val MAX_OPEN_INPUTS = 4
    const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
    const val MAX_CODECS = 2 * EditLimits.MAX_RECORDED_SEGMENTS + 2
    const val MAX_SOURCE_SAMPLES = 2_000_000
    const val POLL_MILLIS = 50L
    const val OUTPUT_RESERVE = 8L * 1024 * 1024
    fun outputRangeAllowed(position: Long, bytes: Long, limit: Long): Boolean =
        position >= 0 && bytes >= 0 && limit > 0 && position <= limit && bytes <= limit - position

    /** Observes every PTS (sorted for decoding-order B frames), not a rounded metadata claim. */
    fun observedFrameRate(timesUs: LongArray, count: Int, durationUs: Long): Int {
        if (count < 2 || count > timesUs.size || durationUs !in 1..EditLimits.MAX_SOURCE_DURATION_US)
            reject()
        java.util.Arrays.sort(timesUs, 0, count)
        val span = timesUs[count - 1] - timesUs[0]
        if (timesUs[0] < 0 || span <= 0) reject()
        val rounded = Math.round((count - 1) * 1_000_000.0 / span).toInt()
        if (rounded !in 1..EditLimits.MAX_FRAME_RATE) reject()
        // Shared bounded global phase tolerance covers container clock quantization.
        // Every observed timestamp is checked; an average frame-rate claim is insufficient.
        for (index in 1 until count) {
            val expected = index * 1_000_000L / rounded
            if (timesUs[index] <= timesUs[index - 1] || abs(timesUs[index] - timesUs[0] - expected) > EditLimits.MAX_FRAME_TIMESTAMP_DRIFT_US) reject()
        }
        if (abs(durationUs - timesUs[0] - count * 1_000_000L / rounded) > EditLimits.MAX_FRAME_TIMESTAMP_DRIFT_US) reject()
        return rounded
    }
    /** NDC has bottom-up Y; UI/display crop coordinates have top-down Y. */
    fun ndc(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): FloatArray {
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || right <= left || bottom <= top || right > width || bottom > height)
            throw EditFailure(EditError.INVALID_CROP)
        return floatArrayOf(2f * left / width - 1f, 2f * right / width - 1f,
            1f - 2f * bottom / height, 1f - 2f * top / height)
    }
    fun timestampAllowed(ptsUs: Long, previousUs: Long?, video: Boolean, durationUs: Long, toleranceUs: Long, audioPrimingUs: Long): Boolean {
        val lower = if (video) 0L else -audioPrimingUs
        return ptsUs >= lower && ptsUs <= durationUs + toleranceUs + (if (video) 0 else audioPrimingUs) &&
            (previousUs == null || ptsUs > previousUs)
    }
    fun codecCreationProvenAbsent(ownPolicyRejection: Boolean, formatUnsupported: Boolean, hasCodecInfo: Boolean, hasCodecName: Boolean): Boolean =
        ownPolicyRejection || (formatUnsupported && hasCodecInfo && !hasCodecName)
    private fun reject(): Nothing = throw EditFailure(EditError.UNSUPPORTED_PROFILE)
}

/** Cancellation fences publication, but cannot prevent bounded MP4 final tables/FD cleanup. */
internal class EditorOutputIoPolicy(private val limit: Long) {
    private var finalizing = false
    private var closed = false
    @Synchronized fun beginFinalization() { if (!closed) finalizing = true }
    @Synchronized fun activeCheckpoint(checkpoint: () -> Unit) {
        if (closed) throw EditFailure(EditError.INVALID_STATE)
        if (!finalizing) checkpoint()
    }
    @Synchronized fun range(position: Long, bytes: Long) {
        if (closed || !EditorEnginePolicy.outputRangeAllowed(position, bytes, limit)) throw EditFailure(EditError.SOURCE_LIMIT)
    }
    @Synchronized fun confirmedClosed() { closed = true }
}

/** Prevent decoder/encoder release while terminal GL teardown can still own codec buffers. */
internal class EditorNativeReleaseOrder {
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
internal object EditorTaskFailureProbe {
    fun failed(task: Runnable, uncaught: Throwable?): Boolean {
        if (uncaught != null) return true
        if (task !is java.util.concurrent.Future<*> || !task.isDone) return false
        return try { task.get(); false }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); true }
        catch (_: Throwable) { true }
    }
}
