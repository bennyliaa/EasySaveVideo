package com.easysavevideo.app.editor.verification

import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditError
import com.easysavevideo.library.edit.EditFailure
import com.easysavevideo.library.edit.EditOutputExpectation
import kotlin.math.abs

internal fun invalidEditOutput(): Nothing = throw EditFailure(EditError.VERIFICATION_FAILED)
internal fun unsupportedEditOutput(): Nothing = throw EditFailure(EditError.UNSUPPORTED_PROFILE)

internal object EditVerificationBounds {
    const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
    const val MAX_SAMPLES = 250_000
    const val MAX_ATOMS = 4096
    const val MAX_CONFIG_BYTES = 64 * 1024
    // Integer media timescales can round a CFR clock. This is not a VFR acceptance window.
    const val TIMESTAMP_ROUNDING_US = 1_000L
    const val NO_PROGRESS_NANOS = 5_000_000_000L
}

internal data class EditEncodedSample(val ptsUs: Long, val bytes: Int, val flags: Int)
internal data class EditScannedTrack(
    val samples: List<EditEncodedSample>, val bytes: Long, val firstPtsUs: Long,
    val lastPtsUs: Long, val endUs: Long,
)

/** Every encoded access unit is walked, including its actual size and final sample duration. */
internal class EditSampleLedger(
    private val video: Boolean,
    private val expected: EditOutputExpectation,
    private val timing: EditMp4Track,
) {
    private val samples = ArrayList<EditEncodedSample>()
    private var bytes = 0L
    private var failed = false
    private var finished = false
    init {
        if (video && timing.sampleCount.toLong() != expected.videoFrameCount) invalidEditOutput()
    }

    fun sample(ptsUs: Long, size: Long, flags: Int) {
        active()
        val allowance = if (video) 0 else expected.audioPrimingToleranceUs
        if (samples.size >= timing.sampleCount || samples.size >= EditVerificationBounds.MAX_SAMPLES ||
            ptsUs < -allowance || ptsUs > expected.durationUs + expected.durationToleranceUs + allowance ||
            size !in 1..EditVerificationBounds.MAX_SAMPLE_BYTES.toLong() || flags and 1.inv() != 0 ||
            size.toInt() != timing.sampleSizes[samples.size] ||
            (video && samples.isEmpty() && flags and 1 == 0)) fail()
        val previous = samples.lastOrNull()
        if (video && samples.isNotEmpty() && abs(ptsUs - samples.first().ptsUs -
                samples.size * 1_000_000L / expected.frameRate) > EditVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        if (previous != null) {
            if (ptsUs <= previous.ptsUs) fail() // Encoder requests no B frames; do not silently admit them.
            val step = if (video) 1_000_000.0 / expected.frameRate
                else 1024_000_000.0 / (expected.audio as? EditAudioProfile.Aac ?: fail()).sampleRate
            if (abs((ptsUs - previous.ptsUs).toDouble() - step) > EditVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        }
        bytes += size
        if (bytes > expected.maxBytes) fail()
        samples.add(EditEncodedSample(ptsUs, size.toInt(), flags))
    }

    fun finish(): EditScannedTrack {
        active()
        if (samples.isEmpty() || samples.size != timing.sampleCount || bytes != timing.sampleBytes ||
            abs(samples.last().ptsUs - samples.first().ptsUs - timing.spanUs) > EditVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        val first = samples.first().ptsUs
        val end = samples.last().ptsUs + timing.lastDurationUs
        val tolerance = expected.durationToleranceUs + (if (video) 0 else expected.audioPrimingToleranceUs)
        if (video && abs(timing.lastDurationUs - 1_000_000.0 / expected.frameRate) >
            EditVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        if (abs(first - timing.presentationOffsetUs) > EditVerificationBounds.TIMESTAMP_ROUNDING_US ||
            abs(first) > (if (video) EditVerificationBounds.TIMESTAMP_ROUNDING_US else expected.audioPrimingToleranceUs) ||
            abs(end - expected.durationUs) > tolerance || end <= 0) fail()
        finished = true
        return EditScannedTrack(samples.toList(), bytes, first, samples.last().ptsUs, end)
    }

    private fun active() { if (failed || finished) fail() }
    private fun fail(): Nothing { failed = true; invalidEditOutput() }
}

/** Full video decode: each exact demux PTS must produce one non-config frame and real output EOS. */
internal class EditVideoDecodeLedger(private val track: EditScannedTrack) {
    private var queued = 0
    private var decoded = 0
    private var inputEos = false
    private var ended = false
    private var failed = false
    val outputEos: Boolean get() = ended && !failed
    val decodedFrames: Long get() = if (outputEos) decoded.toLong() else 0

    fun input(sample: EditEncodedSample) {
        active()
        if (inputEos || queued >= track.samples.size || sample != track.samples[queued]) fail()
        queued++
    }
    fun inputEnd() {
        active()
        if (inputEos || queued != track.samples.size) fail()
        inputEos = true
    }
    fun output(ptsUs: Long, size: Int, config: Boolean, eos: Boolean): Boolean {
        active()
        if (size < 0 || (config && eos)) fail()
        var progress = false
        if (size > 0 && !config) {
            if (decoded >= queued || decoded >= track.samples.size || ptsUs != track.samples[decoded].ptsUs) fail()
            decoded++; progress = true
        }
        if (eos) {
            if (!inputEos || decoded != track.samples.size) fail()
            ended = true; progress = true
        }
        return progress
    }
    private fun active() { if (failed || ended) fail() }
    private fun fail(): Nothing { failed = true; invalidEditOutput() }
}

/** AAC decode proves continuous PCM time, including the tail; packet and PCM counts differ. */
internal class EditAudioDecodeLedger(
    private val expected: EditOutputExpectation,
    private val track: EditScannedTrack,
) {
    private val audio = expected.audio as? EditAudioProfile.Aac ?: invalidEditOutput()
    private var queued = 0
    private var inputEos = false
    private var ended = false
    private var failed = false
    private var firstPtsUs: Long? = null
    private var lastEndUs: Long? = null
    private var frames = 0L
    val outputEos: Boolean get() = ended && !failed
    val decodedFrames: Long get() = if (outputEos) frames else 0

    fun input(sample: EditEncodedSample) {
        active()
        if (inputEos || queued >= track.samples.size || sample != track.samples[queued]) fail()
        queued++
    }
    fun inputEnd() {
        active()
        if (inputEos || queued != track.samples.size) fail()
        inputEos = true
    }
    fun output(ptsUs: Long, size: Int, config: Boolean, eos: Boolean): Boolean {
        active()
        if (size < 0 || (config && eos)) fail()
        var progress = false
        if (size > 0 && !config) {
            val stride = audio.channels * 2 // Decoder must expose PCM16; adapter checks its format.
            if (size % stride != 0 || queued == 0) fail()
            val blockFrames = size / stride
            val blockUs = blockFrames * 1_000_000L / audio.sampleRate
            val previousEnd = lastEndUs
            // Permit only integer-microsecond/sample rounding within the continuous PCM body.
            val rounding = 2L + (1_000_000L + audio.sampleRate - 1) / audio.sampleRate
            if (blockFrames <= 0 || (previousEnd != null && abs(ptsUs - previousEnd) > rounding) ||
                ptsUs < -expected.audioPrimingToleranceUs ||
                ptsUs > expected.durationUs + expected.durationToleranceUs + expected.audioPrimingToleranceUs) fail()
            if (firstPtsUs == null) firstPtsUs = ptsUs
            lastEndUs = ptsUs + blockUs
            frames += blockFrames
            if (frames > (expected.durationUs + expected.durationToleranceUs +
                    2 * expected.audioPrimingToleranceUs) * audio.sampleRate / 1_000_000 + 2048) fail()
            progress = true
        }
        if (eos) {
            val first = firstPtsUs ?: fail()
            val end = lastEndUs ?: fail()
            if (!inputEos || frames <= 0 || abs(first) > expected.audioPrimingToleranceUs ||
                abs(end - expected.durationUs) > expected.durationToleranceUs + expected.audioPrimingToleranceUs ||
                abs(frames * 1_000_000L / audio.sampleRate - expected.durationUs) >
                    expected.durationToleranceUs + expected.audioPrimingToleranceUs + EditVerificationBounds.TIMESTAMP_ROUNDING_US ||
                abs(end - track.endUs) > expected.audioPrimingToleranceUs + EditVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
            ended = true; progress = true
        }
        return progress
    }
    private fun active() { if (failed || ended) fail() }
    private fun fail(): Nothing { failed = true; invalidEditOutput() }
}

internal class EditDecodeProgress(startNanos: Long) {
    private var last = startNanos
    fun progressed(nowNanos: Long) { if (nowNanos < last) invalidEditOutput(); last = nowNanos }
    fun check(nowNanos: Long) {
        if (nowNanos < last || nowNanos - last >= EditVerificationBounds.NO_PROGRESS_NANOS)
            throw EditFailure(EditError.TIMEOUT)
    }
}
