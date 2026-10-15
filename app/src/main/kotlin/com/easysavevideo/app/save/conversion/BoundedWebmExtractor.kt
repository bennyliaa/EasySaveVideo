package com.easysavevideo.app.save.conversion

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.util.ArrayDeque

/**
 * Single policy factory for this profile. Only its exact guarded owned input is currently accepted.
 * Loading additionally requires the registered paired bridge's single-use sequential token.
 * An arbitrary ExtractorInput cannot prove source identity.
 */
internal class WebmExtractorFactory(private val binding: WebmInputBinding) {
    private var issued = false
    fun create(): BoundedWebmExtractor {
        requireWebm(!issued, WebmProbeFailure.INCOMPLETE)
        issued = true
        return BoundedWebmExtractor(binding)
    }
}

/** Composition is required because MatroskaExtractor.read/sniff are final. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class BoundedWebmExtractor internal constructor(private val binding: WebmInputBinding) : Extractor {
    private val policy = Policy(binding)
    private var input: WebmGuardedInput? = null
    private var output: ObservedOutput? = null
    private var readStarted = false
    private var eof = false
    private var released = false
    fun newInput(): WebmGuardedInput {
        requireWebm(input == null, WebmProbeFailure.FOREIGN_INPUT)
        return binding.newInput(policy::boundary).also { input = it; policy.input = it }
    }
    internal fun newInput(owned: WebmSequentialInput): WebmGuardedInput {
        requireWebm(input == null, WebmProbeFailure.FOREIGN_INPUT)
        return binding.newInput(policy::boundary, owned).also { input = it; policy.input = it }
    }
    private fun owned(candidate: ExtractorInput): WebmGuardedInput {
        binding.check()
        val exact = input
        requireWebm(!released && exact != null && candidate === exact && exact.binding === binding,
            WebmProbeFailure.FOREIGN_INPUT)
        return requireNotNull(exact)
    }
    override fun sniff(input: ExtractorInput): Boolean = policy.sniff(owned(input))
    override fun init(output: ExtractorOutput) {
        binding.check(); requireWebm(this.output == null, WebmProbeFailure.INCOMPLETE)
        this.output = ObservedOutput(binding, output, policy).also { policy.init(it) }
    }
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val exact = owned(input); readStarted = true
        val result = policy.read(exact, seekPosition)
        requireWebm(result != Extractor.RESULT_SEEK, WebmProbeFailure.SEEK_UNSUPPORTED)
        if (result == Extractor.RESULT_END_OF_INPUT) eof = true
        binding.check()
        return result
    }
    override fun seek(position: Long, timeUs: Long) {
        binding.check()
        // No resets to discard retained unknown masters. Only an initial zero seek is supported.
        requireWebm(!readStarted && position == 0L && timeUs == 0L, WebmProbeFailure.SEEK_UNSUPPORTED)
    }
    override fun release() { if (!released) { policy.release(); released = true } }
    fun finish(): WebmTransportFacts {
        binding.check()
        val exact = input ?: throw WebmProbeException(WebmProbeFailure.INCOMPLETE)
        val observed = output ?: throw WebmProbeException(WebmProbeFailure.INCOMPLETE)
        requireWebm(eof && exact.position == binding.extent, WebmProbeFailure.INCOMPLETE)
        policy.finish(observed)
        return WebmTransportFacts(binding.extent, policy.durationUs, observed.facts(),
            policy.maximumDepth, policy.masters.size, policy.unknownEndCallbacks,
            binding.headerChecks, binding.payloadDelegations, observed.demuxerDurationUs, policy.timecodeScaleNs)
    }

    private data class Master(val id: Int, val end: Long, val unknown: Boolean)
    private class Policy(val binding: WebmInputBinding) : MatroskaExtractor(
        SubtitleParser.Factory.UNSUPPORTED, FLAG_DISABLE_SEEK_FOR_CUES or FLAG_DISABLE_HAGC_METADATA,
    ) {
        lateinit var input: WebmGuardedInput
        val masters = ArrayDeque<Master>()
        var maximumDepth = 0
        var unknownEndCallbacks = 0
        var durationUs: Long? = null
        val videoDefaultDurationsNs = hashMapOf<Int, Long>()
        var timecodeScaleNs = 1_000_000L; private set
        private var durationTicks: Double? = null
        private var segmentCount = 0
        private var tracksCount = 0
        private var trackEntries = 0
        private var completedEntries = 0
        private var videoEntries = 0
        private var audioEntries = 0
        private var docType = false
        private var tracksDone = false
        private var clusterStarted = false
        private var lastId = 0
        private var rawType = 0
        private var configBytes = 0L
        private var metadataBytes = 0L
        private val trackNumbers = hashSetOf<Int>()
        private val fields = hashSetOf<Int>()
        private val globalFields = hashSetOf<Int>()
        fun boundary(): Long {
            var end = binding.extent
            for (m in masters) if (!m.unknown) end = minOf(end, m.end)
            return end
        }
        private fun current(id: Int) = masters.peek()?.id == id
        private fun scalarPosition() = requireWebm(input.position <= boundary(), WebmProbeFailure.MASTER_EXTENT)
        private fun unique(id: Int) = requireWebm(fields.add(id), WebmProbeFailure.DUPLICATE_FIELD)
        override fun getElementType(id: Int): Int {
            val original = super.getElementType(id)
            binding.event(); lastId = id; rawType = original
            requireWebm(id !in PROTECTED_IDS, WebmProbeFailure.PROTECTED_MEDIA)
            requireWebm(id !in UNSUPPORTED_IDS, WebmProbeFailure.UNSUPPORTED_ELEMENT)
            // Colour is a bounded schema, not an opaque metadata subtree. Only global Void/CRC
            // may accompany the explicitly admitted scalars; no unrelated track fields leak in.
            if (current(COLOUR)) requireWebm(id in COLOUR_FIELDS || id == COLOUR || id == 0xEC || id == 0xBF,
                WebmProbeFailure.UNSUPPORTED_ELEMENT)
            if (id in SKIPPED_METADATA) return EbmlProcessor.ELEMENT_TYPE_BINARY
            if (id in EXTRA_HEADER_INTS || id == FLAG_INTERLACED) return EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT
            requireWebm(id in ALLOWED_IDS, WebmProbeFailure.UNSUPPORTED_ELEMENT)
            return if (original == EbmlProcessor.ELEMENT_TYPE_STRING || original == EbmlProcessor.ELEMENT_TYPE_UNKNOWN)
                EbmlProcessor.ELEMENT_TYPE_BINARY else original
        }
        override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
            val h = WebmElementHeader.read(binding, id, contentPosition)
            requireWebm(h.size == contentSize, WebmProbeFailure.HEADER_CALLBACK)
            requireWebm(!h.unknown || id == SEGMENT || id == CLUSTER, WebmProbeFailure.UNKNOWN_SIZE)
            requireWebm(contentPosition >= 0 && contentPosition <= boundary(), WebmProbeFailure.MASTER_EXTENT)
            if (!h.unknown) requireWebm(contentSize <= boundary() - contentPosition, WebmProbeFailure.MASTER_EXTENT)
            when (id) {
                EBML -> requireWebm(masters.isEmpty() && segmentCount == 0, WebmProbeFailure.ELEMENT_CONTEXT)
                SEGMENT -> requireWebm(masters.isEmpty() && docType && ++segmentCount == 1, WebmProbeFailure.ELEMENT_CONTEXT)
                INFO -> requireWebm(current(SEGMENT) && !clusterStarted, WebmProbeFailure.ELEMENT_CONTEXT)
                TRACKS -> requireWebm(current(SEGMENT) && !clusterStarted && ++tracksCount == 1, WebmProbeFailure.ELEMENT_CONTEXT)
                TRACK_ENTRY -> {
                    requireWebm(current(TRACKS), WebmProbeFailure.ELEMENT_CONTEXT)
                    requireWebm(++trackEntries <= 2, WebmProbeFailure.TRACK_LIMIT); fields.clear()
                }
                VIDEO, AUDIO -> requireWebm(current(TRACK_ENTRY), WebmProbeFailure.ELEMENT_CONTEXT)
                COLOUR -> {
                    requireWebm(current(VIDEO), WebmProbeFailure.ELEMENT_CONTEXT)
                    unique(id)
                }
                CLUSTER -> {
                    requireWebm(tracksDone && (current(SEGMENT) || current(CLUSTER)), WebmProbeFailure.TRACK_INVENTORY)
                    clusterStarted = true
                }
                BLOCK_GROUP -> requireWebm(current(CLUSTER), WebmProbeFailure.ELEMENT_CONTEXT)
                else -> throw WebmProbeException(WebmProbeFailure.UNSUPPORTED_ELEMENT)
            }
            masters.push(Master(id, if (h.unknown) -1 else contentPosition + contentSize, h.unknown))
            maximumDepth = maxOf(maximumDepth, masters.size)
            requireWebm(masters.size <= binding.limits.maxOutstandingMasters, WebmProbeFailure.MASTER_DEPTH)
            super.startMasterElement(id, contentPosition, contentSize)
        }
        override fun endMasterElement(id: Int) {
            binding.check()
            requireWebm(masters.peek()?.id == id, WebmProbeFailure.ELEMENT_CONTEXT)
            val m = masters.pop() // Only the actual pinned callback pops the physical mirror.
            if (m.unknown) {
                unknownEndCallbacks++
                requireWebm(input.position == binding.extent, WebmProbeFailure.UNKNOWN_NUMERIC_END)
            } else requireWebm(input.position == m.end, WebmProbeFailure.MASTER_EXTENT)
            scalarPosition()
            if (id == TRACK_ENTRY) {
                requireWebm(fields.containsAll(listOf(TRACK_NUMBER, TRACK_TYPE, CODEC_ID)), WebmProbeFailure.TRACK_INVENTORY)
                val track = getCurrentTrack(id)
                requireWebm((COLOUR !in fields && FLAG_INTERLACED !in fields) || track.type == C.TRACK_TYPE_VIDEO,
                    WebmProbeFailure.TRACK_PROFILE)
                requireWebm(AUDIO_BIT_DEPTH !in fields ||
                    (track.type == C.TRACK_TYPE_AUDIO && track.codecId == "A_OPUS"), WebmProbeFailure.TRACK_PROFILE)
                requireWebm(track.number > 0 && trackNumbers.add(track.number), WebmProbeFailure.TRACK_ID)
                // Pinned Java fields are MonotonicNonNull but are actually null for clear tracks.
                // Keep nullable locals so Kotlin does not assert or assume they are already nonnull.
                val crypto: TrackOutput.CryptoData? = track.cryptoData
                val drm: androidx.media3.common.DrmInitData? = track.drmInitData
                requireWebm(!track.hasContentEncryption && crypto == null && drm == null,
                    WebmProbeFailure.PROTECTED_MEDIA)
                when {
                    track.type == C.TRACK_TYPE_VIDEO && track.codecId == "V_VP8" ->
                        requireWebm(++videoEntries == 1, WebmProbeFailure.TRACK_PROFILE)
                    track.type == C.TRACK_TYPE_AUDIO && track.codecId == "A_OPUS" ->
                        requireWebm(++audioEntries == 1 && CODEC_PRIVATE in fields, WebmProbeFailure.TRACK_PROFILE)
                    else -> throw WebmProbeException(WebmProbeFailure.TRACK_PROFILE)
                }
                if (track.type == C.TRACK_TYPE_VIDEO && DEFAULT_DURATION in fields) {
                    // Read the validated pinned Track before super clears the current entry.
                    videoDefaultDurationsNs[track.number] = track.defaultSampleDurationNs.toLong()
                }
                completedEntries++
            }
            if (id == COLOUR) requireWebm(COLOUR_FIELDS.any { it in fields }, WebmProbeFailure.FIELD_VALUE)
            if (id == INFO) durationTicks?.let {
                val value = it * timecodeScaleNs / 1000.0
                requireWebm(value.isFinite() && value > 0 && value <= MAX_DURATION_US, WebmProbeFailure.FIELD_VALUE)
                durationUs = value.toLong()
            }
            super.endMasterElement(id)
            if (id == TRACKS) tracksDone = true
        }
        override fun integerElement(id: Int, value: Long) {
            binding.check(); scalarPosition()
            when (id) {
                0x4286, 0x42F7 -> requireWebm(value == 1L, WebmProbeFailure.FIELD_VALUE)
                0x4287, 0x4285 -> requireWebm(value in 1..4, WebmProbeFailure.FIELD_VALUE)
                0x42F2 -> requireWebm(value in 1..4, WebmProbeFailure.FIELD_VALUE)
                0x42F3 -> requireWebm(value in 1..8, WebmProbeFailure.FIELD_VALUE)
                TRACK_NUMBER -> { unique(id); requireWebm(value in 1..Int.MAX_VALUE.toLong(), WebmProbeFailure.TRACK_ID) }
                TRACK_TYPE -> { unique(id); requireWebm(value == 1L || value == 2L, WebmProbeFailure.TRACK_PROFILE) }
                0x73C5 -> { unique(id); requireWebm(value > 0, WebmProbeFailure.FIELD_VALUE) }
                0xB0, 0xBA -> { unique(id); requireWebm(value in 2..1920 && value % 2 == 0L, WebmProbeFailure.TRACK_PROFILE) }
                0x54B0, 0x54BA -> { unique(id); requireWebm(value in 2..1920, WebmProbeFailure.TRACK_PROFILE) }
                0x54B2 -> { unique(id); requireWebm(value == 0L, WebmProbeFailure.TRACK_PROFILE) }
                0x9F -> { unique(id); requireWebm(value in 1..2, WebmProbeFailure.TRACK_PROFILE) }
                0x88, 0x55AA -> requireWebm(value in 0..1, WebmProbeFailure.FIELD_VALUE)
                AUDIO_BIT_DEPTH -> {
                    requireWebm(current(AUDIO), WebmProbeFailure.ELEMENT_CONTEXT)
                    unique(id); requireWebm(value in 1L..32L, WebmProbeFailure.TRACK_PROFILE)
                }
                FLAG_INTERLACED -> {
                    requireWebm(current(VIDEO), WebmProbeFailure.ELEMENT_CONTEXT)
                    unique(id); requireWebm(value == 0L || value == 2L, WebmProbeFailure.TRACK_PROFILE)
                }
                COLOUR_RANGE, COLOUR_TRANSFER, COLOUR_PRIMARIES -> {
                    requireWebm(current(COLOUR), WebmProbeFailure.ELEMENT_CONTEXT)
                    unique(id)
                    when (id) {
                        COLOUR_RANGE -> requireWebm(value == 1L || value == 2L, WebmProbeFailure.TRACK_PROFILE)
                        COLOUR_TRANSFER -> requireWebm((value == 1L || value == 6L) &&
                            ColorInfo.isoTransferCharacteristicsToColorTransfer(value.toInt()) == C.COLOR_TRANSFER_SDR,
                            WebmProbeFailure.TRACK_PROFILE)
                        COLOUR_PRIMARIES -> requireWebm((value == 1L || value == 5L || value == 6L) &&
                            ColorInfo.isoColorPrimariesToColorSpace(value.toInt()) in
                                setOf(C.COLOR_SPACE_BT709, C.COLOR_SPACE_BT601), WebmProbeFailure.TRACK_PROFILE)
                    }
                }
                DEFAULT_DURATION -> {
                    requireWebm(current(TRACK_ENTRY), WebmProbeFailure.ELEMENT_CONTEXT)
                    unique(id); requireWebm(value in 1..Int.MAX_VALUE.toLong(), WebmProbeFailure.FIELD_VALUE)
                }
                0x56AA, 0x56BB -> { unique(id); requireWebm(value in 0..MAX_BOUNDARY_NS, WebmProbeFailure.FIELD_VALUE) }
                0x75A2 -> requireWebm(value in 0..MAX_BOUNDARY_NS, WebmProbeFailure.FIELD_VALUE)
                0x2AD7B1 -> {
                    requireWebm(current(INFO) && globalFields.add(id) && value in 1..1_000_000_000,
                        WebmProbeFailure.FIELD_VALUE); timecodeScaleNs = value
                }
                0xE7, 0x9B, 0xFB -> requireWebm(value >= 0 && value <= Long.MAX_VALUE / timecodeScaleNs &&
                    value * timecodeScaleNs / 1000 <= MAX_DURATION_US, WebmProbeFailure.FIELD_VALUE)
                else -> throw WebmProbeException(WebmProbeFailure.UNSUPPORTED_ELEMENT)
            }
            super.integerElement(id, value)
        }
        override fun floatElement(id: Int, value: Double) {
            binding.check(); scalarPosition(); requireWebm(value.isFinite(), WebmProbeFailure.FIELD_VALUE)
            when (id) {
                0x4489 -> {
                    requireWebm(current(INFO) && globalFields.add(id) && value > 0, WebmProbeFailure.FIELD_VALUE)
                    durationTicks = value
                }
                0xB5 -> { unique(id); requireWebm(value == 48_000.0, WebmProbeFailure.TRACK_PROFILE) }
                else -> throw WebmProbeException(WebmProbeFailure.UNSUPPORTED_ELEMENT)
            }
            super.floatElement(id, value)
        }
        override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
            binding.check()
            requireWebm(id == lastId && input === this.input, WebmProbeFailure.FOREIGN_INPUT)
            val h = WebmElementHeader.read(binding, id, input.position)
            requireWebm(!h.unknown, WebmProbeFailure.UNKNOWN_SIZE)
            requireWebm(h.size <= boundary() - input.position, WebmProbeFailure.TRUNCATED)
            requireWebm(h.size <= Int.MAX_VALUE && h.size == contentSize.toLong(), WebmProbeFailure.HEADER_CALLBACK)
            if (id == DOC_TYPE || id == CODEC_ID) {
                requireWebm(contentSize in 1..binding.limits.maxIdentifierBytes, WebmProbeFailure.IDENTIFIER)
                if (id == CODEC_ID) unique(id)
                val bytes = ByteArray(contentSize); input.readFully(bytes, 0, contentSize)
                requireWebm(bytes.all { it.toInt() in 0x20..0x7E }, WebmProbeFailure.IDENTIFIER)
                val value = bytes.toString(Charsets.US_ASCII)
                if (id == DOC_TYPE) {
                    requireWebm(current(EBML) && globalFields.add(id) && value == "webm", WebmProbeFailure.IDENTIFIER)
                    docType = true
                }
                super.stringElement(id, value); return
            }
            if (id in SKIPPED_METADATA || rawType == EbmlProcessor.ELEMENT_TYPE_STRING ||
                rawType == EbmlProcessor.ELEMENT_TYPE_UNKNOWN) {
                requireWebm(contentSize <= binding.limits.maxMetadataBytes - metadataBytes, WebmProbeFailure.METADATA_LIMIT)
                metadataBytes += contentSize
                input.skipFully(contentSize); return
            }
            if (id == CODEC_PRIVATE) {
                unique(id)
                requireWebm(contentSize <= binding.limits.maxConfigurationBytes &&
                    contentSize <= binding.limits.maxAllConfigurationBytes - configBytes, WebmProbeFailure.CONFIG_LIMIT)
                configBytes += contentSize
            } else if (id == SIMPLE_BLOCK || id == BLOCK) {
                requireWebm(tracksDone && contentSize in 4..binding.limits.maxSampleBytes, WebmProbeFailure.SAMPLE_LIMIT)
            } else throw WebmProbeException(WebmProbeFailure.UNSUPPORTED_ELEMENT)
            binding.payloadDelegations++
            super.binaryElement(id, contentSize, input)
        }
        fun finish(output: ObservedOutput) {
            requireWebm(docType && segmentCount == 1 && tracksDone && completedEntries == trackEntries &&
                videoEntries == 1 && output.endTracksCalls == 1 && output.sinks.size == completedEntries,
                WebmProbeFailure.TRACK_INVENTORY)
            for (m in masters) requireWebm(m.unknown || (m.id == SEGMENT && m.end == binding.extent), WebmProbeFailure.INCOMPLETE)
            for (sink in output.sinks.values) sink.finish()
        }
    }

    private class ObservedOutput(val binding: WebmInputBinding, val delegate: ExtractorOutput, val policy: Policy) : ExtractorOutput {
        val sinks = linkedMapOf<Int, Sink>()
        var endTracksCalls = 0
        private var seekMapSeen = false
        var demuxerDurationUs: Long? = null; private set
        override fun track(id: Int, type: Int): TrackOutput {
            binding.check()
            requireWebm(endTracksCalls == 0 && id > 0 && id !in sinks && sinks.size < 2, WebmProbeFailure.TRACK_INVENTORY)
            return Sink(binding, id, type, delegate.track(id, type)).also { sinks[id] = it }
        }
        override fun endTracks() {
            binding.check(); requireWebm(++endTracksCalls == 1, WebmProbeFailure.TRACK_INVENTORY)
            delegate.endTracks()
        }
        override fun seekMap(seekMap: SeekMap) {
            binding.check()
            val value = seekMap.durationUs
            requireWebm(value == C.TIME_UNSET || value in 1..MAX_DURATION_US, WebmProbeFailure.FIELD_VALUE)
            val duration = if (value == C.TIME_UNSET) null else value
            requireWebm(!seekMapSeen || demuxerDurationUs == duration, WebmProbeFailure.FIELD_VALUE)
            seekMapSeen = true; demuxerDurationUs = duration
            delegate.seekMap(seekMap)
        }
        fun facts(): List<WebmTrackTransport> = sinks.values.map { it.facts(policy.videoDefaultDurationsNs[it.id]) }
    }
    private class Sink(val binding: WebmInputBinding, val id: Int, val type: Int, val delegate: TrackOutput) : TrackOutput {
        private var format: Format? = null
        private var pending = 0L
        private var samples = 0L
        private var firstPts: Long? = null
        private var lastPts: Long? = null
        private var supplementalBytes = 0L
        override fun format(format: Format) {
            binding.check()
            requireWebm(format.drmInitData == null && format.cryptoType == C.CRYPTO_TYPE_NONE, WebmProbeFailure.PROTECTED_MEDIA)
            when (type) {
                C.TRACK_TYPE_VIDEO -> requireWebm(format.sampleMimeType == MimeTypes.VIDEO_VP8 &&
                    format.width in 2..1920 && format.height in 2..1920 && format.width % 2 == 0 && format.height % 2 == 0 &&
                    format.width.toLong() * format.height <= 1920L * 1080 && format.rotationDegrees == 0 &&
                    format.pixelWidthHeightRatio == 1f && (format.colorInfo == null ||
                    format.colorInfo!!.colorTransfer in setOf(Format.NO_VALUE, C.COLOR_TRANSFER_SDR)), WebmProbeFailure.TRACK_PROFILE)
                C.TRACK_TYPE_AUDIO -> requireWebm(format.sampleMimeType == MimeTypes.AUDIO_OPUS &&
                    format.sampleRate == 48_000 && format.channelCount in 1..2 && format.initializationData.size == 3,
                    WebmProbeFailure.TRACK_PROFILE)
                else -> throw WebmProbeException(WebmProbeFailure.TRACK_PROFILE)
            }
            requireWebm(format.initializationData.sumOf { it.size.toLong() } <= binding.limits.maxAllConfigurationBytes,
                WebmProbeFailure.CONFIG_LIMIT)
            this.format = format
            delegate.format(format)
        }
        private fun before(length: Int, part: Int) {
            binding.check()
            requireWebm(part != TrackOutput.SAMPLE_DATA_PART_ENCRYPTION, WebmProbeFailure.PROTECTED_MEDIA)
            requireWebm(part == TrackOutput.SAMPLE_DATA_PART_MAIN || part == TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
                WebmProbeFailure.SAMPLE_BOUNDARY)
            requireWebm(length >= 0 && length <= binding.limits.maxSampleBytes - pending, WebmProbeFailure.SAMPLE_LIMIT)
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            before(length, sampleDataPart)
            val count = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
            binding.check(); requireWebm(count > 0 || length == 0, WebmProbeFailure.ZERO_PROGRESS)
            requireWebm(count <= length, WebmProbeFailure.SAMPLE_BOUNDARY)
            pending += count
            if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL) supplementalBytes += count
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            before(length, sampleDataPart)
            delegate.sampleData(data, length, sampleDataPart)
            pending += length
            if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL) supplementalBytes += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            binding.event()
            requireWebm(cryptoData == null && flags and C.BUFFER_FLAG_ENCRYPTED == 0, WebmProbeFailure.PROTECTED_MEDIA)
            requireWebm(size > 0 && offset >= 0 && size.toLong() + offset == pending, WebmProbeFailure.SAMPLE_BOUNDARY)
            val minimum = if (type == C.TRACK_TYPE_AUDIO) -120_000L else 0L
            requireWebm(timeUs in minimum..MAX_DURATION_US, WebmProbeFailure.FIELD_VALUE)
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            pending = offset.toLong(); samples++
            if (firstPts == null) firstPts = timeUs
            lastPts = timeUs
        }
        fun finish() = requireWebm(format != null && pending == 0L && samples > 0, WebmProbeFailure.INCOMPLETE)
        fun facts(declaredDefaultDurationNs: Long?): WebmTrackTransport {
            finish(); val f = requireNotNull(format)
            return WebmTrackTransport(id, type, requireNotNull(f.sampleMimeType), f.width, f.height,
                f.sampleRate, f.channelCount, f.initializationData, samples, firstPts!!, lastPts!!, supplementalBytes,
                declaredDefaultDurationNs)
        }
    }
    private companion object {
        const val EBML = 0x1A45DFA3; const val SEGMENT = 0x18538067; const val INFO = 0x1549A966
        const val TRACKS = 0x1654AE6B; const val TRACK_ENTRY = 0xAE; const val CLUSTER = 0x1F43B675
        const val VIDEO = 0xE0; const val AUDIO = 0xE1; const val BLOCK_GROUP = 0xA0
        const val DOC_TYPE = 0x4282; const val CODEC_ID = 0x86; const val CODEC_PRIVATE = 0x63A2
        const val TRACK_NUMBER = 0xD7; const val TRACK_TYPE = 0x83; const val SIMPLE_BLOCK = 0xA3; const val BLOCK = 0xA1
        const val DEFAULT_DURATION = 0x23E383
        const val FLAG_INTERLACED = 0x9A; const val COLOUR = 0x55B0; const val AUDIO_BIT_DEPTH = 0x6264
        const val COLOUR_RANGE = 0x55B9; const val COLOUR_TRANSFER = 0x55BA; const val COLOUR_PRIMARIES = 0x55BB
        val COLOUR_FIELDS = setOf(COLOUR_RANGE, COLOUR_TRANSFER, COLOUR_PRIMARIES)
        const val MAX_DURATION_US = 30L * 60 * 1_000_000
        const val MAX_BOUNDARY_NS = 120_000_000L
        val PROTECTED_IDS = setOf(0x6D80, 0x6240, 0x5035, 0x5034, 0x47E2, 0x47E1, 0x4254, 0x4255)
        val UNSUPPORTED_IDS = setOf(0x1043A770, 0x1941A469, 0x3CB923, 0x3EB923, 0x4444, 0x6FAB,
            0x53AB, 0x54AA, 0x54BB, 0x54CC, 0x54DD, 0x7670, 0x53B8, 0x53C0, 0x55D0, 0x41E4)
        val EXTRA_HEADER_INTS = setOf(0x4286, 0x4287, 0x42F2, 0x42F3)
        val SKIPPED_METADATA = setOf(0xEC, 0xBF, 0x1C53BB6B, 0x114D9B74, 0x1254C367,
            0x7BA9, 0x4D80, 0x5741, 0x73A4, 0x536E, 0x22B59C, 0x9C)
        val ALLOWED_IDS = setOf(EBML, SEGMENT, INFO, TRACKS, TRACK_ENTRY, CLUSTER, VIDEO, AUDIO, BLOCK_GROUP,
            DOC_TYPE, CODEC_ID, CODEC_PRIVATE, TRACK_NUMBER, TRACK_TYPE, SIMPLE_BLOCK, BLOCK,
            FLAG_INTERLACED, COLOUR, COLOUR_RANGE, COLOUR_TRANSFER, COLOUR_PRIMARIES, AUDIO_BIT_DEPTH,
            0x42F7, 0x4285, 0x73C5, 0xB0, 0xBA, 0x54B0, 0x54BA, 0x54B2, 0x9F, 0x88, 0x55AA,
            DEFAULT_DURATION, 0x56AA, 0x56BB, 0x75A2, 0x2AD7B1, 0xE7, 0x9B, 0xFB, 0x4489, 0xB5)
    }
}
