package com.easysavevideo.save

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Framing is caller-declared; sniffing and fallback could conceal source byte loss. */
enum class DirectMovNalFraming { AVCC4, ANNEX_B }

/** Original EBSP tuple, with defensive copies at both boundaries. Syntax/profile is checked elsewhere. */
class DirectMovAvcConfiguration internal constructor(sps: ByteArray, pps: ByteArray, val sha256: String) {
    private val sourceSps = sps.copyOf()
    private val sourcePps = pps.copyOf()
    val sps: ByteArray get() = sourceSps.copyOf()
    val pps: ByteArray get() = sourcePps.copyOf()
}

/** ASC transport model only. Neither this type nor its digest enables production AAC admission. */
class DirectMovAacConfiguration internal constructor(
    asc: ByteArray, val sampleRate: Int, val channelCount: Int, val sha256: String,
) {
    private val sourceAsc = asc.copyOf()
    val asc: ByteArray get() = sourceAsc.copyOf()
}

/** Shared raw-source/raw-output/callback byte protocol. Never repairs, trims, drops or unescapes. */
object DirectMovCanonical {
    const val MAX_NALS_PER_SAMPLE = 1_024
    private const val MAX_PARAMETER_SET_BYTES = 65_535

    fun avcSample(bytes: ByteArray, framing: DirectMovNalFraming, checkpoint: () -> Unit = {}): ByteArray {
        val nals = avcNals(bytes, framing, checkpoint)
        val length = nals.fold(0L) { size, nal -> size + 4 + nal.size }
        require(length in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES.toLong())
        val result = ByteArray(length.toInt())
        var position = 0
        for (nal in nals) {
            putU32(result, position, nal.size)
            position += 4
            nal.copyIntoChecked(result, position, checkpoint)
            position += nal.size
        }
        checkpoint()
        return result
    }

    /** Ordered, exact EBSP copies for the independent bounded SPS/PPS/AU syntax checker. */
    fun avcNals(bytes: ByteArray, framing: DirectMovNalFraming, checkpoint: () -> Unit = {}): List<ByteArray> {
        require(bytes.size in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        checkpoint()
        val extents = ArrayList<IntRange>()
        when (framing) {
            DirectMovNalFraming.AVCC4 -> {
                var position = 0
                while (position < bytes.size) {
                    checkpoint()
                    require(bytes.size - position >= 4)
                    val length = u32(bytes, position)
                    position += 4
                    require(length in 1..(bytes.size - position).toLong())
                    addNal(bytes, position, position + length.toInt(), extents, checkpoint)
                    position += length.toInt()
                }
            }
            DirectMovNalFraming.ANNEX_B -> {
                var position = 0
                while (position < bytes.size) {
                    checkpoint()
                    val prefix = startCodeLength(bytes, position)
                    require(prefix != 0)
                    val start = position + prefix
                    var end = start
                    while (end < bytes.size && startCodeLength(bytes, end) == 0) {
                        if ((end - start) % DIRECT_MOV_CHECKPOINT_BYTES == 0) checkpoint()
                        end++
                    }
                    addNal(bytes, start, end, extents, checkpoint)
                    position = end
                }
            }
        }
        require(extents.isNotEmpty())
        val canonicalLength = extents.fold(0L) { count, extent -> count + 4 + extent.last - extent.first + 1 }
        require(canonicalLength <= DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        return extents.map { bytes.copyRangeChecked(it.first, it.last + 1, checkpoint) }.also { checkpoint() }
    }

    fun avcConfiguration(sps: ByteArray, pps: ByteArray, checkpoint: () -> Unit = {}): DirectMovAvcConfiguration {
        checkpoint()
        require(sps.size in 4..MAX_PARAMETER_SET_BYTES && pps.size in 1..MAX_PARAMETER_SET_BYTES)
        require(sps.size.toLong() + pps.size <= DirectNormalizationLimits.MAX_CONFIGURATION_BYTES)
        validateNal(sps, 0, sps.size, checkpoint)
        validateNal(pps, 0, pps.size, checkpoint)
        require(sps[0].toInt() and 31 == 7 && pps[0].toInt() and 31 == 8)
        val hash = configurationDigest(DirectSampleKind.AVC_BASELINE, listOf(7 to sps, 8 to pps), checkpoint)
        return DirectMovAvcConfiguration(sps, pps, hash).also { checkpoint() }
    }

    /** The avcC payload, without its MP4 box header. Exactly one SPS and one PPS, no extension. */
    fun avcConfigurationFromAvcc(avcC: ByteArray, checkpoint: () -> Unit = {}): DirectMovAvcConfiguration {
        checkpoint()
        require(avcC.size in 11..DirectNormalizationLimits.MAX_CONFIGURATION_BYTES)
        require(unsigned(avcC[0]) == 1 && unsigned(avcC[4]) == 0xff && unsigned(avcC[5]) == 0xe1)
        var position = 6
        fun parameterSet(): ByteArray {
            require(avcC.size - position >= 2)
            val length = (unsigned(avcC[position]) shl 8) or unsigned(avcC[position + 1])
            position += 2
            require(length > 0 && length <= avcC.size - position)
            val value = avcC.copyRangeChecked(position, position + length, checkpoint)
            position += length
            return value
        }
        val sps = parameterSet()
        require(position < avcC.size && unsigned(avcC[position++]) == 1)
        val pps = parameterSet()
        require(position == avcC.size && sps.size >= 4)
        require(avcC[1] == sps[1] && avcC[2] == sps[2] && avcC[3] == sps[3])
        return avcConfiguration(sps, pps, checkpoint)
    }

    /** Pinned extractor init buffers each contain exactly one Annex-B NAL. */
    fun avcConfigurationFromInitializationData(initializationData: List<ByteArray>,
        checkpoint: () -> Unit = {}): DirectMovAvcConfiguration {
        checkpoint()
        require(initializationData.size == 2)
        require(initializationData.sumOf { it.size.toLong() } <= DirectNormalizationLimits.MAX_CONFIGURATION_BYTES)
        val sps = avcNals(initializationData[0], DirectMovNalFraming.ANNEX_B, checkpoint)
        val pps = avcNals(initializationData[1], DirectMovNalFraming.ANNEX_B, checkpoint)
        require(sps.size == 1 && pps.size == 1)
        return avcConfiguration(sps.single(), pps.single(), checkpoint)
    }

    /** Exactly the six approved two-byte AAC-LC ASCs; not AAC AU validity or support. */
    fun aacConfiguration(asc: ByteArray, checkpoint: () -> Unit = {}): DirectMovAacConfiguration {
        checkpoint()
        require(asc.size == 2)
        val rateAndChannels = when ((unsigned(asc[0]) shl 8) or unsigned(asc[1])) {
            0x1288 -> 32_000 to 1
            0x1290 -> 32_000 to 2
            0x1208 -> 44_100 to 1
            0x1210 -> 44_100 to 2
            0x1188 -> 48_000 to 1
            0x1190 -> 48_000 to 2
            else -> throw IllegalArgumentException("Unsupported Direct ASC")
        }
        return DirectMovAacConfiguration(asc, rateAndChannels.first, rateAndChannels.second,
            configurationDigest(DirectSampleKind.AAC_LC, listOf(5 to asc), checkpoint)).also { checkpoint() }
    }

    fun aacConfigurationFromInitializationData(initializationData: List<ByteArray>,
        checkpoint: () -> Unit = {}): DirectMovAacConfiguration {
        checkpoint()
        require(initializationData.size == 1)
        return aacConfiguration(initializationData.single(), checkpoint)
    }

    fun sampleSha256(canonical: ByteArray, checkpoint: () -> Unit = {}): String {
        require(canonical.size in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateChecked(canonical, checkpoint)
        return hex(digest.digest()).also { checkpoint() }
    }

    private fun addNal(bytes: ByteArray, start: Int, end: Int, extents: MutableList<IntRange>, checkpoint: () -> Unit) {
        require(extents.size < MAX_NALS_PER_SAMPLE)
        validateNal(bytes, start, end, checkpoint)
        extents += start until end
    }

    private fun validateNal(bytes: ByteArray, start: Int, end: Int, checkpoint: () -> Unit) {
        checkpoint()
        require(start in 0 until end && end <= bytes.size)
        require(unsigned(bytes[start]) and 0x80 == 0)
        require(unsigned(bytes[start]) and 31 in setOf(1, 5, 7, 8, 9))
        require(bytes[end - 1] != 0.toByte())
        var zeros = 0
        for (i in start until end) {
            if ((i - start) % DIRECT_MOV_CHECKPOINT_BYTES == 0) checkpoint()
            val value = unsigned(bytes[i])
            if (zeros >= 2) {
                require(value >= 3)
                if (value == 3) require(i + 1 < end && unsigned(bytes[i + 1]) <= 3)
            }
            zeros = if (value == 0) zeros + 1 else 0
        }
        checkpoint()
    }

    private fun startCodeLength(bytes: ByteArray, position: Int): Int = when {
        bytes.size - position >= 4 && bytes[position] == 0.toByte() && bytes[position + 1] == 0.toByte() &&
            bytes[position + 2] == 0.toByte() && bytes[position + 3] == 1.toByte() -> 4
        bytes.size - position >= 3 && bytes[position] == 0.toByte() && bytes[position + 1] == 0.toByte() &&
            bytes[position + 2] == 1.toByte() -> 3
        else -> 0
    }

    private fun configurationDigest(kind: DirectSampleKind, records: List<Pair<Int, ByteArray>>,
        checkpoint: () -> Unit): String {
        checkpoint()
        val encodingSize = 23L + 4 + records.sumOf { 5L + it.second.size }
        require(encodingSize <= DirectNormalizationLimits.MAX_CONFIGURATION_BYTES)
        val digest = newDigest(1, kind)
        digest.u32(records.size.toLong())
        for ((tag, bytes) in records) {
            digest.u8(tag)
            digest.u32(bytes.size.toLong())
            digest.updateChecked(bytes, checkpoint)
        }
        return hex(digest.digest()).also { checkpoint() }
    }

    internal fun newDigest(domain: Int, kind: DirectSampleKind): MessageDigest {
        require(domain in 1..4)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("ESV-DIRECT-REMUX".toByteArray(StandardCharsets.US_ASCII))
        digest.u8(0)
        digest.u32(1)
        digest.u8(domain)
        digest.u8(when (kind) { DirectSampleKind.AVC_BASELINE -> 1; DirectSampleKind.AAC_LC -> 2 })
        return digest
    }

    internal fun hex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) { append(alphabet[unsigned(byte) ushr 4]); append(alphabet[unsigned(byte) and 15]) }
        }
    }

    private fun u32(bytes: ByteArray, position: Int): Long =
        (unsigned(bytes[position]).toLong() shl 24) or (unsigned(bytes[position + 1]).toLong() shl 16) or
            (unsigned(bytes[position + 2]).toLong() shl 8) or unsigned(bytes[position + 3]).toLong()
    private fun unsigned(value: Byte): Int = value.toInt() and 0xff
    private fun putU32(bytes: ByteArray, position: Int, value: Int) {
        for (i in 0..3) bytes[position + i] = (value ushr (24 - 8 * i)).toByte()
    }
}

/** Final compact track result; video sync identity is the greatest admitted sync index. */
data class DirectMovAccumulation(val track: DirectTrackConservation, val precedingVideoSync: DirectSampleIdentity?)

/** Bounded, thread-confined incremental protocol. It retains only the last/sync identities, not samples. */
class DirectMovSampleAccumulator(private val clock: DirectMovClock, private val configurationSha256: String,
    private val checkpoint: () -> Unit = {}) {
    private val payload = DirectMovCanonical.newDigest(2, clock.kind)
    private val flags = DirectMovCanonical.newDigest(3, clock.kind)
    private val timing = DirectMovCanonical.newDigest(4, clock.kind)
    private var count = 0L
    private var totalBytes = 0L
    private var last: DirectSampleIdentity? = null
    private var precedingSync: DirectSampleIdentity? = null
    private var finished = false
    private var failed = false

    init {
        requireDirectHash(configurationSha256)
        timing.u32(clock.timescale.toLong())
        timing.u32(clock.sampleDeltaTicks)
    }

    fun append(canonical: ByteArray, timeUs: Long, sync: Int): DirectSampleIdentity {
        require(!finished && !failed && sync in 0..1)
        checkpoint()
        clock.requirePresentationTime(count, timeUs)
        require(canonical.size in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES)
        require(clock.kind != DirectSampleKind.AAC_LC || sync == 1)
        require(clock.kind != DirectSampleKind.AVC_BASELINE || count != 0L || sync == 1)
        if (clock.kind == DirectSampleKind.AVC_BASELINE) {
            // Validate canonical framing as well as the bound; codec syntax belongs to the app probe.
            DirectMovCanonical.avcNals(canonical, DirectMovNalFraming.AVCC4, checkpoint)
        }
        val newTotal = DirectMovClock.checkedAdd(totalBytes, canonical.size.toLong())
        require(newTotal <= DirectNormalizationLimits.MAX_BYTES)
        val identity = DirectSampleIdentity(count, timeUs, canonical.size,
            DirectMovCanonical.sampleSha256(canonical, checkpoint), sync)
        checkpoint()
        try {
            payload.u8(1); payload.u64(count); payload.u32(canonical.size.toLong())
            payload.updateChecked(canonical, checkpoint)
            flags.u8(1); flags.u64(count); flags.u8(sync)
            timing.u8(1); timing.u64(count); timing.u64(timeUs)
            checkpoint()
        } catch (failure: Throwable) {
            // A partial digest record is never usable after cancellation or another callback failure.
            failed = true
            throw failure
        }
        last = identity
        if (clock.kind == DirectSampleKind.AVC_BASELINE && sync == 1) precedingSync = identity
        count++
        totalBytes = newTotal
        return identity
    }

    fun finish(): DirectMovAccumulation {
        require(!finished && !failed && count == clock.sampleCount)
        checkpoint()
        payload.u8(255); payload.u64(count); payload.u64(totalBytes)
        flags.u8(255); flags.u64(count)
        timing.u8(255); timing.u64(count); timing.u64(clock.endpointTicks)
        timing.u64(clock.endpoint.minimumUs); timing.u64(clock.endpoint.maximumUs)
        finished = true
        return DirectMovAccumulation(DirectTrackConservation(clock.kind, configurationSha256,
            count, totalBytes, DirectMovCanonical.hex(payload.digest()), DirectMovCanonical.hex(flags.digest()),
            DirectMovCanonical.hex(timing.digest()), 0, requireNotNull(last), clock.endpoint), precedingSync)
    }
}

private fun MessageDigest.u8(value: Int) {
    require(value in 0..255)
    update(value.toByte())
}
private fun MessageDigest.u32(value: Long) {
    require(value in 0..0xffffffffL)
    for (shift in 24 downTo 0 step 8) update((value ushr shift).toByte())
}
private fun MessageDigest.u64(value: Long) {
    require(value >= 0)
    for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
}


private const val DIRECT_MOV_CHECKPOINT_BYTES = 4 * 1024

private fun ByteArray.copyIntoChecked(destination: ByteArray, destinationOffset: Int, checkpoint: () -> Unit) {
    var position = 0
    while (position < size) {
        checkpoint()
        val end = minOf(size, position + DIRECT_MOV_CHECKPOINT_BYTES)
        copyInto(destination, destinationOffset + position, position, end)
        position = end
    }
    checkpoint()
}

private fun ByteArray.copyRangeChecked(start: Int, end: Int, checkpoint: () -> Unit): ByteArray {
    checkpoint()
    val result = ByteArray(end - start)
    var position = start
    while (position < end) {
        checkpoint()
        val next = minOf(end, position + DIRECT_MOV_CHECKPOINT_BYTES)
        copyInto(result, position - start, position, next)
        position = next
    }
    checkpoint()
    return result
}

private fun MessageDigest.updateChecked(bytes: ByteArray, checkpoint: () -> Unit) {
    var position = 0
    while (position < bytes.size) {
        checkpoint()
        val length = minOf(DIRECT_MOV_CHECKPOINT_BYTES, bytes.size - position)
        update(bytes, position, length)
        position += length
    }
    checkpoint()
}
