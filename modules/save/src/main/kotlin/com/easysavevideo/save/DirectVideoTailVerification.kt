package com.easysavevideo.save

import java.util.Collections

/** Transient native-input identity. Payloads, Android objects and source metadata are not retained. */
data class DirectNativeSample(val identity: DirectSampleIdentity, val inputBytes: Int) {
    init { require(inputBytes in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES) }
}

/**
 * The independently scanned final GOP, [precedingVideoSync.index, video.sampleCount). This is an
 * ephemeral bounded manifest, never a journal/evidence encoding. The caller must first compare the
 * Android-derived video conservation with the fresh raw scan and the normalization proof. Full
 * conservation is retained unchanged; optional audio requires its own complete independent check.
 * A completed tail is video evidence only, never proof that audio was decoded.
 */
class DirectVideoTailPlan(
    val clock: DirectMovClock,
    val conservation: DirectRemuxConservation,
    samples: List<DirectNativeSample>,
    checkpoint: () -> Unit = {},
) {
    val samples: List<DirectNativeSample>
    val sampleCount: Int
    val totalInputBytes: Long
    val totalCanonicalBytes: Long

    init {
        checkpoint()
        val bounds = DirectTailManifestBounds(clock, conservation)
        if (samples.size != bounds.expectedCount) invalidDirectTail()
        // Check the count before allocating; validate each item and sum before retaining it.
        val owned = ArrayList<DirectNativeSample>(samples.size)
        for (sample in samples) {
            checkpoint()
            bounds.append(sample)
            owned.add(sample)
        }
        bounds.finish()
        checkpoint()
        this.samples = Collections.unmodifiableList(owned)
        sampleCount = owned.size
        totalInputBytes = bounds.inputBytes
        totalCanonicalBytes = bounds.canonicalBytes
    }

    /** Collect only the already-known final GOP; earlier GOPs never consume this budget. */
    class Builder(
        private val clock: DirectMovClock,
        private val conservation: DirectRemuxConservation,
        private val checkpoint: () -> Unit = {},
    ) {
        private val bounds = DirectTailManifestBounds(clock, conservation)
        private val samples = ArrayList<DirectNativeSample>()
        private var failure: Throwable? = null
        private var finished = false
        val sampleCount: Int get() = samples.size
        val totalInputBytes: Long get() = bounds.inputBytes

        fun append(sample: DirectNativeSample): Unit = guarded {
            bounds.append(sample)
            samples.add(sample)
        }

        fun finish(): DirectVideoTailPlan = guarded {
            bounds.finish()
            val plan = DirectVideoTailPlan(clock, conservation, samples, checkpoint)
            finished = true
            plan
        }

        private inline fun <T> guarded(body: () -> T): T {
            failure?.let { throw it }
            try {
                checkpoint()
                if (finished) invalidDirectTail()
                return body()
            } catch (error: Throwable) { failure = error; throw error }
        }
    }

    companion object {
        const val MAX_TAIL_BYTES = 64L * 1024 * 1024
    }
}

/** Shared constructor/append guard; every resource bound is checked before list retention. */
private class DirectTailManifestBounds(
    private val clock: DirectMovClock,
    private val conservation: DirectRemuxConservation,
) {
    private val firstIndex = conservation.precedingVideoSync.index
    val expectedCount: Int
    private var count = 0
    var inputBytes = 0L
        private set
    var canonicalBytes = 0L
        private set

    init {
        if (clock.kind != DirectSampleKind.AVC_BASELINE || clock.timescale != DirectMovClock.VIDEO_TIMESCALE ||
            clock.frameRate != conservation.frameRate || clock.sampleCount != conservation.video.sampleCount ||
            clock.endpoint != conservation.video.endpoint ||
            conservation.video.lastSample.presentationTimeUs != clock.presentationTimeUs(clock.sampleCount - 1) ||
            conservation.precedingVideoSync.presentationTimeUs != clock.presentationTimeUs(firstIndex)) invalidDirectTail()
        val count = clock.sampleCount - firstIndex
        if (count !in 1..DirectNormalizationLimits.MAX_VIDEO_SAMPLES) invalidDirectTail()
        expectedCount = count.toInt()
    }

    fun append(sample: DirectNativeSample) {
        val identity = sample.identity
        if (count >= expectedCount || identity.index != firstIndex + count ||
            identity.presentationTimeUs != clock.presentationTimeUs(identity.index) ||
            identity.flags != if (count == 0) 1 else 0) invalidDirectTail()
        if (count == 0 && identity != conservation.precedingVideoSync) invalidDirectTail()
        if (count == expectedCount - 1 && identity != conservation.video.lastSample) invalidDirectTail()
        val nextInput = boundedDirectTailSum(inputBytes, sample.inputBytes)
        val nextCanonical = boundedDirectTailSum(canonicalBytes, identity.canonicalBytes)
        if (nextCanonical > conservation.video.canonicalBytes - firstIndex) invalidDirectTail()
        inputBytes = nextInput
        canonicalBytes = nextCanonical
        count++
    }

    fun finish() {
        if (count != expectedCount || (firstIndex == 0L && canonicalBytes != conservation.video.canonicalBytes))
            invalidDirectTail()
    }
}

/**
 * One-to-one progressive/no-B terminal proof. The Android adapter validates buffer bounds, flags,
 * format, exact extractor EOF and native operations. No method here claims those operations ran.
 *
 * Call [requireNextInput] before queueing the reread native bytes. Call [input] only after that queue
 * succeeds, and [inputEndOfStream] only after exact extractor EOF and successful empty EOS queue.
 * A native failure must abort the enclosing inspection; it must never be recovered using this state.
 */
class DirectVideoTailState(
    private val plan: DirectVideoTailPlan,
    private val checkpoint: () -> Unit,
) {
    private var queued = 0
    private var decoded = 0
    private var queuedBytes = 0L
    private var lastOutputUs = -1L
    private var inputEos = false
    private var outputEos = false
    private var finished = false
    private var failure: Throwable? = null
    val complete: Boolean get() = outputEos && failure == null
    val queuedSamples: Int get() = queued
    val decodedSamples: Int get() = decoded

    /** Validation without accounting: required before submitting a reread native sample. */
    fun requireNextInput(sample: DirectNativeSample) = guarded {
        validateNextInput(sample)
    }

    /** Record a successfully queued native media input. Merely dequeuing a buffer is not progress. */
    fun input(sample: DirectNativeSample) = guarded {
        validateNextInput(sample)
        queuedBytes = boundedDirectTailSum(queuedBytes, sample.inputBytes)
        queued++
    }

    /** The adapter must have queued exactly one empty EOS after independently checking all EOF facts. */
    fun inputEndOfStream() = guarded {
        if (inputEos || queued != plan.sampleCount || queuedBytes != plan.totalInputBytes) invalidDirectTail()
        inputEos = true
    }

    /** Returns true only for a validated real frame or output EOS, for the native no-progress guard. */
    fun output(timeUs: Long, bytes: Int, codecConfig: Boolean, endOfStream: Boolean): Boolean = guarded {
        if (bytes < 0 || (codecConfig && endOfStream)) invalidDirectTail()
        var progressed = false
        if (bytes > 0 && !codecConfig) {
            if (decoded >= queued || decoded >= plan.sampleCount ||
                timeUs !in 0..DirectNormalizationLimits.MAX_DURATION_US || timeUs <= lastOutputUs) invalidDirectTail()
            val expected = plan.samples[decoded].identity.presentationTimeUs
            if (timeUs < expected - DirectNormalizationLimits.MAX_ROUNDING_US ||
                timeUs > expected + DirectNormalizationLimits.MAX_ROUNDING_US) invalidDirectTail()
            lastOutputUs = timeUs
            decoded++
            progressed = true
        }
        // A nonempty final frame is accounted for before its attached EOS. An empty EOS has no PTS.
        if (endOfStream) {
            if (!inputEos || decoded != plan.sampleCount) invalidDirectTail()
            outputEos = true
            progressed = true
        }
        progressed
    }

    /** Only actual nonempty output and output EOS can form terminal evidence. Exactly once. */
    fun finish(): DirectVideoTailProof = guarded(allowOutputEos = true) {
        if (!outputEos || !inputEos || queued != plan.sampleCount || decoded != plan.sampleCount || lastOutputUs < 0)
            invalidDirectTail()
        val proof = DirectVideoTailProof(plan.samples.first().identity, plan.samples.last().identity,
            lastOutputUs, outputEos = true)
        if (!proof.matches(plan.conservation)) invalidDirectTail()
        finished = true
        proof
    }

    private fun validateNextInput(sample: DirectNativeSample) {
        if (inputEos || queued >= plan.sampleCount || sample != plan.samples[queued]) invalidDirectTail()
        boundedDirectTailSum(queuedBytes, sample.inputBytes)
    }

    private inline fun <T> guarded(allowOutputEos: Boolean = false, body: () -> T): T {
        failure?.let { throw it }
        try {
            checkpoint()
            if (finished || (outputEos && !allowOutputEos)) invalidDirectTail()
            return body()
        } catch (error: Throwable) { failure = error; throw error }
    }
}

/**
 * Direct-local native no-progress guard. It does not create/extend the original operation deadline:
 * [checkpoint] must enforce that original deadline and cancellation on every call. Only successfully
 * queued media/EOS or validated nonempty output/output EOS may call [madeProgress]. Empty/config/
 * format/dequeue events call [check] only. An overdue result cannot restart an expired allowance.
 */
class DirectDecodeProgress(
    startedNanos: Long,
    private val timeoutNanos: Long = NO_PROGRESS_TIMEOUT_NANOS,
    private val checkpoint: () -> Unit = {},
) {
    private var lastProgress = startedNanos
    private var lastObserved = startedNanos
    private var failure: Throwable? = null
    init { require(timeoutNanos in 1..NO_PROGRESS_TIMEOUT_NANOS) }

    fun check(nowNanos: Long) {
        failure?.let { throw it }
        try {
            checkpoint()
            // Subtraction handles the ordinary signed nanoTime wrap without overflowing a sum.
            val sinceObservation = nowNanos - lastObserved
            val sinceProgress = nowNanos - lastProgress
            if (sinceObservation < 0 || sinceProgress < 0 || sinceProgress >= timeoutNanos)
                throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
            lastObserved = nowNanos
        } catch (error: Throwable) { failure = error; throw error }
    }

    fun madeProgress(nowNanos: Long) { check(nowNanos); lastProgress = nowNanos }

    companion object { const val NO_PROGRESS_TIMEOUT_NANOS = 5_000_000_000L }
}

/** The adapter maps only recognized ByteBuffer YUV output formats; opaque/Surface formats reject. */
enum class DirectDecodedVideoRepresentation { YUV420_PLANAR, YUV420_SEMIPLANAR, YUV420_FLEXIBLE }

data class DirectDecodedVideoFormat(
    val mime: String, val width: Int, val height: Int,
    val cropLeft: Int, val cropRight: Int, val cropTop: Int, val cropBottom: Int,
    val representation: DirectDecodedVideoRepresentation,
)

/** A stable bounded raw output interpretation is necessary, but is not itself a decoded frame. */
class DirectVideoFormatState(
    private val encodedWidth: Int,
    private val encodedHeight: Int,
    private val checkpoint: () -> Unit = {},
) {
    private var first: DirectDecodedVideoFormat? = null
    private var formatEvent = false
    private var outputSeen = false
    private var failure: Throwable? = null

    init {
        require(encodedWidth in 2..1920 && encodedHeight in 2..1920 &&
            encodedWidth % 2 == 0 && encodedHeight % 2 == 0 && encodedWidth.toLong() * encodedHeight <= 1920L * 1080)
    }

    fun accept(actual: DirectDecodedVideoFormat, changeEvent: Boolean) {
        failure?.let { throw it }
        try {
            checkpoint()
            if (actual.mime != "video/raw" || actual.width !in 1..8192 || actual.height !in 1..8192 ||
                actual.width.toLong() * actual.height > 16_777_216 ||
                actual.cropLeft !in 0 until actual.width || actual.cropRight !in actual.cropLeft until actual.width ||
                actual.cropTop !in 0 until actual.height || actual.cropBottom !in actual.cropTop until actual.height ||
                actual.cropRight - actual.cropLeft + 1 != encodedWidth ||
                actual.cropBottom - actual.cropTop + 1 != encodedHeight ||
                (first != null && first != actual) || (changeEvent && (formatEvent || outputSeen)))
                throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
            first = actual
            if (changeEvent) formatEvent = true else outputSeen = true
        } catch (error: Throwable) { failure = error; throw error }
    }
}

private fun boundedDirectTailSum(previous: Long, bytes: Int): Long {
    if (bytes !in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES || previous < 0) invalidDirectTail()
    if (previous > DirectVideoTailPlan.MAX_TAIL_BYTES - bytes)
        throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
    return previous + bytes
}

private fun invalidDirectTail(): Nothing = throw SaveFailure(SaveError.INVALID_MEDIA)
