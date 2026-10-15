package com.easysavevideo.app.save.normalization

import com.easysavevideo.save.DirectMovAacConfiguration
import com.easysavevideo.save.DirectMovAvcConfiguration
import com.easysavevideo.save.DirectMovCanonical
import com.easysavevideo.save.DirectMovClock
import com.easysavevideo.save.DirectMovNalFraming
import com.easysavevideo.save.DirectMovSampleAccumulator
import com.easysavevideo.save.DirectNormalizationLimits
import com.easysavevideo.save.DirectRemuxConservation
import java.io.IOException
import java.util.Collections

/**
 * A caller-owned, fixed-extent, already completed and exclusively owned artifact. Reads must fill
 * the requested range or throw, never manufacture EOF bytes. This port does not acquire ownership,
 * open paths/URIs or close the caller's descriptor. The owner must bind its hash/identity and rehash
 * after extraction; a stable length alone cannot detect a same-size concurrent mutation.
 */
internal interface MovProbeInput {
    val length: Long
    fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int)
}

internal data class MovSampleReference(
    val index: Int,
    val offset: Long,
    val size: Int,
    val presentationTimeUs: Long,
    val sync: Boolean,
)

/** Exact bounded audio transport. Elementary AAC syntax remains inside the trusted decoder. */
internal class MovAudioProfile internal constructor(
    val clock: DirectMovClock,
    val configuration: DirectMovAacConfiguration,
    samples: List<MovSampleReference>,
) {
    val samples: List<MovSampleReference> = Collections.unmodifiableList(ArrayList(samples))
}

/** Syntax and transport evidence only. Neither this type nor a successful scan is decode proof. */
internal class MovProfile internal constructor(
    val inputLength: Long,
    val width: Int,
    val height: Int,
    val clock: DirectMovClock,
    val configuration: DirectMovAvcConfiguration,
    samples: List<MovSampleReference>,
    val conservation: DirectRemuxConservation,
    val audio: MovAudioProfile? = null,
) {
    val durationUs: Long = maxOf(clock.roundedDurationUs, audio?.clock?.roundedDurationUs ?: 0L)
    val frameRate: Int = requireNotNull(clock.frameRate)
    val samples: List<MovSampleReference> = Collections.unmodifiableList(ArrayList(samples))
}

internal enum class MovProfileFailure { STRUCTURE, LIMIT, PROTECTED_MEDIA, AUDIO_NOT_ADMITTED, AVC_PROFILE }
internal class MovProfileException(val failure: MovProfileFailure) : IOException("MOV_PROFILE_${failure.name}")
internal fun requireMov(ok: Boolean, failure: MovProfileFailure = MovProfileFailure.STRUCTURE) {
    if (!ok) throw MovProfileException(failure)
}

/**
 * Original strict raw-table scanner, separate from Mp4ContainerProbe and Android decoding.
 *
 * Structural allowlist: ftyp/moov/mdat plus explicitly inert free/skip/wide top-level padding;
 * moov(mvhd,trak); trak(tkhd,mdia); mdia(mdhd,hdlr,minf); minf(vmhd|smhd,dinf,stbl);
 * dinf(dref(url-self-contained)); stbl(stsd,stts,stsz,stsc,stco|co64,optional stss);
 * avc1(avcC,optional pasp/colr), mp4a(esds with one exact two-byte ASC). Unknown boxes at every
 * structural level fail closed. Exactly one video and zero/one audio; no metadata/other tracks,
 * edits, composition offsets, fragments, protection or description switches.
 * Header version 0 and avc1 version 0 only. A separate, named MP4 entry point uses the same sample
 * interpretation but NEVER admits QuickTime brands, and cannot broaden ordinary MP4 admission.
 */
internal object MovProfileProbe {
    const val MAX_MOOV_BYTES = 8 * 1024 * 1024
    const val MAX_BOXES = 16_384
    const val MAX_DEPTH = 12
    private const val MAX_SAMPLES = 108_000
    private val mp4Brands = setOf("isom", "iso2", "mp41", "mp42", "avc1")
    private val protectionBoxes = setOf("pssh", "sinf", "schi", "tenc", "senc", "saiz", "saio", "encv", "enca")

    fun probeQuickTime(input: MovProbeInput, checkpoint: () -> Unit = {}): MovProfile =
        Scanner(input, quickTime = true, checkpoint).scan()

    /** Expected canonical-output scanner only; never an alternate input-admission fallback. */
    fun scanCanonicalMp4(input: MovProbeInput, checkpoint: () -> Unit = {}): MovProfile =
        Scanner(input, quickTime = false, checkpoint).scan()

    fun readCanonicalSample(input: MovProbeInput, profile: MovProfile, index: Int,
        checkpoint: () -> Unit = {}): ByteArray {
        checkpoint()
        requireMov(input.length == profile.inputLength && index in profile.samples.indices)
        val sample = profile.samples[index]
        val bytes = readBounded(input, profile.inputLength, sample.offset, sample.size,
            DirectNormalizationLimits.MAX_SAMPLE_BYTES, checkpoint)
        return DirectMovCanonical.avcSample(bytes, DirectMovNalFraming.AVCC4, checkpoint)
    }

    /** Raw AAC bytes are returned unchanged. No AU syntax, decoding, trim or repair is implied. */
    fun readAudioSample(input: MovProbeInput, profile: MovProfile, index: Int,
        checkpoint: () -> Unit = {}): ByteArray {
        checkpoint()
        val audio = profile.audio
        requireMov(input.length == profile.inputLength && audio != null && index in audio.samples.indices)
        val sample = requireNotNull(audio).samples[index]
        return readBounded(input, profile.inputLength, sample.offset, sample.size,
            DirectNormalizationLimits.MAX_SAMPLE_BYTES, checkpoint)
    }

    private data class Extent(val start: Long, val end: Long)
    private data class Box(val type: String, val start: Int, val end: Int) {
        val size get() = end - start
    }
    private data class Header(val duration: Long, val trackId: Long = 0, val width: Int = 0, val height: Int = 0)
    private data class ChunkRun(val first: Int, val count: Int)

    private class Scanner(val input: MovProbeInput, val quickTime: Boolean, val checkpoint: () -> Unit) {
        private val extent = input.length
        private var boxCount = 0
        private lateinit var moov: ByteArray
        private val media = ArrayList<Extent>()

        fun scan(): MovProfile {
            checkpoint()
            requireMov(extent in 32..DirectNormalizationLimits.MAX_BYTES, MovProfileFailure.LIMIT)
            var at = 0L
            var ftyp = false
            var foundMoov = false
            while (at < extent) {
                checkpoint(); countBox(0)
                requireMov(extent - at >= 8)
                val head = readBounded(input, extent, at, 8, 16, checkpoint)
                val shortSize = u32(head, 0)
                val type = tag(head, 4)
                var header = 8L
                val size = when (shortSize) {
                    0L -> throw MovProfileException(MovProfileFailure.STRUCTURE) // Never implicit-to-EOF sizes.
                    1L -> {
                        header = 16
                        requireMov(extent - at >= header)
                        positive64(readBounded(input, extent, at + 8, 8, 8, checkpoint), 0)
                    }
                    else -> shortSize
                }
                requireMov(size >= header && size <= extent - at)
                val payload = at + header
                val payloadSize = size - header
                when (type) {
                    "ftyp" -> {
                        requireMov(!ftyp && at <= 4096 && payloadSize in 8..4096 && payloadSize % 4 == 0L)
                        checkBrands(readBounded(input, extent, payload, payloadSize.toInt(), 4096, checkpoint))
                        ftyp = true
                    }
                    "moov" -> {
                        requireMov(!foundMoov && payloadSize > 0)
                        requireMov(size <= MAX_MOOV_BYTES, MovProfileFailure.LIMIT)
                        moov = readBounded(input, extent, payload, payloadSize.toInt(), MAX_MOOV_BYTES, checkpoint)
                        foundMoov = true
                    }
                    "mdat" -> {
                        requireMov(payloadSize > 0)
                        media.add(Extent(payload, at + size))
                    }
                    "free", "skip" -> Unit // Explicit inert padding, not parsed as metadata.
                    "wide" -> requireMov(payloadSize == 0L)
                    else -> rejectBox(type)
                }
                at += size
            }
            requireMov(ftyp && foundMoov && media.isNotEmpty() && at == extent)
            val movie = children(0, moov.size, 1, setOf("mvhd", "trak"))
            val tracks = movie.filter { it.type == "trak" }
            requireMov(tracks.size in 1..2)
            val movieHeader = movieHeader(only(movie, "mvhd"))
            val facts = tracks.map { track -> trackFacts(track) }
            requireMov(facts.map { it.header.trackId }.toSet().size == facts.size &&
                facts.all { movieHeader.trackId > it.header.trackId })
            val videos = facts.filter { it.handler == "vide" }
            val audios = facts.filter { it.handler == "soun" }
            requireMov(videos.size == 1 && audios.size <= 1)
            val video = videos.single()
            val trackHeader = video.header
            val tables = video.tables
            val config = sampleDescription(only(tables, "stsd"), trackHeader.width, trackHeader.height)
            val sizes = sampleSizes(only(tables, "stsz"))
            val clock = sampleClock(only(tables, "stts"), sizes.size, video.media, audio = false)
            requireMov(trackHeader.duration == clock.headerDurationTicks)
            val chunks = chunkOffsets(tables)
            val runs = sampleChunks(only(tables, "stsc"), chunks.size, sizes.size)
            val sync = syncSamples(optional(tables, "stss"), sizes.size)
            requireMov(sync[0])
            val samples = sampleReferences(chunks, runs, sizes, sync, clock)
            val audio = audios.singleOrNull()?.let { track ->
                val audioConfig = audioDescription(only(track.tables, "stsd"))
                requireMov(track.media.timescale == audioConfig.sampleRate)
                val audioSizes = sampleSizes(only(track.tables, "stsz"))
                val audioClock = sampleClock(only(track.tables, "stts"), audioSizes.size, track.media, audio = true)
                requireMov(track.header.duration == audioClock.headerDurationTicks)
                val audioChunks = chunkOffsets(track.tables)
                val audioRuns = sampleChunks(only(track.tables, "stsc"), audioChunks.size, audioSizes.size)
                val audioSync = syncSamples(optional(track.tables, "stss"), audioSizes.size)
                requireMov(audioSync.all { it })
                MovAudioProfile(audioClock, audioConfig,
                    sampleReferences(audioChunks, audioRuns, audioSizes, audioSync, audioClock))
            }
            requireMov(movieHeader.duration == DirectMovClock.movieHeaderDurationTicks(clock, audio?.clock))
            // Prove global disjointness, including aliases between otherwise valid track tables.
            requireDisjoint(samples + (audio?.samples ?: emptyList()))
            val avc = MovAvcProfile(config, trackHeader.width, trackHeader.height,
                requireNotNull(clock.frameRate), checkpoint)
            val accumulation = DirectMovSampleAccumulator(clock, config.sha256, checkpoint)
            for (sample in samples) {
                checkpoint()
                val raw = readBounded(input, extent, sample.offset, sample.size,
                    DirectNormalizationLimits.MAX_SAMPLE_BYTES, checkpoint)
                val nals = DirectMovCanonical.avcNals(raw, DirectMovNalFraming.AVCC4, checkpoint)
                avc.sample(nals, sample.index, sample.sync)
                val canonical = DirectMovCanonical.avcSample(raw, DirectMovNalFraming.AVCC4, checkpoint)
                accumulation.append(canonical, sample.presentationTimeUs, if (sample.sync) 1 else 0)
            }
            val result = accumulation.finish()
            requireMov(input.length == extent)
            val audioResult = audio?.let { track ->
                val accumulator = DirectMovSampleAccumulator(track.clock, track.configuration.sha256, checkpoint)
                for (sample in track.samples) {
                    checkpoint()
                    val raw = readBounded(input, extent, sample.offset, sample.size,
                        DirectNormalizationLimits.MAX_SAMPLE_BYTES, checkpoint)
                    accumulator.append(raw, sample.presentationTimeUs, 1)
                }
                accumulator.finish().track
            }
            requireMov(input.length == extent)
            val proof = DirectRemuxConservation(trackHeader.width, trackHeader.height,
                requireNotNull(clock.frameRate), result.track, audioResult,
                audio?.configuration?.sampleRate, audio?.configuration?.channelCount,
                requireNotNull(result.precedingVideoSync))
            return MovProfile(extent, trackHeader.width, trackHeader.height, clock, config, samples, proof, audio)
        }

        private data class MediaHeader(val timescale: Int, val duration: Long)
        private data class TrackFacts(val handler: String, val header: Header,
            val media: MediaHeader, val tables: List<Box>)

        private fun trackFacts(box: Box): TrackFacts {
            val track = children(box.start, box.end, 2, setOf("tkhd", "mdia"))
            val mdia = only(track, "mdia")
            val mdiaChildren = children(mdia.start, mdia.end, 3, setOf("mdhd", "hdlr", "minf"))
            val handler = handler(only(mdiaChildren, "hdlr"))
            requireMov(handler == "vide" || handler == "soun")
            val audio = handler == "soun"
            val header = trackHeader(only(track, "tkhd"), audio)
            val media = mediaHeader(only(mdiaChildren, "mdhd"))
            val minf = only(mdiaChildren, "minf")
            val mediaType = if (audio) "smhd" else "vmhd"
            val minfChildren = children(minf.start, minf.end, 4, setOf(mediaType, "dinf", "stbl"))
            if (audio) audioHeader(only(minfChildren, mediaType)) else videoHeader(only(minfChildren, mediaType))
            dataReferences(only(minfChildren, "dinf"))
            val stbl = only(minfChildren, "stbl")
            return TrackFacts(handler, header, media, children(stbl.start, stbl.end, 5,
                setOf("stsd", "stts", "stsz", "stsc", "stco", "co64", "stss")))
        }

        private fun checkBrands(bytes: ByteArray) {
            val major = tag(bytes, 0)
            if (quickTime) requireMov(major == "qt  ") else requireMov(major in mp4Brands)
            var at = 8
            while (at < bytes.size) {
                val brand = tag(bytes, at)
                if (quickTime) requireMov(brand == "qt  ") else requireMov(brand in mp4Brands)
                at += 4
            }
        }

        private fun countBox(depth: Int) {
            requireMov(depth <= MAX_DEPTH && ++boxCount <= MAX_BOXES, MovProfileFailure.LIMIT)
        }
        private fun children(start: Int, end: Int, depth: Int, allowed: Set<String>): List<Box> {
            requireMov(start in 0..end && end <= moov.size)
            val result = ArrayList<Box>()
            var at = start
            while (at < end) {
                checkpoint(); countBox(depth)
                requireMov(end - at >= 8)
                val small = u32(moov, at)
                val type = tag(moov, at + 4)
                var header = 8
                val size = if (small == 1L) {
                    requireMov(end - at >= 16); header = 16; positive64(moov, at + 8)
                } else small
                requireMov(size >= header && size <= end.toLong() - at)
                requireMov(type in allowed, if (type in protectionBoxes) MovProfileFailure.PROTECTED_MEDIA else MovProfileFailure.STRUCTURE)
                result.add(Box(type, at + header, at + size.toInt()))
                at += size.toInt()
            }
            requireMov(at == end)
            return result
        }
        private fun only(boxes: List<Box>, type: String): Box = optional(boxes, type)
            ?: throw MovProfileException(MovProfileFailure.STRUCTURE)
        private fun optional(boxes: List<Box>, type: String): Box? {
            val matches = boxes.filter { it.type == type }
            requireMov(matches.size <= 1)
            return matches.firstOrNull()
        }
        private fun full(box: Box, flags: Set<Long> = setOf(0L)) {
            requireMov(box.size >= 4 && u32(moov, box.start) in flags)
        }
        private fun zero(start: Int, count: Int) {
            requireMov(start >= 0 && count >= 0 && start <= moov.size - count)
            repeat(count) { requireMov(moov[start + it] == 0.toByte()) }
        }
        private fun matrix(start: Int) {
            val identity = longArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)
            identity.forEachIndexed { index, expected -> requireMov(u32(moov, start + 4 * index) == expected) }
        }
        private fun movieHeader(box: Box): Header {
            full(box); requireMov(box.size == 100)
            val b = box.start
            requireMov(u32(moov, b + 12) == 10_000L && u32(moov, b + 20) == 0x10000L && u16(moov, b + 24) == 256)
            zero(b + 26, 10); matrix(b + 36); zero(b + 72, 24)
            val nextId = u32(moov, b + 96)
            requireMov(nextId > 0)
            return Header(u32(moov, b + 16), trackId = nextId)
        }
        private fun trackHeader(box: Box, audio: Boolean): Header {
            full(box, setOf(3L, 7L)); requireMov(box.size == 84)
            val b = box.start
            val id = u32(moov, b + 12)
            requireMov(id in 1..Int.MAX_VALUE.toLong()); zero(b + 16, 4); zero(b + 24, 12)
            requireMov(u16(moov, b + 36) == if (audio) 256 else 0); zero(b + 38, 2); matrix(b + 40)
            val width = u32(moov, b + 76)
            val height = u32(moov, b + 80)
            requireMov(width and 0xffffL == 0L && height and 0xffffL == 0L)
            val w = (width ushr 16).toInt(); val h = (height ushr 16).toInt()
            requireMov(if (audio) w == 0 && h == 0 else
                w in 2..1920 && h in 2..1920 && w % 2 == 0 && h % 2 == 0 && w.toLong() * h <= 1920L * 1080)
            return Header(u32(moov, b + 20), id, w, h)
        }
        private fun mediaHeader(box: Box): MediaHeader {
            full(box); requireMov(box.size == 24)
            val scale = u32(moov, box.start + 12)
            requireMov(scale in 1..Int.MAX_VALUE.toLong() && u16(moov, box.start + 22) == 0)
            return MediaHeader(scale.toInt(), u32(moov, box.start + 16))
        }
        private fun handler(box: Box): String {
            full(box); requireMov(box.size in 24..280)
            // QuickTime's mhlr component type and ISO's pre_defined zero are both explicit.
            requireMov(u32(moov, box.start + 4) == 0L || quickTime && tag(moov, box.start + 4) == "mhlr")
            zero(box.start + 12, 12)
            return tag(moov, box.start + 8)
        }
        private fun videoHeader(box: Box) {
            full(box, setOf(0L, 1L)); requireMov(box.size == 12); zero(box.start + 4, 8)
        }
        private fun audioHeader(box: Box) {
            full(box); requireMov(box.size == 8); zero(box.start + 4, 4)
        }
        private fun dataReferences(box: Box) {
            val dref = only(children(box.start, box.end, 5, setOf("dref")), "dref")
            full(dref); requireMov(dref.size >= 8 && u32(moov, dref.start + 4) == 1L)
            val url = only(children(dref.start + 8, dref.end, 6, setOf("url ")), "url ")
            requireMov(url.size == 4); full(url, setOf(1L))
        }
        private fun sampleDescription(box: Box, width: Int, height: Int): DirectMovAvcConfiguration {
            full(box); requireMov(box.size >= 8 && u32(moov, box.start + 4) == 1L)
            val entry = only(children(box.start + 8, box.end, 6, setOf("avc1")), "avc1")
            requireMov(entry.size >= 78)
            val b = entry.start
            zero(b, 6); requireMov(u16(moov, b + 6) == 1); zero(b + 8, 16)
            requireMov(u16(moov, b + 24) == width && u16(moov, b + 26) == height)
            requireMov(u32(moov, b + 28) > 0 && u32(moov, b + 32) > 0)
            zero(b + 36, 4); requireMov(u16(moov, b + 40) == 1)
            requireMov((moov[b + 42].toInt() and 255) <= 31) // Bounded, ignored compressor name.
            requireMov(u16(moov, b + 74) == 24 && u16(moov, b + 76) == 65535)
            val children = children(b + 78, entry.end, 7, setOf("avcC", "pasp", "colr"))
            optional(children, "pasp")?.let {
                requireMov(it.size == 8 && u32(moov, it.start) > 0 && u32(moov, it.start) == u32(moov, it.start + 4))
            }
            optional(children, "colr")?.let {
                requireMov(it.size in 10..11)
                val kind = tag(moov, it.start)
                requireMov(kind == "nclc" && it.size == 10 || kind == "nclx" && it.size == 11)
                requireMov(u16(moov, it.start + 4) == 1 && u16(moov, it.start + 6) == 1 && u16(moov, it.start + 8) == 1)
                if (it.size == 11) requireMov(moov[it.start + 10] == 0.toByte())
            }
            val avcc = only(children, "avcC")
            requireMov(avcc.size in 1..DirectNormalizationLimits.MAX_CONFIGURATION_BYTES, MovProfileFailure.LIMIT)
            return DirectMovCanonical.avcConfigurationFromAvcc(moov.copyOfRange(avcc.start, avcc.end), checkpoint)
        }
        private fun audioDescription(box: Box): DirectMovAacConfiguration {
            full(box); requireMov(box.size >= 8 && u32(moov, box.start + 4) == 1L)
            val entry = only(children(box.start + 8, box.end, 6, setOf("mp4a")), "mp4a")
            requireMov(entry.size >= 28)
            val b = entry.start
            zero(b, 6); requireMov(u16(moov, b + 6) == 1)
            zero(b + 8, 8) // Version zero, revision/vendor/reserved; no QT v1/v2 or wave fallback.
            val channels = u16(moov, b + 16)
            requireMov(channels in 1..2 && u16(moov, b + 18) == 16)
            zero(b + 20, 4)
            val rate = u32(moov, b + 24)
            requireMov(rate and 0xffffL == 0L && (rate ushr 16).toInt() in DirectMovClock.AUDIO_SAMPLE_RATES)
            val esds = only(children(b + 28, entry.end, 7, setOf("esds")), "esds")
            full(esds); requireMov(esds.size in 4..128, MovProfileFailure.LIMIT)
            val es = descriptor(esds.start + 4, esds.end, 3)
            requireMov(es.end == esds.end && es.size >= 3)
            requireMov((moov[es.start + 2].toInt() and 255) == 0) // No dependency, URL, OCR, priority.
            val decoder = descriptor(es.start + 3, es.end, 4)
            requireMov(decoder.size >= 13 && (moov[decoder.start].toInt() and 255) == 0x40 &&
                (moov[decoder.start + 1].toInt() and 255) == 0x15) // MPEG-4 audio, downstream, reserved=1.
            // Buffer and bitrate fields are bounded numeric hints, never configuration authority.
            val specific = descriptor(decoder.start + 13, decoder.end, 5)
            requireMov(specific.size == 2 && specific.end == decoder.end)
            val sl = descriptor(decoder.end, es.end, 6)
            requireMov(sl.size == 1 && sl.end == es.end && moov[sl.start] == 2.toByte())
            val config = DirectMovCanonical.aacConfiguration(moov.copyOfRange(specific.start, specific.end), checkpoint)
            requireMov(config.sampleRate == (rate ushr 16).toInt() && config.channelCount == channels)
            return config
        }

        /** One bounded descriptor, with its own parent-relative extent; no tolerated trailing tags. */
        private fun descriptor(start: Int, parentEnd: Int, expectedTag: Int): Box {
            requireMov(start >= 0 && start < parentEnd && parentEnd <= moov.size)
            requireMov((moov[start].toInt() and 255) == expectedTag)
            var at = start + 1
            var length = 0
            var terminated = false
            repeat(4) {
                if (!terminated) {
                    requireMov(at < parentEnd)
                    val value = moov[at++].toInt() and 255
                    length = (length shl 7) or (value and 127)
                    terminated = value and 128 == 0
                }
            }
            requireMov(terminated && length <= parentEnd - at)
            return Box("descriptor", at, at + length)
        }

        private fun table(box: Box, width: Int, minimum: Int = 1): Int {
            full(box); requireMov(box.size >= 8)
            val count = u32(moov, box.start + 4)
            requireMov(count in minimum.toLong()..MAX_SAMPLES.toLong(), MovProfileFailure.LIMIT)
            requireMov(count <= (box.size - 8L) / width && 8L + count * width == box.size.toLong())
            return count.toInt()
        }
        private fun sampleSizes(box: Box): IntArray {
            full(box); requireMov(box.size >= 12)
            val constant = u32(moov, box.start + 4)
            val count = u32(moov, box.start + 8)
            requireMov(count in 2..MAX_SAMPLES.toLong(), MovProfileFailure.LIMIT)
            requireMov(constant <= DirectNormalizationLimits.MAX_SAMPLE_BYTES, MovProfileFailure.LIMIT)
            requireMov(box.size.toLong() == 12L + if (constant == 0L) count * 4 else 0)
            return IntArray(count.toInt()) { index ->
                if (index and 1023 == 0) checkpoint()
                val size = if (constant == 0L) u32(moov, box.start + 12 + index * 4) else constant
                requireMov(size in 1..DirectNormalizationLimits.MAX_SAMPLE_BYTES.toLong(), MovProfileFailure.LIMIT)
                size.toInt()
            }
        }
        private fun sampleClock(box: Box, sampleCount: Int, media: MediaHeader, audio: Boolean): DirectMovClock {
            val count = table(box, 8)
            var total = 0L
            var delta = -1L
            repeat(count) { index ->
                if (index and 1023 == 0) checkpoint()
                val b = box.start + 8 + index * 8
                val run = u32(moov, b)
                val d = u32(moov, b + 4)
                requireMov(run > 0 && run <= sampleCount - total && d > 0 && (delta == -1L || delta == d))
                total += run; delta = d
            }
            requireMov(total == sampleCount.toLong())
            return if (audio) DirectMovClock.audioFromTable(media.timescale, delta, total, media.duration)
                else DirectMovClock.videoFromTable(media.timescale, delta, total, media.duration)
        }
        private fun chunkOffsets(tables: List<Box>): LongArray {
            val stco = optional(tables, "stco"); val co64 = optional(tables, "co64")
            requireMov((stco == null) != (co64 == null))
            val box = stco ?: requireNotNull(co64)
            val stride = if (stco != null) 4 else 8
            val count = table(box, stride)
            return LongArray(count) { index ->
                if (index and 1023 == 0) checkpoint()
                val b = box.start + 8 + index * stride
                val offset = if (stride == 4) u32(moov, b) else positive64(moov, b)
                requireMov(offset < extent)
                offset
            }
        }
        private fun sampleChunks(box: Box, chunkCount: Int, sampleCount: Int): List<ChunkRun> {
            val count = table(box, 12)
            requireMov(count <= chunkCount)
            val runs = ArrayList<ChunkRun>(count)
            repeat(count) { index ->
                if (index and 1023 == 0) checkpoint()
                val b = box.start + 8 + index * 12
                val first = u32(moov, b)
                val samples = u32(moov, b + 4)
                requireMov(first in 1..chunkCount.toLong() && samples in 1..sampleCount.toLong() && u32(moov, b + 8) == 1L)
                requireMov(if (index == 0) first == 1L else first > runs.last().first)
                runs.add(ChunkRun(first.toInt(), samples.toInt()))
            }
            return runs
        }
        private fun syncSamples(box: Box?, sampleCount: Int): BooleanArray {
            if (box == null) return BooleanArray(sampleCount) { true }
            val count = table(box, 4)
            requireMov(count <= sampleCount)
            var previous = 0
            val result = BooleanArray(sampleCount)
            repeat(count) { index ->
                if (index and 1023 == 0) checkpoint()
                val sample = u32(moov, box.start + 8 + index * 4)
                requireMov(sample > previous && sample <= sampleCount)
                previous = sample.toInt(); result[previous - 1] = true
            }
            return result
        }
        private fun sampleReferences(chunks: LongArray, runs: List<ChunkRun>, sizes: IntArray,
            sync: BooleanArray, clock: DirectMovClock): List<MovSampleReference> {
            val result = ArrayList<MovSampleReference>(sizes.size)
            var runIndex = 0
            for (chunkIndex in chunks.indices) {
                checkpoint()
                while (runIndex + 1 < runs.size && chunkIndex + 1 >= runs[runIndex + 1].first) runIndex++
                val count = runs[runIndex].count
                requireMov(count <= sizes.size - result.size)
                var offset = chunks[chunkIndex]
                repeat(count) {
                    val index = result.size
                    if (index and 1023 == 0) checkpoint()
                    val size = sizes[index]
                    requireMov(offset >= 0 && size.toLong() <= extent - offset)
                    val end = offset + size
                    requireMov(inMedia(offset, end))
                    result.add(MovSampleReference(index, offset, size, clock.presentationTimeUs(index.toLong()), sync[index]))
                    offset = end
                }
            }
            requireMov(result.size == sizes.size)
            requireDisjoint(result)
            return result
        }
        private fun requireDisjoint(samples: List<MovSampleReference>) {
            var previousEnd = -1L
            var comparisons = 0
            val byOffset = samples.sortedWith { left, right ->
                if (++comparisons and 1023 == 0) checkpoint()
                left.offset.compareTo(right.offset)
            }
            for (sample in byOffset) {
                checkpoint()
                requireMov(sample.offset >= previousEnd) // No aliases, overlaps, backwards overflow or duplicate chunk extents.
                previousEnd = sample.offset + sample.size
            }
        }
        private fun inMedia(start: Long, end: Long): Boolean {
            // Top-level extents are already ordered and disjoint. Binary search keeps adversarial
            // many-mdat inputs O(samples * log(boxes)), not O(samples * boxes).
            var low = 0
            var high = media.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val candidate = media[middle]
                when {
                    start < candidate.start -> high = middle - 1
                    start >= candidate.end -> low = middle + 1
                    else -> return end <= candidate.end
                }
            }
            return false
        }
        private fun rejectBox(type: String): Nothing = throw MovProfileException(
            if (type in protectionBoxes) MovProfileFailure.PROTECTED_MEDIA else MovProfileFailure.STRUCTURE)
    }

    private fun readBounded(input: MovProbeInput, extent: Long, offset: Long, count: Int,
        cap: Int, checkpoint: () -> Unit): ByteArray {
        checkpoint()
        requireMov(count in 0..cap && offset >= 0 && offset <= extent && count.toLong() <= extent - offset && input.length == extent)
        val result = ByteArray(count)
        var at = 0
        while (at < count) {
            checkpoint()
            val block = minOf(64 * 1024, count - at)
            input.readAt(offset + at, result, at, block)
            at += block
        }
        checkpoint(); requireMov(input.length == extent)
        return result
    }
    private fun u16(b: ByteArray, at: Int): Int {
        requireMov(at >= 0 && at <= b.size - 2)
        return ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
    }
    private fun u32(b: ByteArray, at: Int): Long {
        requireMov(at >= 0 && at <= b.size - 4)
        return (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
    }
    private fun positive64(b: ByteArray, at: Int): Long {
        val high = u32(b, at); val low = u32(b, at + 4)
        requireMov(high <= 0x7fff_ffffL)
        return (high shl 32) or low
    }
    private fun tag(b: ByteArray, at: Int): String {
        requireMov(at >= 0 && at <= b.size - 4)
        return CharArray(4) { (b[at + it].toInt() and 255).toChar() }.concatToString()
    }
}
