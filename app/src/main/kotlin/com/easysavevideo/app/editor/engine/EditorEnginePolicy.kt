package com.easysavevideo.app.editor.engine

import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditLimits
import kotlin.math.abs

/** Preserve the Editor name while both callers use the same shared fence implementation. */
internal typealias EditorReleaseFence = com.easysavevideo.app.media.transform.TransformerReleaseFence

internal object EditorEnginePolicy {
    const val MAX_OPEN_INPUTS = 4
    const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
    const val MAX_CODECS = 2 * EditLimits.MAX_RECORDED_SEGMENTS + 2
    const val MAX_SOURCE_SAMPLES = 2_000_000
    const val POLL_MILLIS = 50L
    const val OUTPUT_RESERVE = 8L * 1024 * 1024
    fun outputRangeAllowed(position: Long, bytes: Long, limit: Long): Boolean =
        com.easysavevideo.app.media.transform.TransformerMechanismPolicy.outputRangeAllowed(position, bytes, limit)

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
    fun timestampAllowed(ptsUs: Long, previousUs: Long?, video: Boolean, durationUs: Long, toleranceUs: Long, audioPrimingUs: Long): Boolean =
        com.easysavevideo.app.media.transform.TransformerMechanismPolicy.timestampAllowed(ptsUs, previousUs, video, durationUs, toleranceUs, audioPrimingUs)
    fun codecCreationProvenAbsent(ownPolicyRejection: Boolean, formatUnsupported: Boolean, hasCodecInfo: Boolean, hasCodecName: Boolean): Boolean =
        com.easysavevideo.app.media.transform.TransformerMechanismPolicy.codecCreationProvenAbsent(ownPolicyRejection, formatUnsupported, hasCodecInfo, hasCodecName)
    private fun reject(): Nothing = throw EditFailure(EditError.UNSUPPORTED_PROFILE)
}

/** Existing policy names forward to the mechanism exercised by both callers. */
internal class EditorOutputIoPolicy(limit: Long) {
    private val shared = com.easysavevideo.app.media.transform.TransformerOutputIoPolicy(limit)
    fun beginFinalization() = shared.beginFinalization()
    fun activeCheckpoint(checkpoint: () -> Unit) = mapped { shared.activeCheckpoint(checkpoint) }
    fun range(position: Long, bytes: Long) = mapped { shared.range(position, bytes) }
    fun confirmedClosed() = shared.confirmedClosed()
    private inline fun mapped(block: () -> Unit) {
        try { block() } catch (failure: com.easysavevideo.app.media.transform.TransformerFailure) { throw editorTransformerFailure(failure) }
    }
}
internal typealias EditorNativeReleaseOrder = com.easysavevideo.app.media.transform.TransformerNativeReleaseOrder
internal object EditorTaskFailureProbe {
    fun failed(task: Runnable, uncaught: Throwable?): Boolean = com.easysavevideo.app.media.transform.TransformerTaskFailureProbe.failed(task, uncaught)
}
