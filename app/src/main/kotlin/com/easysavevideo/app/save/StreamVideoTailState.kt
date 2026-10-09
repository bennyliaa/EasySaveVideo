package com.easysavevideo.app.save

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import kotlin.math.abs

/** Actual extractor identities retained only for the bounded final GOP, after the full scan. */
internal data class StreamTailSample(
    val timeUs: Long,
    val flags: Int,
    val inputBytes: Int,
    val canonicalBytes: Long,
    val sha256: String,
)

/**
 * Progressive/no-B tail proof. Every input and every nonempty output is accounted for, rather than
 * accepting a nearby frame. EOS attached to the final nonempty output is processed after that frame.
 * This class has no Android dependency so failure/ordering cases can be exercised without a codec.
 */
internal class StreamVideoTailState(
    private val expected: List<StreamTailSample>,
    private val checkpoint: () -> Unit,
) {
    private var queued = 0
    private var decoded = 0
    private var lastOutputUs = -1L
    private var inputEos = false
    private var outputEos = false
    private var failed = false
    val complete: Boolean get() = outputEos && !failed
    val decodedSamples: Int get() = decoded

    init {
        require(expected.isNotEmpty() && expected.size <= 250_000)
        require(expected.first().flags == 1)
        require(expected.all { it.timeUs >= 0 && it.inputBytes in 1..4 * 1024 * 1024 &&
            it.canonicalBytes > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")) && it.flags in 0..1 })
        require(expected.zipWithNext().all { (a, b) -> b.timeUs > a.timeUs })
    }

    fun input(sample: StreamTailSample) {
        active()
        if (inputEos || queued >= expected.size || sample != expected[queued]) reject()
        queued++
    }

    fun inputEndOfStream() {
        active()
        if (inputEos || queued != expected.size) reject()
        inputEos = true
    }

    fun output(timeUs: Long, bytes: Int, codecConfig: Boolean, endOfStream: Boolean): Boolean {
        active()
        if (bytes < 0) reject()
        var progressed = false
        if (bytes > 0 && !codecConfig) {
            // Decoder input PTS already came from independently verified MP4 ticks. Decoders are
            // required to preserve these microsecond PTS; allow only one microsecond conversion.
            if (decoded >= queued || decoded >= expected.size || timeUs < 0 || timeUs <= lastOutputUs ||
                abs(timeUs - expected[decoded].timeUs) > 1) reject()
            lastOutputUs = timeUs
            decoded++
            progressed = true
        }
        if (endOfStream) {
            if (codecConfig || !inputEos || decoded != expected.size) reject()
            outputEos = true
            progressed = true
        }
        return progressed
    }

    private fun active() {
        try { checkpoint() } catch (failure: Throwable) { failed = true; throw failure }
        if (failed || outputEos) reject()
    }
    private fun reject(): Nothing { failed = true; throw SaveFailure(SaveError.INVALID_MEDIA) }
}

/** Native dequeue returning TRY_AGAIN forever must fail before the absolute operation deadline. */
internal class StreamDecodeProgress(private val startedNanos: Long, private val timeoutNanos: Long) {
    private var lastProgress = startedNanos
    init { require(timeoutNanos > 0) }
    fun madeProgress(nowNanos: Long) { require(nowNanos >= lastProgress); lastProgress = nowNanos }
    fun check(nowNanos: Long) {
        if (nowNanos < lastProgress || nowNanos - lastProgress >= timeoutNanos)
            throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
    }
}

internal data class StreamDecodedVideoFormat(
    val mime: String, val width: Int, val height: Int, val cropLeft: Int, val cropRight: Int,
    val cropTop: Int, val cropBottom: Int, val color: Int,
)

/** A decoder may announce its initial raw output format once; a later switch is never tail proof. */
internal class StreamVideoFormatState(private val encodedWidth: Int, private val encodedHeight: Int) {
    private var first: StreamDecodedVideoFormat? = null
    private var formatEvent = false
    private var outputSeen = false
    fun accept(actual: StreamDecodedVideoFormat, changeEvent: Boolean) {
        if (actual.mime != "video/raw" || actual.width !in 1..8192 || actual.height !in 1..8192 ||
            actual.width.toLong() * actual.height > 16_777_216 ||
            actual.cropLeft !in 0 until actual.width || actual.cropRight !in actual.cropLeft until actual.width ||
            actual.cropTop !in 0 until actual.height || actual.cropBottom !in actual.cropTop until actual.height ||
            actual.cropRight - actual.cropLeft + 1 != encodedWidth ||
            actual.cropBottom - actual.cropTop + 1 != encodedHeight ||
            (first != null && first != actual) || (changeEvent && (formatEvent || outputSeen)))
            throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        first = actual
        if (changeEvent) formatEvent = true else outputSeen = true
    }
}
