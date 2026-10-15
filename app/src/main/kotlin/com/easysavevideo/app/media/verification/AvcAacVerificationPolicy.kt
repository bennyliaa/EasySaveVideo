package com.easysavevideo.app.media.verification

import kotlin.math.abs
import java.math.BigInteger

internal enum class AvcAacVerificationError { VERIFICATION_FAILED, UNSUPPORTED_PROFILE, TIMEOUT, RELEASE_UNCONFIRMED }
internal class AvcAacVerificationFailure(val code: AvcAacVerificationError) : RuntimeException(code.name)

internal fun invalidAvcAacOutput(): Nothing = throw AvcAacVerificationFailure(AvcAacVerificationError.VERIFICATION_FAILED)
internal fun unsupportedAvcAacOutput(): Nothing = throw AvcAacVerificationFailure(AvcAacVerificationError.UNSUPPORTED_PROFILE)

internal object AvcAacVerificationBounds {
    const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
    const val MAX_SAMPLES = 250_000
    const val MAX_ATOMS = 4096
    const val MAX_CONFIG_BYTES = 64 * 1024
    // Integer media timescales can round a CFR clock. This is not a VFR acceptance window.
    const val TIMESTAMP_ROUNDING_US = 1_000L
    const val NO_PROGRESS_NANOS = 5_000_000_000L
}

internal data class AvcAacEncodedSample(val ptsUs: Long, val bytes: Int, val flags: Int)
internal data class AvcAacScannedTrack(
    val samples: List<AvcAacEncodedSample>, val bytes: Long, val firstPtsUs: Long,
    val lastPtsUs: Long, val endUs: Long,
    val audioPresentation: AvcAacAudioPresentation? = null,
)

/** Every encoded access unit is walked, including its actual size and final sample duration. */
internal class AvcAacSampleLedger(
    private val video: Boolean,
    private val expected: AvcAacOutputExpectation,
    private val timing: AvcAacMp4Track,
) {
    private val samples = ArrayList<AvcAacEncodedSample>()
    private var bytes = 0L
    private var failed = false
    private var finished = false
    private val independent = !video && expected.independentAudio != null
    private var audioPresentation: AvcAacAudioPresentation? = null
    init {
        if (video && timing.sampleCount.toLong() != expected.videoFrameCount) invalidAvcAacOutput()
    }

    fun sample(ptsUs: Long, size: Long, flags: Int) {
        active()
        val allowance = if (video) 0 else expected.audioPrimingToleranceUs
        val maximumPts = if (independent) 1_800_000_000L + 2 * AvcAacIndependentAudioExpectation.BOUNDARY_US
            else expected.durationUs + expected.durationToleranceUs + allowance
        if (samples.size >= timing.sampleCount || samples.size >= AvcAacVerificationBounds.MAX_SAMPLES ||
            ptsUs < -allowance || ptsUs > maximumPts ||
            size !in 1..AvcAacVerificationBounds.MAX_SAMPLE_BYTES.toLong() || flags and 1.inv() != 0 ||
            size.toInt() != timing.sampleSizes[samples.size] ||
            (video && samples.isEmpty() && flags and 1 == 0)) fail()
        if (independent) try {
            val mapping = audioPresentation ?: AvcAacAudioPresentation.from(timing, ptsUs,
                (expected.audio as AvcAacAudioProfile.Aac).sampleRate).also { audioPresentation = it }
            mapping.requireSample(samples.size, ptsUs)
        } catch (failure: AvcAacVerificationFailure) { failed = true; throw failure }
        val previous = samples.lastOrNull()
        if (video && samples.isNotEmpty() && abs(ptsUs - samples.first().ptsUs -
                samples.size * 1_000_000L / expected.frameRate) > AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        if (previous != null) {
            if (ptsUs <= previous.ptsUs) fail() // Encoder requests no B frames; do not silently admit them.
            val step = if (video) 1_000_000.0 / expected.frameRate
                else 1024_000_000.0 / (expected.audio as? AvcAacAudioProfile.Aac ?: fail()).sampleRate
            if (abs((ptsUs - previous.ptsUs).toDouble() - step) > AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        }
        bytes += size
        if (bytes > expected.maxBytes) fail()
        samples.add(AvcAacEncodedSample(ptsUs, size.toInt(), flags))
    }

    fun finish(): AvcAacScannedTrack {
        active()
        if (samples.isEmpty() || samples.size != timing.sampleCount || bytes != timing.sampleBytes ||
            abs(samples.last().ptsUs - samples.first().ptsUs - timing.spanUs) > AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        val first = samples.first().ptsUs
        val end = samples.last().ptsUs + timing.lastDurationUs
        val tolerance = expected.durationToleranceUs + (if (video) 0 else expected.audioPrimingToleranceUs)
        if (video && abs(timing.lastDurationUs - 1_000_000.0 / expected.frameRate) >
            AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
        if (independent) {
            if (audioPresentation == null || end <= 0) fail()
        } else if (abs(first - timing.presentationOffsetUs) > AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US ||
            abs(first) > (if (video) AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US else expected.audioPrimingToleranceUs) ||
            abs(end - expected.durationUs) > tolerance || end <= 0) fail()
        finished = true
        return AvcAacScannedTrack(samples.toList(), bytes, first, samples.last().ptsUs, end, audioPresentation)
    }

    private fun active() { if (failed || finished) fail() }
    private fun fail(): Nothing { failed = true; invalidAvcAacOutput() }
}

/** The complete scan establishes whether this extractor already applied the one supported edit. */
internal enum class AvcAacAudioCoordinates { MEDIA, PRESENTATION }

/** This is a native behavior requirement, not inferred from absent/default-zero format keys. */
internal enum class AvcAacNativeAudioMapping {
    UNVERIFIED,
    PCM_FOLLOWS_EXTRACTOR_WITH_VERIFIED_PRIMING,
}

internal data class AvcAacPresentedAudio(val startUs: Long, val endUs: Long, val frames: Long,
    val sampleRate: Int, val channels: Int, val outputEos: Boolean, val continuous: Boolean)

/**
 * Same-file/same-track window only. Endpoints are half-open and both use ceil on the raw sample
 * lattice. No encoder E, video duration, format delay default or guessed priming enters this class.
 */
internal class AvcAacAudioPresentation private constructor(
    private val timing: AvcAacMp4Track,
    val coordinates: AvcAacAudioCoordinates,
    val sampleRate: Int,
    private val mediaTime: Long,
    val startFrame: Long,
    val endFrame: Long,
) {
    private val scale = BigInteger.valueOf(timing.timescale)
    private val million = BigInteger.valueOf(1_000_000)
    private val rate = BigInteger.valueOf(sampleRate.toLong())
    private val editOffsetNumerator = BigInteger.valueOf(mediaTime).multiply(rate)
    val durationUs: Long get() = (endFrame - startFrame) * 1_000_000 / sampleRate

    fun requireSample(index: Int, actualPtsUs: Long) {
        val tick = timing.sampleStartTicks?.getOrNull(index) ?: invalidAvcAacOutput()
        val mapped = tick - if (coordinates == AvcAacAudioCoordinates.PRESENTATION) mediaTime else 0L
        val expectedUs = Math.floorDiv(Math.multiplyExact(mapped, 1_000_000L), timing.timescale)
        if (abs(actualPtsUs - expectedUs) > 2L) invalidAvcAacOutput()
    }

    /** Recover a PCM sample edge once. Already-presented extractor PTS gets the inverse edit here. */
    fun mediaFrame(ptsUs: Long): Long {
        var numerator = BigInteger.valueOf(ptsUs).multiply(rate).multiply(scale)
        if (coordinates == AvcAacAudioCoordinates.PRESENTATION)
            numerator = numerator.add(editOffsetNumerator.multiply(million))
        val denominator = million.multiply(scale)
        return floor(numerator.multiply(BigInteger.valueOf(2)).add(denominator), denominator.multiply(BigInteger.valueOf(2)))
    }

    /** Clock comparison remains rational, so quantizing each block cannot conceal drift or overlap. */
    fun requirePcmClock(ptsUs: Long, mediaFrame: Long) {
        val mediaNumerator = BigInteger.valueOf(mediaFrame).multiply(scale)
            .subtract(if (coordinates == AvcAacAudioCoordinates.PRESENTATION) editOffsetNumerator else BigInteger.ZERO)
        val expectedUs = floor(mediaNumerator.multiply(million), rate.multiply(scale))
        if (abs(ptsUs - expectedUs) > 2L) invalidAvcAacOutput()
    }

    fun presentedUs(mediaFrame: Long): Long = floor(
        BigInteger.valueOf(mediaFrame).multiply(scale).subtract(editOffsetNumerator).multiply(million),
        rate.multiply(scale))

    companion object {
        private fun floor(n: BigInteger, d: BigInteger): Long {
            val pair = n.divideAndRemainder(d)
            return pair[0].subtract(if (pair[1].signum() < 0) BigInteger.ONE else BigInteger.ZERO).longValueExact()
        }
        private fun ceil(n: BigInteger, d: BigInteger): Long = -floor(n.negate(), d)
        fun from(timing: AvcAacMp4Track, firstPtsUs: Long, sampleRate: Int): AvcAacAudioPresentation {
            if (timing.kind != "soun" || sampleRate != 48_000 || timing.timescale !in 1..1_000_000_000L ||
                timing.movieTimescale !in 1..1_000_000_000L || timing.durationTicks <= 0 ||
                timing.sampleStartTicks?.size != timing.sampleCount ||
                timing.editPresent != timing.edits.isNotEmpty() || timing.edits.size > 1) unsupportedAvcAacOutput()
            val edit = timing.edits.singleOrNull()
            if (edit != null && (edit.mediaTimeTicks < 0 || edit.segmentDurationTicks <= 0)) unsupportedAvcAacOutput()
            val media = edit?.mediaTimeTicks ?: 0L
            val scale = BigInteger.valueOf(timing.timescale)
            val movie = BigInteger.valueOf(timing.movieTimescale)
            val rate = BigInteger.valueOf(sampleRate.toLong())
            val startNumerator = BigInteger.valueOf(media).multiply(rate)
            val start = ceil(startNumerator, scale)
            val end = if (edit == null) ceil(BigInteger.valueOf(timing.durationTicks).multiply(rate), scale)
                else ceil(startNumerator.multiply(movie).add(BigInteger.valueOf(edit.segmentDurationTicks)
                    .multiply(rate).multiply(scale)), scale.multiply(movie))
            val total = ceil(BigInteger.valueOf(timing.durationTicks).multiply(rate), scale)
            val boundary = 2048L // Two AAC frames fit the 42,667us allowance, independently of video.
            if (start !in 0..boundary || end <= start || end > total + 1 || total - end > boundary ||
                end - start > 86_400_000L + 2048) unsupportedAvcAacOutput()
            // tkhd is in movie time. Compare only to this track's own window, allowing one movie tick.
            val presented = if (edit == null) BigInteger.valueOf(timing.durationTicks).multiply(movie)
                else BigInteger.valueOf(edit.segmentDurationTicks).multiply(scale)
            val declared = BigInteger.valueOf(timing.trackDurationTicks).multiply(scale)
            if (timing.trackDurationTicks <= 0 || declared.subtract(presented).abs() > scale)
                invalidAvcAacOutput()
            val shiftedUs = Math.floorDiv(Math.multiplyExact(-media, 1_000_000L), timing.timescale)
            val mediaMatch = abs(firstPtsUs) <= 2
            val presentationMatch = abs(firstPtsUs - shiftedUs) <= 2
            val coordinates = when {
                media == 0L && mediaMatch -> AvcAacAudioCoordinates.MEDIA
                mediaMatch && !presentationMatch -> AvcAacAudioCoordinates.MEDIA
                presentationMatch && !mediaMatch -> AvcAacAudioCoordinates.PRESENTATION
                else -> unsupportedAvcAacOutput() // Ambiguous/non-covered extractor mapping is not guessed.
            }
            return AvcAacAudioPresentation(timing, coordinates, sampleRate, media, start, end)
        }
    }
}

/** Full video decode: each exact demux PTS must produce one non-config frame and real output EOS. */
internal class AvcAacVideoDecodeLedger(private val track: AvcAacScannedTrack) {
    private var queued = 0
    private var decoded = 0
    private var inputEos = false
    private var ended = false
    private var failed = false
    val outputEos: Boolean get() = ended && !failed
    val decodedFrames: Long get() = if (outputEos) decoded.toLong() else 0

    fun input(sample: AvcAacEncodedSample) {
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
    private fun fail(): Nothing { failed = true; invalidAvcAacOutput() }
}

/** AAC decode proves continuous PCM time, including the tail; packet and PCM counts differ. */
internal class AvcAacAudioDecodeLedger(
    private val expected: AvcAacOutputExpectation,
    private val track: AvcAacScannedTrack,
    private val nativeMapping: AvcAacNativeAudioMapping = AvcAacNativeAudioMapping.UNVERIFIED,
) {
    private val audio = expected.audio as? AvcAacAudioProfile.Aac ?: invalidAvcAacOutput()
    private var queued = 0
    private var inputEos = false
    private var ended = false
    private var failed = false
    private var firstPtsUs: Long? = null
    private var lastEndUs: Long? = null
    private var frames = 0L
    private val presentation = if (expected.independentAudio != null)
        track.audioPresentation ?: invalidAvcAacOutput() else null
    private var firstMediaFrame: Long? = null
    private var retainedStart: Long? = null
    private var retainedEnd: Long? = null
    private var retainedFrames = 0L
    val presentedAudio: AvcAacPresentedAudio? get() {
        val p = presentation ?: return null
        if (!outputEos || nativeMapping != AvcAacNativeAudioMapping.PCM_FOLLOWS_EXTRACTOR_WITH_VERIFIED_PRIMING) return null
        return AvcAacPresentedAudio(p.presentedUs(retainedStart ?: return null),
            p.presentedUs(retainedEnd ?: return null), retainedFrames, audio.sampleRate, audio.channels, true, true)
    }
    val outputEos: Boolean get() = ended && !failed
    val decodedFrames: Long get() = if (outputEos) frames else 0

    fun input(sample: AvcAacEncodedSample) {
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
            if (presentation != null) {
                if (blockFrames <= 0 || ptsUs !in -85_334L..1_800_085_334L ||
                    frames + blockFrames.toLong() > queued.toLong() * 1024L) fail()
                val first = firstMediaFrame ?: presentation.mediaFrame(ptsUs).also { firstMediaFrame = it }
                val start = Math.addExact(first, frames)
                try { presentation.requirePcmClock(ptsUs, start) }
                catch (failure: AvcAacVerificationFailure) { failed = true; throw failure }
                val end = Math.addExact(start, blockFrames.toLong())
                val outside = 2048L
                if (start < presentation.startFrame - outside || end > presentation.endFrame + outside) fail()
                val keptStart = maxOf(start, presentation.startFrame)
                val keptEnd = minOf(end, presentation.endFrame)
                if (keptEnd > keptStart) {
                    if (retainedEnd != null && retainedEnd != keptStart) fail()
                    if (retainedStart == null) retainedStart = keptStart
                    retainedEnd = keptEnd
                    retainedFrames = Math.addExact(retainedFrames, keptEnd - keptStart)
                }
                frames = Math.addExact(frames, blockFrames.toLong())
                if (frames > 86_400_000L + 4096) fail()
                if (firstPtsUs == null) firstPtsUs = ptsUs
                lastEndUs = ptsUs + blockUs
                progress = true
            } else {
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
        }
        if (eos) {
            if (presentation != null) {
                if (!inputEos || frames <= 0 || retainedFrames <= 0 ||
                    retainedStart != presentation.startFrame || retainedEnd != presentation.endFrame ||
                    retainedFrames != presentation.endFrame - presentation.startFrame) fail()
            } else {
                val first = firstPtsUs ?: fail()
                val end = lastEndUs ?: fail()
                if (!inputEos || frames <= 0 || abs(first) > expected.audioPrimingToleranceUs ||
                    abs(end - expected.durationUs) > expected.durationToleranceUs + expected.audioPrimingToleranceUs ||
                    abs(frames * 1_000_000L / audio.sampleRate - expected.durationUs) >
                        expected.durationToleranceUs + expected.audioPrimingToleranceUs + AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US ||
                    abs(end - track.endUs) > expected.audioPrimingToleranceUs + AvcAacVerificationBounds.TIMESTAMP_ROUNDING_US) fail()
            }
            ended = true; progress = true
        }
        return progress
    }
    private fun active() { if (failed || ended) fail() }
    private fun fail(): Nothing { failed = true; invalidAvcAacOutput() }
}

/** E is compared only here, after the actual window and all PCM intersections have been fixed. */
internal fun requireIndependentAacMatches(expected: AvcAacIndependentAudioExpectation,
    observed: AvcAacPresentedAudio) {
    if (!observed.outputEos || !observed.continuous || observed.frames !in 1..86_402_048L ||
        observed.sampleRate != 48_000 || observed.channels !in 1..2 ||
        observed.startUs !in -42_667L..42_667L || observed.endUs !in 1..1_800_042_667L) invalidAvcAacOutput()
    val duration = observed.frames * 1_000_000L / observed.sampleRate
    if (abs(observed.startUs) > AvcAacIndependentAudioExpectation.BOUNDARY_US ||
        abs(observed.endUs - expected.durationUs) > AvcAacIndependentAudioExpectation.BOUNDARY_US ||
        abs(duration - expected.durationUs) > AvcAacIndependentAudioExpectation.BOUNDARY_US ||
        abs(observed.endUs - observed.startUs - duration) > 2) invalidAvcAacOutput()
}

internal class AvcAacDecodeProgress(startNanos: Long) {
    private var last = startNanos
    fun progressed(nowNanos: Long) { if (nowNanos < last) invalidAvcAacOutput(); last = nowNanos }
    fun check(nowNanos: Long) {
        if (nowNanos < last || nowNanos - last >= AvcAacVerificationBounds.NO_PROGRESS_NANOS)
            throw AvcAacVerificationFailure(AvcAacVerificationError.TIMEOUT)
    }
}


/** AAC AudioSpecificConfig is checked independently; HE-AAC/SBR/PS or explicit extensions fail. */
internal object AvcAacAacConfigPolicy {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    fun requireLc(bytes: ByteArray, sampleRate: Int, channels: Int) {
        // AAC LC with frameLengthFlag=0, dependsOnCoreCoder=0, extensionFlag=0, no implicit extension.
        if (bytes.size != 2) unsupportedAvcAacOutput()
        val bits = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        val objectType = bits ushr 11
        val frequencyIndex = (bits ushr 7) and 15
        val channelCount = (bits ushr 3) and 15
        if (objectType != 2 || rates.getOrNull(frequencyIndex) != sampleRate || channelCount != channels || bits and 7 != 0) unsupportedAvcAacOutput()
    }
}

/** Measurement only: Editor keeps its relative-clock acceptance; converted policy applies its bound. */
internal fun maximumFixedVideoDeviationUs(samples: List<AvcAacEncodedSample>, frameRate: Int,
    checkpoint: () -> Unit): Long {
    if (frameRate <= 0 || samples.isEmpty() || samples.size > AvcAacVerificationBounds.MAX_SAMPLES)
        invalidAvcAacOutput()
    var maximum = 0L
    samples.forEachIndexed { index, sample ->
        checkpoint()
        // Bounded index and nonnegative video PTS make multiplication/subtraction/abs safe.
        if (sample.ptsUs < 0) invalidAvcAacOutput()
        val expectedUs = index.toLong() * 1_000_000L / frameRate
        maximum = maxOf(maximum, abs(sample.ptsUs - expectedUs))
    }
    checkpoint()
    return maximum
}
