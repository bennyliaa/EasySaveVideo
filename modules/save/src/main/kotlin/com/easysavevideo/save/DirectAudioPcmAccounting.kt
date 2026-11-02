package com.easysavevideo.save

import java.nio.ByteBuffer

/**
 * Ephemeral, aggregate PCM accounting consumed by the owned Direct AAC adapter. This neither runs a decoder
 * nor proves raw AAC syntax, individual AU frame counts, session isolation or native release. It is
 * not an independent verification/publication capability and cannot itself admit an audio track.
 *
 * The caller must independently check one of the six exact two-byte ASC configurations 1288,
 * 1290, 1208, 1210, 1188 or 1190: declared AOT 2/frameLengthFlag 0, no declared SBR/PS,
 * and independently reject container/native priming or padding trim. Elementary syntax is
 * interpreted by the reviewed trusted decoder policy; this does not prove every bit was consumed.
 * AAC_LC, a 1024-tick table delta and this constructor do not establish that ASC or complete AU
 * syntax. There is intentionally no ASC/parser argument here. Already verified input identities
 * must come from exact source/native byte conservation; this helper does not hash input bytes or
 * revalidate a middle identity against the compressed payload/configuration/timing digests.
 *
 * One serial owner calls [requireNextInput], performs the native queue, then immediately calls
 * [inputQueued] on success, before processing any output caused by that queue. Only successful
 * queue events grant causal frame credit; delayed callbacks may not borrow future input credit.
 * [inputEndOfStream] requires independently established exact extractor EOF and a successful
 * single empty EOS queue. A native failure aborts the enclosing inspection, not just that event.
 *
 * PCM observations can split or coalesce AUs. Matching aggregate counts/times cannot identify stale
 * or substituted PCM within available credit or prove each AU decoded to 1024 frames. The owning
 * adapter owns actual encoding/flags, buffer release, codec/session/flush isolation, the original
 * operation deadline and [DirectDecodeProgress]. Only successfully queued media/EOS or output
 * returning true count as progress. No buffers, whole-track manifests or native resources are kept.
 */
class DirectAudioPcmAccounting(
    private val audio: DirectTrackConservation,
    private val clock: DirectMovClock,
    private val channelCount: Int,
    private val checkpoint: () -> Unit = {},
) {
    private val bytesPerFrame: Int
    private val expectedPcmFrames: Long
    private val expectedPcmBytes: Long
    private var queuedAccessUnits = 0L
    private var queuedBytes = 0L
    private var outputBufferCount = 0L
    private var pcmFrames = 0L
    private var pcmBytes = 0L
    private var firstOutputTimeUs = -1L
    private var lastOutputTimeUs = -1L
    private var inputEos = false
    private var outputEos = false
    private var finished = false
    private var failure: Throwable? = null

    init {
        checkpoint()
        if (audio.kind != DirectSampleKind.AAC_LC || clock.kind != DirectSampleKind.AAC_LC ||
            channelCount !in 1..2 || clock.timescale !in DirectMovClock.AUDIO_SAMPLE_RATES ||
            clock.sampleCount != audio.sampleCount ||
            audio.sampleCount !in 2..DirectNormalizationLimits.MAX_AUDIO_SAMPLES) invalidInput()
        val expected = try { DirectMovClock.audio(clock.timescale, audio.sampleCount) }
            catch (_: IllegalArgumentException) { invalidInput() }
        if (clock.sampleDeltaTicks != expected.sampleDeltaTicks || clock.frameRate != expected.frameRate ||
            clock.endpointTicks != expected.endpointTicks || clock.endpoint != expected.endpoint ||
            clock.roundedDurationUs != expected.roundedDurationUs ||
            clock.headerDurationTicks != expected.headerDurationTicks ||
            audio.firstPresentationTimeUs != 0L || audio.lastSample.index != audio.sampleCount - 1 ||
            audio.lastSample.presentationTimeUs != expected.presentationTimeUs(audio.sampleCount - 1) ||
            audio.lastSample.flags != 1 || audio.endpoint != expected.endpoint) invalidInput()
        bytesPerFrame = channelCount * 2
        expectedPcmFrames = DirectMovClock.checkedMultiply(audio.sampleCount, 1_024)
        expectedPcmBytes = DirectMovClock.checkedMultiply(expectedPcmFrames, bytesPerFrame.toLong())
        checkpoint()
    }

    /** Validation only; this grants no frame credit and changes no input counters. */
    fun requireNextInput(sample: DirectNativeSample): Unit = guarded {
        validateNextInput(sample)
        checkpoint()
    }

    /** Call immediately after the corresponding native queue succeeds, before consuming output. */
    fun inputQueued(sample: DirectNativeSample): Unit = guarded {
        val nextBytes = validateNextInput(sample)
        val nextCount = DirectMovClock.checkedAdd(queuedAccessUnits, 1)
        checkpoint()
        queuedBytes = nextBytes
        queuedAccessUnits = nextCount
    }

    /** Records independently verified exact EOF plus a successfully queued empty input EOS. */
    fun inputEndOfStream(): Unit = guarded {
        if (inputEos || queuedAccessUnits != audio.sampleCount || queuedBytes != audio.canonicalBytes)
            invalidInput()
        checkpoint()
        inputEos = true
    }

    /**
     * Reads the absolute [offset]/[size] extent without changing the caller's cursor. The supplied
     * signedPcm16 flag describes actual observed encoding, not a requested/coerced codec setting.
     * Empty EOS has no meaningful media timestamp. Config/empty observations return false and
     * never renew a no-progress allowance. Real PCM (including silence) or valid EOS return true.
     */
    fun output(
        buffer: ByteBuffer?,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        signedPcm16: Boolean,
        codecConfig: Boolean,
        endOfStream: Boolean,
    ): Boolean = guarded {
        if (codecConfig && endOfStream) unavailable()
        val extent = outputExtent(buffer, offset, size)
        val media = size > 0 && !codecConfig
        var nextFrames = pcmFrames
        var nextBytes = pcmBytes
        var nextBuffers = outputBufferCount
        if (media) {
            if (mime != "audio/raw" || sampleRate != clock.timescale ||
                channelCount != this.channelCount || !signedPcm16 || size % bytesPerFrame != 0 ||
                queuedAccessUnits == 0L) unavailable()
            val expectedTime = DirectMovClock.floorUs(pcmFrames, clock.timescale)
            if (presentationTimeUs < 0 ||
                presentationTimeUs < expectedTime - DirectNormalizationLimits.MAX_ROUNDING_US ||
                presentationTimeUs > expectedTime + DirectNormalizationLimits.MAX_ROUNDING_US) unavailable()
            nextFrames = DirectMovClock.checkedAdd(pcmFrames, (size / bytesPerFrame).toLong())
            nextBytes = DirectMovClock.checkedAdd(pcmBytes, size.toLong())
            nextBuffers = DirectMovClock.checkedAdd(outputBufferCount, 1)
            val queuedFrameCredit = DirectMovClock.checkedMultiply(queuedAccessUnits, 1_024)
            if (nextFrames > queuedFrameCredit || nextFrames > expectedPcmFrames ||
                nextBytes > expectedPcmBytes || nextBuffers > expectedPcmFrames) unavailable()
            readExtent(requireNotNull(extent))
        }
        if (endOfStream) {
            if (!inputEos || queuedAccessUnits != audio.sampleCount || queuedBytes != audio.canonicalBytes ||
                nextBuffers == 0L || nextFrames != expectedPcmFrames || nextBytes != expectedPcmBytes ||
                observedEndpoint(nextFrames) != audio.endpoint) unavailable()
        }
        // Commit the complete observation only after its format, extent, clock, counts and EOS pass.
        checkpoint()
        if (media) {
            if (outputBufferCount == 0L) firstOutputTimeUs = presentationTimeUs
            lastOutputTimeUs = presentationTimeUs
            pcmFrames = nextFrames
            pcmBytes = nextBytes
            outputBufferCount = nextBuffers
        }
        if (endOfStream) outputEos = true
        media || endOfStream
    }

    /** Exactly one successful result after measured PCM and both EOS events, never a durable proof. */
    fun finish(): Result = guarded(allowOutputEos = true) {
        if (!inputEos || !outputEos || queuedAccessUnits != audio.sampleCount ||
            queuedBytes != audio.canonicalBytes || outputBufferCount == 0L ||
            pcmFrames != expectedPcmFrames || pcmBytes != expectedPcmBytes) unavailable()
        val endpoint = observedEndpoint(pcmFrames)
        if (endpoint != audio.endpoint) unavailable()
        val result = Result(queuedAccessUnits, outputBufferCount, pcmFrames, pcmBytes,
            firstOutputTimeUs, lastOutputTimeUs, endpoint, outputEos = true)
        checkpoint()
        finished = true
        result
    }

    /** Transient aggregate observations only; no serialization or publication authority. */
    data class Result(
        val queuedAccessUnits: Long,
        val outputBufferCount: Long,
        val pcmFrames: Long,
        val pcmBytes: Long,
        val firstOutputTimeUs: Long,
        val lastOutputTimeUs: Long,
        val endpoint: DirectTrackEndpoint,
        val outputEos: Boolean,
    )

    private fun validateNextInput(sample: DirectNativeSample): Long {
        val identity = sample.identity
        if (inputEos || queuedAccessUnits >= audio.sampleCount || identity.index != queuedAccessUnits ||
            identity.presentationTimeUs != clock.presentationTimeUs(queuedAccessUnits) || identity.flags != 1 ||
            identity.canonicalBytes !in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES ||
            sample.inputBytes != identity.canonicalBytes ||
            (queuedAccessUnits == audio.sampleCount - 1 && identity != audio.lastSample)) invalidInput()
        val nextBytes = DirectMovClock.checkedAdd(queuedBytes, sample.inputBytes.toLong())
        if (nextBytes > audio.canonicalBytes || nextBytes > DirectNormalizationLimits.MAX_BYTES) invalidInput()
        return nextBytes
    }

    /** Capture the native valid limit; never expand it to capacity, including for config buffers. */
    private fun outputExtent(buffer: ByteBuffer?, offset: Int, size: Int): ByteBuffer? {
        if (offset < 0 || size < 0 || size > DirectNormalizationLimits.MAX_SAMPLE_BYTES) unavailable()
        if (buffer == null) {
            if (size != 0) unavailable()
            return null
        }
        val extent = try { buffer.duplicate() } catch (_: RuntimeException) { unavailable() }
        val end = offset.toLong() + size.toLong()
        if (end > extent.limit().toLong() || end > extent.capacity().toLong()) unavailable()
        try { extent.position(offset); extent.limit(end.toInt()) }
        catch (_: RuntimeException) { unavailable() }
        return extent
    }

    private fun readExtent(extent: ByteBuffer) {
        // One fixed-size scratch chunk, never an allocation proportional to a track or large buffer.
        val chunk = ByteArray(minOf(extent.remaining(), 8 * 1024))
        while (extent.hasRemaining()) {
            checkpoint()
            try { extent.get(chunk, 0, minOf(extent.remaining(), chunk.size)) }
            catch (_: RuntimeException) { unavailable() }
        }
    }

    private fun observedEndpoint(frames: Long) = DirectTrackEndpoint(
        DirectMovClock.floorUs(frames, clock.timescale), DirectMovClock.ceilUs(frames, clock.timescale),
    )

    private inline fun <T> guarded(allowOutputEos: Boolean = false, body: () -> T): T {
        failure?.let { throw it }
        try {
            checkpoint()
            // Match adjacent Direct state machines for programmer lifecycle misuse.
            if (finished || (outputEos && !allowOutputEos)) invalidInput()
            return body()
        } catch (error: Throwable) { failure = error; throw error }
    }

    private fun invalidInput(): Nothing = throw SaveFailure(SaveError.INVALID_MEDIA)
    private fun unavailable(): Nothing = throw SaveFailure(SaveError.VERIFICATION_UNAVAILABLE)
}
