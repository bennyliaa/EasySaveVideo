package com.easysavevideo.save

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Bounded evidence for the admitted progressive AVC/no-B + optional AAC-LC remux profile. */
object StreamProofLimits {
    const val MAX_SAMPLES = 250_000L
    const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
    const val MAX_DURATION_US = 24L * 60 * 60 * 1_000_000
    const val MAX_GAP_US = 250_000L
    // Pinned Media3 1.11.1 uses a 10 kHz movie edit-list clock (HALF_UP).
    const val AV_OFFSET_TOLERANCE_US = 102L
}
enum class StreamSampleKind { AVC, AAC }
enum class AvcSampleFraming { ANNEX_B, AVCC, AUTO }
enum class StreamEndpointRule { ESTABLISHED_CFR, BOUNDED_VFR, AAC_1024 }

/** Times are relative to this track's first sample, after the pinned muxer's clock conversion. */
data class StreamSampleIdentity(
    val index: Long, val presentationTimeUs: Long, val canonicalBytes: Int, val sha256: String, val flags: Int,
) {
    init {
        require(index in 0 until StreamProofLimits.MAX_SAMPLES)
        require(presentationTimeUs in 0..StreamProofLimits.MAX_DURATION_US)
        require(canonicalBytes in 1..StreamProofLimits.MAX_SAMPLE_BYTES && sha256.isProofHash())
        require(flags in 0..1)
    }
}

data class StreamSampleSequence(
    val kind: StreamSampleKind, val timescale: Int, val sampleCount: Long,
    val canonicalBytes: Long, val sequenceSha256: String, val lastSample: StreamSampleIdentity,
) {
    init {
        require(if (kind == StreamSampleKind.AVC) timescale == 90_000 else timescale in 8_000..96_000)
        require(sampleCount in 2..StreamProofLimits.MAX_SAMPLES)
        require(canonicalBytes in sampleCount..sampleCount * StreamProofLimits.MAX_SAMPLE_BYTES)
        require(sequenceSha256.isProofHash() && lastSample.index == sampleCount - 1)
        require(lastSample.presentationTimeUs > 0 && canonicalBytes >= lastSample.canonicalBytes + sampleCount - 1)
    }
    val timestampToleranceUs: Long get() = (1_000_000L + timescale - 1) / timescale + 1
}

data class StreamVideoTail(val lastSample: StreamSampleIdentity, val precedingSync: StreamSampleIdentity) {
    init {
        require(precedingSync.flags == 1 && precedingSync.index <= lastSample.index)
        require(precedingSync.presentationTimeUs <= lastSample.presentationTimeUs)
        require((precedingSync.index == lastSample.index) == (precedingSync == lastSample))
        require(precedingSync.index == 0L || precedingSync.presentationTimeUs > 0)
        require(precedingSync.index != 0L || precedingSync.presentationTimeUs == 0L)
    }
}

/** A presentation endpoint, never merely lastPTS-firstPTS. VFR makes no invented final duration. */
data class StreamEndpoint(val rule: StreamEndpointRule, val minimumUs: Long, val maximumUs: Long) {
    init {
        require(minimumUs > 0 && maximumUs in minimumUs..StreamProofLimits.MAX_DURATION_US)
        require(maximumUs - minimumUs <= StreamProofLimits.MAX_GAP_US)
        require(rule == StreamEndpointRule.BOUNDED_VFR || minimumUs == maximumUs)
    }
    fun contains(durationUs: Long, toleranceUs: Long = 0): Boolean = toleranceUs >= 0 && durationUs >= 0 &&
        durationUs >= minimumUs - toleranceUs && durationUs - toleranceUs <= maximumUs
}

data class StreamConservationContract(
    val video: StreamSampleSequence, val audio: StreamSampleSequence?, val audioStartOffsetUs: Long?,
    val videoTail: StreamVideoTail, val endpoint: StreamEndpoint,
    val audioChannelCount: Int? = null,
) {
    init {
        require(video.kind == StreamSampleKind.AVC && (audio == null || audio.kind == StreamSampleKind.AAC))
        require((audio == null) == (audioStartOffsetUs == null))
        require(if (audio == null) audioChannelCount == null else audioChannelCount in 1..2)
        require(audioStartOffsetUs == null || audioStartOffsetUs in -StreamProofLimits.MAX_GAP_US..StreamProofLimits.MAX_GAP_US)
        require(videoTail.lastSample == video.lastSample)
        require(endpoint.rule != StreamEndpointRule.AAC_1024)
        require(endpoint.minimumUs > video.lastSample.presentationTimeUs)
        require(endpoint.maximumUs - video.lastSample.presentationTimeUs <= StreamProofLimits.MAX_GAP_US)
        require(endpoint.rule != StreamEndpointRule.ESTABLISHED_CFR || video.sampleCount >= 3)
    }
    fun matches(actual: StreamConservationContract): Boolean = video == actual.video && audio == actual.audio &&
        videoTail == actual.videoTail && endpoint == actual.endpoint && audioChannelCount == actual.audioChannelCount &&
        ((audioStartOffsetUs == null && actual.audioStartOffsetUs == null) ||
            (audioStartOffsetUs != null && actual.audioStartOffsetUs != null &&
                abs(audioStartOffsetUs - actual.audioStartOffsetUs) <= StreamProofLimits.AV_OFFSET_TOLERANCE_US))
}

/** Written only after independent full sample scan and actual terminal output + output EOS. */
data class StreamTailProof(
    val precedingSync: StreamSampleIdentity, val lastSample: StreamSampleIdentity,
    val terminalOutputTimeUs: Long, val outputEos: Boolean,
) {
    init { require(terminalOutputTimeUs in 0..StreamProofLimits.MAX_DURATION_US) }
    fun matches(expected: StreamConservationContract): Boolean = outputEos &&
        precedingSync == expected.videoTail.precedingSync && lastSample == expected.videoTail.lastSample &&
        abs(terminalOutputTimeUs - expected.video.lastSample.presentationTimeUs) <= expected.video.timestampToleranceUs
}

/**
 * Constant-memory sequence proof. Binds every canonical payload byte, its length, sample index,
 * sync flag and PTS in integer track ticks, then the final sample count and byte count.
 *
 * Media3 1.11.1 Boxes.vuFromUs uses HALF_UP on absolute mux-input timestamps before subtracting
 * the first timestamp. For independent MP4 samples, subtract the observed first PTS first, then
 * recover integer ticks (extractor rounding is <=1 us). This cancels the edit-list offset instead
 * of enlarging a timestamp bucket. Existing preflight-versus-mux raw-PTS checks remain required.
 */
class StreamSampleAccumulator(
    private val kind: StreamSampleKind,
    private val timescale: Int = 90_000,
    private val timestampsAreMuxInput: Boolean = true,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var firstUs = -1L
    private var firstTicks = 0L
    private var previousUs = -1L
    private var previousTicks = -1L
    private var count = 0L
    private var bytes = 0L
    private var minDelta = Long.MAX_VALUE
    private var maxDelta = 0L
    private var last: StreamSampleIdentity? = null
    private var sync: StreamSampleIdentity? = null
    private var completed: StreamSampleSequence? = null
    init {
        require(if (kind == StreamSampleKind.AVC) timescale == 90_000 else timescale in 8_000..96_000)
        digest.update("ESV-SAMPLE-SEQUENCE-1".toByteArray(Charsets.US_ASCII))
        digest.update(ByteBuffer.allocate(8).putInt(kind.ordinal).putInt(timescale).array())
    }
    val firstPresentationTimeUs: Long get() { check(count > 0); return firstUs }
    fun add(timeUs: Long, flags: Int, payload: ByteBuffer, framing: AvcSampleFraming = AvcSampleFraming.AUTO): StreamSampleIdentity {
        val copy = payload.duplicate()
        require(copy.remaining() in 1..StreamProofLimits.MAX_SAMPLE_BYTES)
        return add(timeUs, flags, ByteArray(copy.remaining()).also { copy.get(it) }, framing)
    }
    fun add(timeUs: Long, flags: Int, payload: ByteArray, framing: AvcSampleFraming = AvcSampleFraming.AUTO): StreamSampleIdentity {
        check(completed == null)
        require(count < StreamProofLimits.MAX_SAMPLES && timeUs in 0..(Long.MAX_VALUE - 500_000) / 96_000)
        require(flags in 0..1 && payload.size in 1..StreamProofLimits.MAX_SAMPLE_BYTES)
        require(count == 0L || (timeUs > previousUs && timeUs - previousUs <= StreamProofLimits.MAX_GAP_US))
        val canonical = if (kind == StreamSampleKind.AVC) canonicalAvcSample(payload, framing) else payload
        if (count == 0L) {
            require(kind != StreamSampleKind.AVC || flags == 1)
            firstUs = timeUs; firstTicks = ticks(timeUs, timescale)
        }
        require(timeUs - firstUs <= StreamProofLimits.MAX_DURATION_US)
        val relativeTicks = if (timestampsAreMuxInput) ticks(timeUs, timescale) - firstTicks else ticks(timeUs - firstUs, timescale)
        require(count == 0L || relativeTicks > previousTicks)
        if (count > 0) {
            val delta = relativeTicks - previousTicks
            minDelta = minOf(minDelta, delta); maxDelta = maxOf(maxDelta, delta)
        }
        val identity = StreamSampleIdentity(count, micros(relativeTicks, timescale), canonical.size,
            MessageDigest.getInstance("SHA-256").digest(canonical).proofHex(), flags)
        digest.update(ByteBuffer.allocate(24).putLong(count).putLong(relativeTicks).putInt(flags).putInt(canonical.size).array())
        digest.update(canonical)
        count++; bytes += canonical.size; previousUs = timeUs; previousTicks = relativeTicks; last = identity
        if (flags == 1) sync = identity
        return identity
    }
    fun finish(): StreamSampleSequence {
        completed?.let { return it }
        require(count >= 2)
        digest.update(ByteBuffer.allocate(16).putLong(count).putLong(bytes).array())
        return StreamSampleSequence(kind, timescale, count, bytes, digest.digest().proofHex(), last!!).also { completed = it }
    }
    fun videoTail(): StreamVideoTail {
        require(kind == StreamSampleKind.AVC); finish()
        return StreamVideoTail(last!!, requireNotNull(sync))
    }
    fun endpoint(): StreamEndpoint {
        finish()
        val rule = when {
            kind == StreamSampleKind.AAC -> StreamEndpointRule.AAC_1024
            count >= 3 && minDelta == maxDelta -> StreamEndpointRule.ESTABLISHED_CFR
            else -> StreamEndpointRule.BOUNDED_VFR
        }
        val minimumTicks = previousTicks + if (kind == StreamSampleKind.AAC) 1024 else minDelta
        val maximumTicks = previousTicks + if (kind == StreamSampleKind.AAC) 1024 else maxDelta
        return StreamEndpoint(rule, micros(minimumTicks, timescale), micros(maximumTicks, timescale))
    }
}

/** Canonical AVC = each unchanged NAL preceded by a four-byte big-endian byte length. */
fun canonicalAvcSample(payload: ByteArray, framing: AvcSampleFraming = AvcSampleFraming.AUTO): ByteArray {
    require(payload.size in 1..StreamProofLimits.MAX_SAMPLE_BYTES)
    fun annex(): List<IntRange> {
        // Follow the admitted muxer's Annex-B framing: a run of three zero bytes ends a
        // NAL. One or two final zero bytes are retained, not guessed to be framing.
        fun marker(at: Int): Boolean = at + 3 <= payload.size && payload[at] == 0.toByte() &&
            payload[at + 1] == 0.toByte() && payload[at + 2].toInt() in 0..1
        val ranges = ArrayList<IntRange>()
        var at = 0
        while (at < payload.size) {
            val prefix = at
            while (at < payload.size && payload[at] == 0.toByte()) at++
            if (at == payload.size) { require(at - prefix >= 3 && ranges.isNotEmpty()); break }
            require(at - prefix >= 2 && payload[at] == 1.toByte())
            val begin = ++at; require(begin < payload.size)
            while (at < payload.size && !marker(at)) at++
            require(at > begin && ranges.size < 65_536)
            ranges.add(begin until at)
        }
        return ranges
    }
    fun avcc(): List<IntRange> {
        val ranges = ArrayList<IntRange>(); var at = 0
        while (at < payload.size) {
            require(payload.size - at >= 4)
            val size = ByteBuffer.wrap(payload, at, 4).int; at += 4
            require(size > 0 && size <= payload.size - at)
            require(ranges.size < 65_536); ranges.add(at until at + size); at += size
        }
        return ranges
    }
    fun canonical(ranges: List<IntRange>): ByteArray {
        require(ranges.isNotEmpty())
        var size = 0; var hasVcl = false
        for (range in ranges) {
            val header = payload[range.first].toInt() and 255
            require(header and 128 == 0 && header and 31 in 1..23)
            if (header and 31 in 1..5) hasVcl = true
            size += 4 + range.last - range.first + 1
            require(size <= StreamProofLimits.MAX_SAMPLE_BYTES)
        }
        require(hasVcl)
        val out = ByteBuffer.allocate(size)
        ranges.forEach { range -> out.putInt(range.last - range.first + 1); out.put(payload, range.first, range.last - range.first + 1) }
        return out.array()
    }
    return when (framing) {
        AvcSampleFraming.ANNEX_B -> canonical(annex())
        AvcSampleFraming.AVCC -> canonical(avcc())
        AvcSampleFraming.AUTO -> {
            val a = try { canonical(annex()) } catch (_: IllegalArgumentException) { null }
            val b = try { canonical(avcc()) } catch (_: IllegalArgumentException) { null }
            require(a == null || b == null || a.contentEquals(b))
            requireNotNull(a ?: b)
        }
    }
}
private fun ticks(us: Long, scale: Int): Long = (us * scale + 500_000) / 1_000_000
private fun micros(ticks: Long, scale: Int): Long = ticks * 1_000_000 / scale
private val proofHashPattern = Regex("[a-f0-9]{64}")
private const val HEX_DIGITS = "0123456789abcdef"
private fun String.isProofHash() = matches(proofHashPattern)
private fun ByteArray.proofHex(): String = CharArray(size * 2).also { output ->
    for (index in indices) {
        val value = this[index].toInt() and 255
        output[index * 2] = HEX_DIGITS[value ushr 4]
        output[index * 2 + 1] = HEX_DIGITS[value and 15]
    }
}.concatToString()
