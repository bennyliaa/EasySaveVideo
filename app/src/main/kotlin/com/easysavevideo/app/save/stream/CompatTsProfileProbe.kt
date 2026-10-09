package com.easysavevideo.app.save.stream

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException

internal enum class CompatTsProfileFailure {
    EMPTY, PACKET, PROTECTED, DISCONTINUITY, CONTINUITY, PSI, PROGRAM, TRACKS,
    PES, TIMESTAMP, AVC, AAC, FORMAT_CHANGE, LIMIT, INCOMPLETE, RELEASE_UNCONFIRMED,
    UNSUPPORTED_AU_LAYOUT,
}

/** Contains only a bounded technical code, never a source URL, path, or parser exception message. */
internal class CompatTsProfileException(val reason: CompatTsProfileFailure) :
    IllegalArgumentException(reason.name)

/** Exact caller-approved read capability; production callers supply already-checked channels. */
internal data class CompatTsInput(val bytes: Long, val open: () -> InputStream) {
    override fun toString() = "CompatTsInput(bytes=$bytes)"
}

@ConsistentCopyVisibility
internal data class CompatTsSegmentEvidence internal constructor(
    val videoPesCount: Long,
    val audioPesCount: Long,
    val videoAccessUnitCount: Long,
    val audioFrameCount: Long,
    val firstVideoPts90Khz: Long,
    val lastVideoPts90Khz: Long,
    val firstAudioPts90Khz: Long?,
    val lastAudioPts90Khz: Long?,
)

@ConsistentCopyVisibility
internal data class CompatTsProfileEvidence internal constructor(
    val videoPid: Int,
    val audioPid: Int?,
    val videoPesCount: Long,
    val audioPesCount: Long,
    val videoAccessUnitCount: Long,
    val audioFrameCount: Long,
    val firstVideoPts90Khz: Long,
    val lastVideoPts90Khz: Long,
    val firstAudioPts90Khz: Long?,
    val lastAudioPts90Khz: Long?,
    val sps: List<Byte>,
    val pps: List<Byte>,
    val audioSampleRate: Int?,
    val audioChannelCount: Int?,
    val segments: List<CompatTsSegmentEvidence>,
    /** A real VCL AU follows the last completed AUD delimiter and needs reader EOF commit. */
    val terminalPendingVcl: Boolean,
)

/**
 * Original, deliberately narrow transport/bitstream structural preflight; NOT a decoder.
 *
 * Accepts single-program, clear 188-byte TS, progressive AVC Baseline, one AUD-delimited
 * I/P access unit per video PES, with ordered same-type slices belonging to one progressive picture,
 * and optionally complete declared-LC ADTS frames per audio PES. Every segment repeats
 * identical PAT/PMT; SPS/PPS are inherited across segments. The first asset AU and an AU
 * following an explicit source end marker need IDR; ordinary segment boundaries do not.
 * Counters and original 33-bit timestamps must continue across segments without wrap/reset.
 * Parameter sets and ADTS configuration must remain byte/configuration identical throughout.
 * Observed audio PES PTS cadence must match the preceding parsed ADTS frame count to one tick.
 * Final unbounded video PES is admitted only after the entire last input was read to its
 * capability's exact byte count and clean EOF. This is structural evidence, not a transfer
 * permit; production must separately establish complete immutable resource transfer.
 * Intermediate unbounded PES is admitted only as part of a successful full-asset scan:
 * the next segment must start a new video PES with PUSI and a new leading AUD. A continuation
 * without PUSI fails because segment finish clears the PES buffer; continued slices in a
 * new PES fail the leading-AUD gate. PES and AU boundaries may not cross segments in this
 * isolated candidate. More than one real AU per PES and no-AUD input are unsupported.
 * Optional final empty AUD must be followed by an end marker, so the reader has already
 * observed the delimiter end. It reports no pending VCL; ordinary no-tail input reports one
 * pending AU for the guarded once-only reader EOF callback. Intermediate empty AUDs create
 * ghost samples and remain forbidden. No source bytes or timestamps are invented.
 * A declared PES length must match exactly. Neither those checks nor end markers establish
 * entropy-code validity: independent extraction and output decode checks remain mandatory.
 * Implicit SBR inside AAC entropy payload is not excluded by the ADTS header checks.
 * No timestamps are synthesized, shifted, unwrapped, or repaired here.
 */
internal object CompatTsProfileProbe {
    private const val PACKET_SIZE = 188
    private const val MAX_PES_BYTES = 4 * 1024 * 1024
    private const val MAX_GAP = 2 * 90_000L
    private const val MAX_DURATION = 24 * 60 * 60 * 90_000L

    /** Convenience wrapper for synthetic file tests; production uses inspectSources. */
    fun inspect(segments: List<File>, checkpoint: () -> Unit = {}): CompatTsProfileEvidence =
        inspectSources(segments.map { file ->
            checkpoint()
            ensure(file.isFile, CompatTsProfileFailure.EMPTY)
            CompatTsInput(file.length()) { file.inputStream() }
        }, checkpoint)

    fun inspectSources(segments: List<CompatTsInput>, checkpoint: () -> Unit = {}): CompatTsProfileEvidence {
        ensure(segments.isNotEmpty(), CompatTsProfileFailure.EMPTY)
        return Scanner(checkpoint).scan(segments)
    }

    private class Scanner(private val checkpoint: () -> Unit) {
        private val counters = IntArray(8192) { -1 }
        private var pat: ByteArray? = null
        private var pmt: ByteArray? = null
        private var program = -1
        private var pmtPid = -1
        private var videoPid = -1
        private var audioPid: Int? = null
        private var segmentPat = false
        private var segmentPmt = false
        private val patReader = SectionReader { readPat(it) }
        private val pmtReader = SectionReader { readPmt(it) }
        private var video: PesReader? = null
        private var audio: PesReader? = null
        private var spsBytes: ByteArray? = null
        private var ppsBytes: ByteArray? = null
        private var sps: Sps? = null
        private var pps: Pps? = null
        private var audioConfig: AudioConfig? = null
        private val videoTime = Timeline()
        private val audioTime = Timeline()
        private var videoCount = 0L
        private var audioCount = 0L
        private var audioFrameCount = 0L
        private var segmentAudioFrameCount = 0L
        private var previousAudioPesFrames = 0L
        private var segmentVideoCount = 0L
        private var segmentAudioCount = 0L
        private var segmentVideoFirst: Long? = null
        private var segmentAudioFirst: Long? = null
        private var lastVideoHasEnd = false
        private var lastVideoHasTrailingAud = false
        private var segmentEnded = false
        private var nextVideoRequiresIdr = true
        private var videoStreamId = -1
        private var audioStreamId = -1

        fun scan(sources: List<CompatTsInput>): CompatTsProfileEvidence {
            val evidence = ArrayList<CompatTsSegmentEvidence>(sources.size)
            val packet = ByteArray(PACKET_SIZE)
            for ((segmentIndex, source) in sources.withIndex()) {
                checkpoint()
                ensure(source.bytes > 0L, CompatTsProfileFailure.EMPTY)
                ensure(source.bytes % PACKET_SIZE == 0L, CompatTsProfileFailure.PACKET)
                segmentPat = false
                segmentPmt = false
                segmentEnded = false
                lastVideoHasTrailingAud = false
                segmentVideoCount = 0
                segmentAudioCount = 0
                segmentAudioFrameCount = 0
                segmentVideoFirst = null
                segmentAudioFirst = null
                val input = source.open().buffered(32 * 1024)
                try {
                    var readBytes = 0L
                    while (readPacket(input, packet)) {
                        checkpoint()
                        ensure(readBytes <= source.bytes - PACKET_SIZE, CompatTsProfileFailure.INCOMPLETE)
                        readBytes += PACKET_SIZE
                        readPacket(packet)
                    }
                    ensure(readBytes == source.bytes, CompatTsProfileFailure.INCOMPLETE)
                } finally {
                    // A parser failure must still close the stream. Failure to establish release
                    // outranks malformed media so the owner can retain its workspace/lane fence.
                    try { input.close() }
                    catch (_: IOException) { fail(CompatTsProfileFailure.RELEASE_UNCONFIRMED) }
                }
                ensure(patReader.isComplete && pmtReader.isComplete, CompatTsProfileFailure.INCOMPLETE)
                video?.finish()
                audio?.finish()
                ensure(segmentPat && segmentPmt && segmentVideoCount > 0, CompatTsProfileFailure.INCOMPLETE)
                ensure(audioPid == null || segmentAudioCount > 0, CompatTsProfileFailure.INCOMPLETE)
                // No evidence escapes until the whole scan succeeds. For an intermediate
                // unbounded PES, the next segment's new PUSI plus leading AUD supplies its
                // ordinary boundary. Continuation is rejected by the cleared PES buffer.
                ensure(!lastVideoHasTrailingAud || segmentIndex == sources.lastIndex, CompatTsProfileFailure.INCOMPLETE)
                ensure(!lastVideoHasTrailingAud || lastVideoHasEnd, CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT)
                if (audioPid != null) {
                    ensure(kotlin.math.abs(segmentVideoFirst!! - segmentAudioFirst!!) <= MAX_GAP, CompatTsProfileFailure.TIMESTAMP)
                    ensure(kotlin.math.abs(videoTime.last!! - audioTime.last!!) <= MAX_GAP, CompatTsProfileFailure.TIMESTAMP)
                }
                evidence += CompatTsSegmentEvidence(
                    segmentVideoCount, segmentAudioCount, segmentVideoCount, segmentAudioFrameCount,
                    segmentVideoFirst!!, videoTime.last!!,
                    segmentAudioFirst, if (audioPid == null) null else audioTime.last,
                )
            }
            return CompatTsProfileEvidence(
                videoPid, audioPid, videoCount, audioCount, videoCount, audioFrameCount,
                videoTime.first!!, videoTime.last!!,
                audioTime.first, audioTime.last, spsBytes!!.toList(), ppsBytes!!.toList(),
                audioConfig?.sampleRate, audioConfig?.channels, evidence.toList(),
                terminalPendingVcl = !lastVideoHasTrailingAud,
            )
        }

        private fun readPacket(input: InputStream, packet: ByteArray): Boolean {
            var read = 0
            while (read < packet.size) {
                val n = input.read(packet, read, packet.size - read)
                if (n < 0) {
                    ensure(read == 0, CompatTsProfileFailure.INCOMPLETE)
                    return false
                }
                ensure(n > 0, CompatTsProfileFailure.INCOMPLETE)
                read += n
            }
            return true
        }

        private fun readPacket(b: ByteArray) {
            ensure(u(b[0]) == 0x47, CompatTsProfileFailure.PACKET)
            ensure(u(b[1]) and 0x80 == 0, CompatTsProfileFailure.PACKET)
            ensure(u(b[3]) and 0xc0 == 0, CompatTsProfileFailure.PROTECTED)
            val pid = ((u(b[1]) and 31) shl 8) or u(b[2])
            val start = u(b[1]) and 0x40 != 0
            val mode = (u(b[3]) ushr 4) and 3
            ensure(mode != 0, CompatTsProfileFailure.PACKET)
            val payload = mode and 1 != 0
            var at = 4
            if (mode and 2 != 0) {
                val length = u(b[at++])
                ensure(length <= if (payload) 182 else 183, CompatTsProfileFailure.PACKET)
                ensure(payload || length == 183, CompatTsProfileFailure.PACKET)
                val end = at + length
                if (length > 0) {
                    val flags = u(b[at++])
                    ensure(flags and 0x80 == 0, CompatTsProfileFailure.DISCONTINUITY)
                    // The narrow profile has no OPCR, splice, private adaptation data, or extension.
                    ensure(flags and 0x0f == 0, CompatTsProfileFailure.PACKET)
                    if (flags and 0x10 != 0) {
                        ensure(at + 6 <= end, CompatTsProfileFailure.PACKET)
                        ensure(u(b[at + 4]) and 0x7e == 0x7e, CompatTsProfileFailure.PACKET)
                        val extension = ((u(b[at + 4]) and 1) shl 8) or u(b[at + 5])
                        ensure(extension < 300, CompatTsProfileFailure.PACKET)
                        at += 6
                    }
                    while (at < end) ensure(u(b[at++]) == 0xff, CompatTsProfileFailure.PACKET)
                }
                at = end
            }
            ensure(!payload || at < PACKET_SIZE, CompatTsProfileFailure.PACKET)
            if (pid == 0x1fff) return
            val counter = u(b[3]) and 15
            val previous = counters[pid]
            if (previous >= 0) ensure(counter == ((previous + if (payload) 1 else 0) and 15), CompatTsProfileFailure.CONTINUITY)
            counters[pid] = counter
            ensure(pid == 0 || pid == pmtPid || pid == videoPid || pid == audioPid, CompatTsProfileFailure.TRACKS)
            if (!payload) return
            when (pid) {
                0 -> patReader.consume(b, at, start)
                pmtPid -> {
                    ensure(segmentPat, CompatTsProfileFailure.PROGRAM)
                    pmtReader.consume(b, at, start)
                }
                videoPid -> {
                    ensure(segmentPmt, CompatTsProfileFailure.PROGRAM)
                    video!!.consume(b, at, start)
                }
                audioPid -> {
                    ensure(segmentPmt, CompatTsProfileFailure.PROGRAM)
                    audio!!.consume(b, at, start)
                }
                else -> fail(CompatTsProfileFailure.TRACKS)
            }
        }

        private fun readPat(bytes: ByteArray) {
            validateSection(bytes, 0)
            ensure(bytes.size == 16, CompatTsProfileFailure.PROGRAM) // Exactly one program; no NIT entry.
            val number = word(bytes, 8)
            val pid = word(bytes, 10) and 0x1fff
            ensure(number != 0 && pid in 0x20..0x1ffe, CompatTsProfileFailure.PROGRAM)
            ensure(u(bytes[10]) and 0xe0 == 0xe0, CompatTsProfileFailure.PSI)
            stable(pat, bytes)
            pat = bytes
            program = number
            pmtPid = pid
            segmentPat = true
        }

        private fun readPmt(bytes: ByteArray) {
            validateSection(bytes, 2)
            ensure(word(bytes, 3) == program && bytes.size >= 21, CompatTsProfileFailure.PROGRAM)
            ensure(u(bytes[8]) and 0xe0 == 0xe0 && u(bytes[10]) and 0xf0 == 0xf0, CompatTsProfileFailure.PSI)
            val pcrPid = word(bytes, 8) and 0x1fff
            // Descriptors are excluded rather than assuming unknown descriptors are harmless.
            ensure(word(bytes, 10) and 0x0fff == 0, CompatTsProfileFailure.TRACKS)
            var v = -1
            var a: Int? = null
            var at = 12
            while (at < bytes.size - 4) {
                ensure(at + 5 <= bytes.size - 4, CompatTsProfileFailure.PSI)
                val type = u(bytes[at])
                ensure(u(bytes[at + 1]) and 0xe0 == 0xe0 && u(bytes[at + 3]) and 0xf0 == 0xf0, CompatTsProfileFailure.PSI)
                val pid = word(bytes, at + 1) and 0x1fff
                val infoLength = word(bytes, at + 3) and 0x0fff
                ensure(infoLength == 0, CompatTsProfileFailure.TRACKS)
                ensure(pid in 0x20..0x1ffe && pid != pmtPid && pid != v && pid != a, CompatTsProfileFailure.TRACKS)
                when (type) {
                    0x1b -> { ensure(v == -1, CompatTsProfileFailure.TRACKS); v = pid }
                    0x0f -> { ensure(a == null, CompatTsProfileFailure.TRACKS); a = pid }
                    else -> fail(CompatTsProfileFailure.TRACKS)
                }
                at += 5
            }
            ensure(at == bytes.size - 4 && v != -1 && pcrPid == v, CompatTsProfileFailure.TRACKS)
            stable(pmt, bytes)
            if (pmt == null) {
                videoPid = v
                audioPid = a
                video = PesReader { readPes(it, true) }
                if (a != null) audio = PesReader { readPes(it, false) }
            }
            pmt = bytes
            segmentPmt = true
        }

        private fun readPes(bytes: ByteArray, isVideo: Boolean) {
            checkpoint()
            ensure(bytes.size >= 14, CompatTsProfileFailure.PES)
            ensure(u(bytes[0]) == 0 && u(bytes[1]) == 0 && u(bytes[2]) == 1, CompatTsProfileFailure.PES)
            val streamId = u(bytes[3])
            ensure(streamId in if (isVideo) 0xe0..0xef else 0xc0..0xdf, CompatTsProfileFailure.PES)
            val priorId = if (isVideo) videoStreamId else audioStreamId
            ensure(priorId == -1 || priorId == streamId, CompatTsProfileFailure.FORMAT_CHANGE)
            if (isVideo) videoStreamId = streamId else audioStreamId = streamId
            val declared = word(bytes, 4)
            ensure((declared != 0 || isVideo) && (declared == 0 || declared + 6 == bytes.size), CompatTsProfileFailure.INCOMPLETE)
            ensure(u(bytes[6]) and 0xc0 == 0x80, CompatTsProfileFailure.PES)
            ensure(u(bytes[6]) and 0x30 == 0, CompatTsProfileFailure.PROTECTED)
            val timing = u(bytes[7]) ushr 6
            ensure(timing == 2 || timing == 3, CompatTsProfileFailure.TIMESTAMP)
            ensure(u(bytes[7]) and 0x3f == 0, CompatTsProfileFailure.PES)
            val headerSize = if (timing == 3) 10 else 5
            ensure(u(bytes[8]) == headerSize && bytes.size > 9 + headerSize, CompatTsProfileFailure.PES)
            val pts = timestamp(bytes, 9, timing)
            if (timing == 3) ensure(timestamp(bytes, 14, 1) == pts, CompatTsProfileFailure.TIMESTAMP)
            if (isVideo) {
                ensure(!segmentEnded && !lastVideoHasTrailingAud, CompatTsProfileFailure.AVC)
                val ended = readAvc(bytes, 9 + headerSize, nextVideoRequiresIdr)
                nextVideoRequiresIdr = ended
                videoTime.add(pts)
                if (segmentVideoFirst == null) segmentVideoFirst = pts
                segmentVideoCount++
                videoCount++
                lastVideoHasEnd = ended
                segmentEnded = ended
            } else {
                val frames = readAdts(bytes, 9 + headerSize)
                val previousPts = audioTime.last
                if (previousPts != null) {
                    val rate = audioConfig!!.sampleRate.toLong()
                    val observedTicks = pts - previousPts
                    val expectedNumerator = previousAudioPesFrames * 1024L * 90_000L
                    // Compare observed PTS with parsed frame duration as an exact rational.
                    // One 90 kHz tick admits timestamp rounding, not a missing AAC frame.
                    ensure(kotlin.math.abs(observedTicks * rate - expectedNumerator) <= rate, CompatTsProfileFailure.TIMESTAMP)
                }
                audioTime.add(pts)
                previousAudioPesFrames = frames.toLong()
                if (segmentAudioFirst == null) segmentAudioFirst = pts
                segmentAudioCount++
                audioCount++
            }
        }

        private fun readAvc(bytes: ByteArray, from: Int, first: Boolean): Boolean {
            var at = from
            var nalCount = 0
            var slices = 0
            var picture: SlicePicture? = null
            var lastFirstMacroblock = -1
            var ended = false
            var trailingAud = false
            while (at < bytes.size) {
                checkpoint()
                val start = startCode(bytes, at)
                ensure(start > 0, CompatTsProfileFailure.AVC)
                val header = at + start
                ensure(header < bytes.size, CompatTsProfileFailure.INCOMPLETE)
                var next = header + 1
                while (next < bytes.size && startCode(bytes, next) == 0) {
                    if (next and 4095 == 0) checkpoint()
                    next++
                }
                var end = next
                while (end > header + 1 && bytes[end - 1] == 0.toByte()) end--
                val h = u(bytes[header])
                ensure(h and 0x80 == 0, CompatTsProfileFailure.AVC)
                val type = h and 31
                ensure(!ended, CompatTsProfileFailure.AVC)
                if (nalCount++ == 0) ensure(type == 9, CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT)
                val raw = bytes.copyOfRange(header, end)
                val rbsp = unescape(bytes, header + 1, end, checkpoint)
                ensure(!trailingAud || type == 10 || type == 11, CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT)
                when (type) {
                    9 -> {
                        ensure(h and 0x60 == 0 && rbsp.size == 1 && u(rbsp[0]) and 31 == 0x10, CompatTsProfileFailure.AVC)
                        if (nalCount != 1) {
                            ensure(slices > 0 && !trailingAud, CompatTsProfileFailure.UNSUPPORTED_AU_LAYOUT)
                            trailingAud = true
                        }
                    }
                    7 -> {
                        ensure(slices == 0 && h and 0x60 != 0, CompatTsProfileFailure.AVC)
                        val parsed = parseSps(rbsp)
                        stable(spsBytes, raw)
                        spsBytes = raw
                        sps = parsed
                    }
                    8 -> {
                        ensure(slices == 0 && h and 0x60 != 0 && sps != null, CompatTsProfileFailure.AVC)
                        val parsed = parsePps(rbsp, sps!!)
                        stable(ppsBytes, raw)
                        ppsBytes = raw
                        pps = parsed
                    }
                    1, 5 -> {
                        ensure(sps != null && pps != null, CompatTsProfileFailure.AVC)
                        if (first) ensure(type == 5, CompatTsProfileFailure.AVC)
                        ensure(type != 5 || h and 0x60 != 0, CompatTsProfileFailure.AVC)
                        val slice = readSlice(rbsp, type, (h ushr 5) and 3, sps!!, pps!!)
                        ensure(if (slices == 0) slice.firstMacroblock == 0
                            else slice.firstMacroblock > lastFirstMacroblock, CompatTsProfileFailure.AVC)
                        // Progressive Baseline eliminates field/colour-plane identity. The
                        // remaining discriminating slice-header values must match exactly.
                        ensure(picture == null || picture == slice.picture, CompatTsProfileFailure.AVC)
                        picture = slice.picture
                        lastFirstMacroblock = slice.firstMacroblock
                        slices++
                    }
                    6 -> { ensure(h and 0x60 == 0, CompatTsProfileFailure.AVC); readSei(rbsp, checkpoint) }
                    10, 11 -> {
                        ensure(slices > 0 && h and 0x60 == 0 && rbsp.isEmpty(), CompatTsProfileFailure.AVC)
                        ended = true
                    }
                    12 -> {
                        ensure(h and 0x60 == 0 && rbsp.isNotEmpty() && u(rbsp.last()) == 0x80, CompatTsProfileFailure.AVC)
                        for (i in 0 until rbsp.lastIndex) {
                            if (i and 4095 == 0) checkpoint()
                            ensure(u(rbsp[i]) == 0xff, CompatTsProfileFailure.AVC)
                        }
                    }
                    else -> fail(CompatTsProfileFailure.AVC)
                }
                at = next
            }
            ensure(slices > 0, CompatTsProfileFailure.INCOMPLETE)
            lastVideoHasTrailingAud = trailingAud
            return ended
        }

        private fun readAdts(b: ByteArray, from: Int): Int {
            var at = from
            var frames = 0
            while (at < b.size) {
                checkpoint()
                ensure(at + 7 <= b.size, CompatTsProfileFailure.INCOMPLETE)
                ensure(u(b[at]) == 0xff && u(b[at + 1]) and 0xfe == 0xf0, CompatTsProfileFailure.AAC)
                // MPEG-4, layer 0, LC, 1024 samples, explicit mono/stereo configuration.
                ensure(u(b[at + 2]) ushr 6 == 1, CompatTsProfileFailure.AAC)
                val rateIndex = (u(b[at + 2]) ushr 2) and 15
                ensure(rateIndex in SAMPLE_RATES.indices, CompatTsProfileFailure.AAC)
                val channels = ((u(b[at + 2]) and 1) shl 2) or (u(b[at + 3]) ushr 6)
                ensure(channels in 1..2, CompatTsProfileFailure.AAC)
                ensure(u(b[at + 6]) and 3 == 0, CompatTsProfileFailure.AAC)
                val config = AudioConfig(SAMPLE_RATES[rateIndex], channels, u(b[at + 1]) and 1)
                ensure(audioConfig == null || config == audioConfig, CompatTsProfileFailure.FORMAT_CHANGE)
                audioConfig = config
                // CRC-protected ADTS needs CRC checking; not admitted by this narrower profile.
                ensure(config.protectionAbsent == 1, CompatTsProfileFailure.AAC)
                val length = ((u(b[at + 3]) and 3) shl 11) or (u(b[at + 4]) shl 3) or (u(b[at + 5]) ushr 5)
                ensure(length > 7 && length <= b.size - at, CompatTsProfileFailure.INCOMPLETE)
                at += length
                frames++
            }
            ensure(frames > 0 && at == b.size, CompatTsProfileFailure.INCOMPLETE)
            audioFrameCount += frames
            segmentAudioFrameCount += frames
            return frames
        }
    }

    private class Timeline {
        var first: Long? = null
        var last: Long? = null
        fun add(pts: Long) {
            val previous = last
            if (previous != null) ensure(pts > previous && pts - previous <= MAX_GAP, CompatTsProfileFailure.TIMESTAMP)
            if (first == null) first = pts
            ensure(pts - first!! <= MAX_DURATION, CompatTsProfileFailure.LIMIT)
            last = pts
        }
    }

    private class PesReader(private val completed: (ByteArray) -> Unit) {
        private var buffer: ByteArrayOutputStream? = null
        fun consume(bytes: ByteArray, at: Int, start: Boolean) {
            if (start) { finish(); buffer = ByteArrayOutputStream() }
            val current = buffer ?: fail(CompatTsProfileFailure.INCOMPLETE)
            ensure(current.size() <= MAX_PES_BYTES - (bytes.size - at), CompatTsProfileFailure.LIMIT)
            current.write(bytes, at, bytes.size - at)
        }
        fun finish() {
            val current = buffer ?: return
            buffer = null
            completed(current.toByteArray())
        }
    }

    private class SectionReader(private val completed: (ByteArray) -> Unit) {
        private val data = ByteArray(1024)
        private var size = 0
        private var expected = -1
        val isComplete get() = size == 0
        fun consume(bytes: ByteArray, from: Int, start: Boolean) {
            var at = from
            if (start) {
                ensure(at < bytes.size, CompatTsProfileFailure.PSI)
                val pointer = u(bytes[at++])
                ensure(pointer <= bytes.size - at, CompatTsProfileFailure.PSI)
                val end = at + pointer
                if (size > 0) {
                    while (at < end && size > 0) append(bytes[at++])
                    ensure(size == 0, CompatTsProfileFailure.INCOMPLETE)
                }
                while (at < end) ensure(u(bytes[at++]) == 0xff, CompatTsProfileFailure.PSI)
                ensure(size == 0, CompatTsProfileFailure.INCOMPLETE)
            } else if (size == 0) {
                ensure((at until bytes.size).all { u(bytes[it]) == 0xff }, CompatTsProfileFailure.PSI)
                return
            }
            while (at < bytes.size) {
                if (size == 0 && u(bytes[at]) == 0xff) {
                    ensure((at until bytes.size).all { u(bytes[it]) == 0xff }, CompatTsProfileFailure.PSI)
                    return
                }
                append(bytes[at++])
            }
        }
        private fun append(value: Byte) {
            ensure(size < data.size, CompatTsProfileFailure.LIMIT)
            data[size++] = value
            if (size == 3) {
                val length = word(data, 1) and 0x0fff
                ensure(length in 9..1021, CompatTsProfileFailure.PSI)
                expected = 3 + length
            }
            if (size == expected) {
                val section = data.copyOf(size)
                size = 0
                expected = -1
                completed(section)
            }
        }
    }

    private data class AudioConfig(val sampleRate: Int, val channels: Int, val protectionAbsent: Int)
    private data class Sps(
        val id: Int, val frameBits: Int, val pocType: Int, val pocBits: Int,
        val deltaAlwaysZero: Boolean, val pictureMacroblocks: Int,
    )
    private data class Pps(val id: Int)
    private data class SlicePicture(
        val ppsId: Int, val frameNum: Int, val nalRefIdc: Int, val idr: Boolean,
        val idrPicId: Int?, val pocLsb: Int?, val deltaPoc: Int?, val sliceType: Int,
    )
    private data class Slice(val firstMacroblock: Int, val picture: SlicePicture)

    private fun parseSps(rbsp: ByteArray): Sps {
        val b = Bits(rbsp)
        ensure(b.bits(8) == 66, CompatTsProfileFailure.AVC)
        ensure(b.bits(8) and 3 == 0, CompatTsProfileFailure.AVC)
        ensure(b.bits(8) in setOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52), CompatTsProfileFailure.AVC)
        val id = b.ue(31)
        val frameBits = b.ue(12) + 4
        val pocType = b.ue(2)
        var pocBits = 0
        var deltaAlwaysZero = false
        if (pocType == 0) pocBits = b.ue(12) + 4
        if (pocType == 1) {
            deltaAlwaysZero = b.bit()
            b.se(); b.se()
            repeat(b.ue(255)) { b.se() }
        }
        b.ue(16)
        ensure(!b.bit(), CompatTsProfileFailure.AVC) // No frame_num gaps.
        val width = (b.ue(255) + 1) * 16
        val height = (b.ue(255) + 1) * 16
        ensure(b.bit(), CompatTsProfileFailure.AVC) // Progressive frame_mbs_only_flag.
        b.bit()
        if (b.bit()) {
            val left = b.ue(2047); val right = b.ue(2047)
            val top = b.ue(2047); val bottom = b.ue(2047)
            ensure((left + right) * 2 < width && (top + bottom) * 2 < height, CompatTsProfileFailure.AVC)
        }
        if (b.bit()) readVui(b)
        b.trailing()
        return Sps(id, frameBits, pocType, pocBits, deltaAlwaysZero, (width / 16) * (height / 16))
    }

    private fun readVui(b: Bits) {
        if (b.bit()) { if (b.bits(8) == 255) { ensure(b.bits(16) > 0 && b.bits(16) > 0, CompatTsProfileFailure.AVC) } }
        if (b.bit()) b.bit()
        if (b.bit()) { b.bits(3); b.bit(); if (b.bit()) { b.bits(8); b.bits(8); b.bits(8) } }
        if (b.bit()) { b.ue(5); b.ue(5) }
        if (b.bit()) { ensure(b.longBits(32) > 0 && b.longBits(32) > 0, CompatTsProfileFailure.AVC); b.bit() }
        val nalHrd = b.bit()
        if (nalHrd) readHrd(b)
        val vclHrd = b.bit()
        if (vclHrd) readHrd(b)
        if (nalHrd || vclHrd) b.bit()
        b.bit()
        if (b.bit()) {
            b.bit(); b.ue(16); b.ue(16); b.ue(16); b.ue(16)
            ensure(b.ue(16) == 0, CompatTsProfileFailure.AVC) // max_num_reorder_frames
            b.ue(16)
        }
    }

    private fun readHrd(b: Bits) {
        val count = b.ue(31) + 1
        b.bits(4); b.bits(4)
        repeat(count) { b.ue(); b.ue(); b.bit() }
        repeat(4) { b.bits(5) }
    }

    private fun parsePps(rbsp: ByteArray, sps: Sps): Pps {
        val b = Bits(rbsp)
        val id = b.ue(255)
        ensure(b.ue(31) == sps.id, CompatTsProfileFailure.AVC)
        ensure(!b.bit() && !b.bit(), CompatTsProfileFailure.AVC) // CAVLC, no bottom-field POC.
        ensure(b.ue(7) == 0, CompatTsProfileFailure.AVC) // No slice groups.
        b.ue(31); b.ue(31)
        ensure(!b.bit() && b.bits(2) == 0, CompatTsProfileFailure.AVC)
        ensure(b.se() in -26..25 && b.se() in -26..25 && b.se() in -12..12, CompatTsProfileFailure.AVC)
        b.bit(); b.bit()
        ensure(!b.bit(), CompatTsProfileFailure.AVC) // No redundant pictures.
        b.trailing() // Extension syntax, including transform_8x8, is outside Baseline profile.
        return Pps(id)
    }

    private fun readSlice(rbsp: ByteArray, type: Int, nalRefIdc: Int, sps: Sps, pps: Pps): Slice {
        val b = Bits(rbsp)
        val firstMacroblock = b.ue(sps.pictureMacroblocks - 1)
        val sliceType = b.ue(9) % 5
        ensure(sliceType == 0 || sliceType == 2, CompatTsProfileFailure.AVC)
        ensure(type != 5 || sliceType == 2, CompatTsProfileFailure.AVC)
        ensure(b.ue(255) == pps.id, CompatTsProfileFailure.AVC)
        val frameNum = b.bits(sps.frameBits)
        val idrPicId = if (type == 5) b.ue(65535) else null
        val pocLsb = if (sps.pocType == 0) b.bits(sps.pocBits) else null
        val deltaPoc = if (sps.pocType == 1 && !sps.deltaAlwaysZero) b.se() else null
        // Entropy data is intentionally not decoded. At least some payload must follow this
        // discriminating header, and the final nonzero byte must contain RBSP trailing bits.
        ensure(b.remaining > 1 && rbsp.isNotEmpty() && rbsp.last() != 0.toByte(), CompatTsProfileFailure.INCOMPLETE)
        return Slice(firstMacroblock, SlicePicture(pps.id, frameNum, nalRefIdc, type == 5,
            idrPicId, pocLsb, deltaPoc, sliceType))
    }

    private fun readSei(rbsp: ByteArray, checkpoint: () -> Unit) {
        ensure(rbsp.isNotEmpty() && u(rbsp.last()) == 0x80, CompatTsProfileFailure.AVC)
        var at = 0
        while (at < rbsp.size - 1) {
            var type = 0
            while (at < rbsp.size - 1 && u(rbsp[at]) == 255) { if (at and 4095 == 0) checkpoint(); type += 255; at++ }
            ensure(at < rbsp.size - 1, CompatTsProfileFailure.AVC)
            type += u(rbsp[at++])
            var size = 0
            while (at < rbsp.size - 1 && u(rbsp[at]) == 255) { if (at and 4095 == 0) checkpoint(); size += 255; at++ }
            ensure(at < rbsp.size - 1, CompatTsProfileFailure.AVC)
            size += u(rbsp[at++])
            ensure(size <= rbsp.size - 1 - at, CompatTsProfileFailure.INCOMPLETE)
            // Only user_data_unregistered (e.g. encoder signature); no timing/recovery or
            // alternative-format SEI silently changing our admitted interpretation.
            ensure(type == 5 && size >= 16, CompatTsProfileFailure.AVC)
            at += size
        }
        ensure(at == rbsp.size - 1, CompatTsProfileFailure.AVC)
    }

    private class Bits(private val data: ByteArray) {
        private var at = 0
        val remaining get() = data.size * 8 - at
        fun bit() = bits(1) != 0
        fun bits(count: Int): Int = longBits(count).toInt()
        fun longBits(count: Int): Long {
            ensure(count in 0..32 && remaining >= count, CompatTsProfileFailure.INCOMPLETE)
            var value = 0L
            repeat(count) { value = (value shl 1) or ((u(data[at / 8]) ushr (7 - at++ % 8)) and 1).toLong() }
            return value
        }
        fun ue(max: Int = Int.MAX_VALUE - 1): Int {
            var zeros = 0
            while (!bit()) { ensure(++zeros <= 30, CompatTsProfileFailure.LIMIT) }
            val value = ((1L shl zeros) - 1 + longBits(zeros))
            ensure(value <= max, CompatTsProfileFailure.AVC)
            return value.toInt()
        }
        fun se(): Int { val v = ue(); return if (v and 1 == 0) -(v / 2) else (v + 1) / 2 }
        fun trailing() {
            ensure(remaining in 1..8 && bit(), CompatTsProfileFailure.AVC)
            while (remaining > 0) ensure(!bit(), CompatTsProfileFailure.AVC)
        }
    }

    private fun validateSection(b: ByteArray, table: Int) {
        ensure(b.size >= 12 && u(b[0]) == table && u(b[1]) and 0xf0 == 0xb0, CompatTsProfileFailure.PSI)
        ensure((word(b, 1) and 0xfff) + 3 == b.size, CompatTsProfileFailure.PSI)
        ensure(u(b[5]) and 0xc1 == 0xc1 && u(b[6]) == 0 && u(b[7]) == 0, CompatTsProfileFailure.PROGRAM)
        var crc = -1
        for (byte in b) {
            crc = crc xor (u(byte) shl 24)
            repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
        }
        ensure(crc == 0, CompatTsProfileFailure.PSI)
    }

    private fun timestamp(b: ByteArray, at: Int, prefix: Int): Long {
        ensure(at + 5 <= b.size && u(b[at]) ushr 4 == prefix, CompatTsProfileFailure.TIMESTAMP)
        ensure(u(b[at]) and 1 == 1 && u(b[at + 2]) and 1 == 1 && u(b[at + 4]) and 1 == 1, CompatTsProfileFailure.TIMESTAMP)
        return ((u(b[at]).toLong() and 14) shl 29) or (u(b[at + 1]).toLong() shl 22) or
            ((u(b[at + 2]).toLong() and 254) shl 14) or (u(b[at + 3]).toLong() shl 7) or (u(b[at + 4]).toLong() ushr 1)
    }

    private fun startCode(b: ByteArray, at: Int): Int {
        if (at + 3 <= b.size && b[at] == 0.toByte() && b[at + 1] == 0.toByte()) {
            if (b[at + 2] == 1.toByte()) return 3
            if (at + 4 <= b.size && b[at + 2] == 0.toByte() && b[at + 3] == 1.toByte()) return 4
        }
        return 0
    }

    private fun unescape(b: ByteArray, from: Int, end: Int, checkpoint: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream(end - from)
        var at = from
        var zeros = 0
        while (at < end) {
            if (at and 4095 == 0) checkpoint()
            val value = u(b[at++])
            if (zeros >= 2 && value == 3) {
                ensure(at < end && u(b[at]) <= 3, CompatTsProfileFailure.AVC)
                zeros = 0
                continue
            }
            ensure(zeros < 2 || value > 2, CompatTsProfileFailure.AVC)
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun stable(previous: ByteArray?, next: ByteArray) =
        ensure(previous == null || previous.contentEquals(next), CompatTsProfileFailure.FORMAT_CHANGE)
    private fun word(b: ByteArray, at: Int) = (u(b[at]) shl 8) or u(b[at + 1])
    private fun u(b: Byte) = b.toInt() and 255
    private fun ensure(condition: Boolean, failure: CompatTsProfileFailure) { if (!condition) fail(failure) }
    private fun fail(failure: CompatTsProfileFailure): Nothing = throw CompatTsProfileException(failure)
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
}
