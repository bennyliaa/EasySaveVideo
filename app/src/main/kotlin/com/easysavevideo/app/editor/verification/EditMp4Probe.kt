package com.easysavevideo.app.editor.verification

import com.easysavevideo.library.edit.EditAudioProfile
import com.easysavevideo.library.edit.EditOutputExpectation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

internal data class EditMp4Track(
    val kind: String,
    val sampleSizes: IntArray,
    val sampleBytes: Long,
    val timescale: Long,
    val durationTicks: Long,
    val lastDurationTicks: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val avcProfile: Int?,
    val presentationOffsetUs: Long = 0,
) {
    val sampleCount: Int get() = sampleSizes.size
    private fun micros(ticks: Long) = ticks / timescale * 1_000_000 + ticks % timescale * 1_000_000 / timescale
    val durationUs: Long get() = micros(durationTicks)
    val spanUs: Long get() = micros(durationTicks - lastDurationTicks)
    val lastDurationUs: Long get() = micros(lastDurationTicks)
}

/**
 * Independent flat-MP4 extent/timing walk. No Save, Record, HLS, URL or transport receipt is used.
 * Every chunk must fit wholly inside mdat and chunks must not overlap. The declared sample count,
 * sizes, stts duration and extractor's entire sample walk are reconciled before any decode proof.
 * This conservative output subset excludes fragmented MP4, external data, encryption and B frames.
 */
internal object EditMp4Probe {
    private data class Atom(val type: String, val payload: Long, val end: Long)
    private data class Extent(val start: Long, val end: Long)
    private val brands = setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1")

    fun read(channel: FileChannel, size: Long, expected: EditOutputExpectation,
        checkpoint: () -> Unit = {}): List<EditMp4Track> {
        if (size !in 32..expected.maxBytes || channel.size() != size) invalidEditOutput()
        var atoms = 0
        fun bytes(at: Long, count: Int, lower: Long = 0, upper: Long = size): ByteArray {
            checkpoint()
            if (at < lower || count < 0 || at > upper || count.toLong() > upper - at) invalidEditOutput()
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                checkpoint()
                if (channel.read(buffer, at + buffer.position()) <= 0) invalidEditOutput()
            }
            return buffer.array()
        }
        fun uint(at: Long, atom: Atom) = ByteBuffer.wrap(bytes(at, 4, atom.payload, atom.end)).int.toLong() and 0xffffffffL
        fun ushort(at: Long, atom: Atom) = ByteBuffer.wrap(bytes(at, 2, atom.payload, atom.end)).short.toInt() and 0xffff
        fun long(at: Long, atom: Atom) = ByteBuffer.wrap(bytes(at, 8, atom.payload, atom.end)).long
        fun children(start: Long, end: Long): List<Atom> {
            if (start < 0 || end < start || end > size) invalidEditOutput()
            val result = ArrayList<Atom>()
            var at = start
            while (at < end) {
                checkpoint()
                if (++atoms > EditVerificationBounds.MAX_ATOMS || end - at < 8) invalidEditOutput()
                val header = ByteBuffer.wrap(bytes(at, 8, start, end)).order(ByteOrder.BIG_ENDIAN)
                val shortSize = header.int.toLong() and 0xffffffffL
                val tag = ByteArray(4).also(header::get).toString(Charsets.ISO_8859_1)
                val headerBytes = if (shortSize == 1L) 16L else 8L
                val length = when (shortSize) {
                    0L -> end - at
                    1L -> ByteBuffer.wrap(bytes(at + 8, 8, start, end)).long
                    else -> shortSize
                }
                if (length < headerBytes || length > end - at) invalidEditOutput()
                result.add(Atom(tag, at + headerBytes, at + length)); at += length
            }
            return result
        }
        fun only(children: List<Atom>, type: String) = children.singleOrNull { it.type == type } ?: invalidEditOutput()
        fun child(parent: Atom, type: String) = only(children(parent.payload, parent.end), type)
        fun fullTable(atom: Atom, entryBytes: Int, maximum: Int = EditVerificationBounds.MAX_SAMPLES): Int {
            if (uint(atom.payload, atom) != 0L) invalidEditOutput()
            val count = uint(atom.payload + 4, atom)
            if (count !in 1..maximum.toLong() || atom.end - atom.payload != 8L + count * entryBytes) invalidEditOutput()
            return count.toInt()
        }

        val top = children(0, size)
        if (top.isEmpty() || top.first().type != "ftyp" ||
            top.any { it.type !in setOf("ftyp", "moov", "mdat", "free", "skip", "wide") }) unsupportedEditOutput()
        val ftyp = only(top, "ftyp")
        if (ftyp.end - ftyp.payload !in 8L..4096L || (ftyp.end - ftyp.payload) % 4L != 0L) invalidEditOutput()
        val major = bytes(ftyp.payload, 4, ftyp.payload, ftyp.end).toString(Charsets.ISO_8859_1)
        if (major !in brands) unsupportedEditOutput()
        var brandAt = ftyp.payload + 8
        while (brandAt < ftyp.end) {
            if (bytes(brandAt, 4, ftyp.payload, ftyp.end).toString(Charsets.ISO_8859_1) == "qt  ") unsupportedEditOutput()
            brandAt += 4
        }
        val media = top.filter { it.type == "mdat" }.map { if (it.payload == it.end) invalidEditOutput(); Extent(it.payload, it.end) }
        if (media.isEmpty()) invalidEditOutput()
        val moov = only(top, "moov")
        val movieChildren = children(moov.payload, moov.end)
        if (movieChildren.any { it.type == "mvex" || it.type == "pssh" }) unsupportedEditOutput()
        val mvhd = only(movieChildren, "mvhd")
        val movieVersionFlags = uint(mvhd.payload, mvhd)
        val movieVersion = movieVersionFlags ushr 24
        if (movieVersion !in 0L..1L || movieVersionFlags and 0xffffffL != 0L) unsupportedEditOutput()
        val movieScaleAt = mvhd.payload + if (movieVersion == 0L) 12 else 20
        val movieScale = uint(movieScaleAt, mvhd)
        val movieTicks = if (movieVersion == 0L) uint(movieScaleAt + 4, mvhd) else long(movieScaleAt + 4, mvhd)
        if (movieScale !in 1..1_000_000_000L || movieTicks <= 0 || movieTicks / movieScale > 3600) invalidEditOutput()
        val movieDurationUs = movieTicks / movieScale * 1_000_000 + movieTicks % movieScale * 1_000_000 / movieScale
        if (abs(movieDurationUs - expected.durationUs) > expected.durationToleranceUs + expected.audioPrimingToleranceUs +
            EditVerificationBounds.TIMESTAMP_ROUNDING_US) invalidEditOutput()
        val tracks = movieChildren.filter { it.type == "trak" }
        if (tracks.size != (if (expected.audio is EditAudioProfile.Silent) 1 else 2)) invalidEditOutput()
        val output = ArrayList<EditMp4Track>()
        val chunkExtents = ArrayList<Extent>()
        tracks.forEach { track ->
            checkpoint()
            val trackChildren = children(track.payload, track.end)
            val mdia = only(trackChildren, "mdia")
            val mdiaChildren = children(mdia.payload, mdia.end)
            val hdlr = only(mdiaChildren, "hdlr")
            val kind = bytes(hdlr.payload + 8, 4, hdlr.payload, hdlr.end).toString(Charsets.ISO_8859_1)
            if (kind !in setOf("vide", "soun") || output.any { it.kind == kind }) invalidEditOutput()
            val video = kind == "vide"
            if (!video && expected.audio !is EditAudioProfile.Aac) invalidEditOutput()
            val tkhd = only(trackChildren, "tkhd")
            val tkhdVersion = uint(tkhd.payload, tkhd) ushr 24
            if (tkhdVersion !in 0L..1L) unsupportedEditOutput()
            val trackTicks = if (tkhdVersion == 0L) uint(tkhd.payload + 20, tkhd) else long(tkhd.payload + 28, tkhd)
            if (trackTicks <= 0 || trackTicks / movieScale > 3600) invalidEditOutput()
            val trackDurationUs = trackTicks / movieScale * 1_000_000 + trackTicks % movieScale * 1_000_000 / movieScale
            val trackTolerance = expected.durationToleranceUs + if (video) 0 else expected.audioPrimingToleranceUs
            if (abs(trackDurationUs - expected.durationUs) > trackTolerance + EditVerificationBounds.TIMESTAMP_ROUNDING_US) invalidEditOutput()
            val matrixAt = tkhd.payload + if (tkhdVersion == 0L) 40 else 52
            // Physical display-oriented output: no rotation, shear, translation or perspective.
            val identity = longArrayOf(65536, 0, 0, 0, 65536, 0, 0, 0, 1L shl 30)
            repeat(9) { if (uint(matrixAt + it * 4L, tkhd) != identity[it]) unsupportedEditOutput() }
            val tkhdWidth = uint(matrixAt + 36, tkhd)
            val tkhdHeight = uint(matrixAt + 40, tkhd)
            if (video && (tkhdWidth != (expected.width.toLong() shl 16) || tkhdHeight != (expected.height.toLong() shl 16))) invalidEditOutput()
            val mdhd = only(mdiaChildren, "mdhd")
            val versionFlags = uint(mdhd.payload, mdhd)
            val version = versionFlags ushr 24
            if (version !in 0L..1L || versionFlags and 0xffffffL != 0L) unsupportedEditOutput()
            val scaleAt = mdhd.payload + if (version == 0L) 12 else 20
            val scale = uint(scaleAt, mdhd)
            if (scale !in 1..1_000_000_000L) invalidEditOutput()
            val declaredTicks = if (version == 0L) uint(scaleAt + 4, mdhd) else long(scaleAt + 4, mdhd)
            var presentationOffsetUs = 0L
            val edits = trackChildren.filter { it.type == "edts" }
            if (edits.size > 1) invalidEditOutput()
            edits.singleOrNull()?.let { edts ->
                val editChildren = children(edts.payload, edts.end)
                if (editChildren.size != 1) unsupportedEditOutput()
                val elst = only(editChildren, "elst")
                val editVersionFlags = uint(elst.payload, elst)
                val editVersion = editVersionFlags ushr 24
                if (editVersion !in 0L..1L || editVersionFlags and 0xffffffL != 0L) unsupportedEditOutput()
                val editCount = uint(elst.payload + 4, elst)
                val entryBytes = if (editVersion == 0L) 12 else 20
                if (editCount !in 1..2 || elst.end - elst.payload != 8 + editCount * entryBytes) unsupportedEditOutput()
                var delayUs = 0L
                repeat(editCount.toInt()) { i ->
                    val at = elst.payload + 8 + i * entryBytes.toLong()
                    val durationTicks = if (editVersion == 0L) uint(at, elst) else long(at, elst)
                    val mediaTicks = if (editVersion == 0L) uint(at + 4, elst).toInt().toLong() else long(at + 8, elst)
                    val rateAt = at + if (editVersion == 0L) 8 else 16
                    if (durationTicks <= 0 || durationTicks / movieScale > 3600 || ushort(rateAt, elst) != 1 ||
                        ushort(rateAt + 2, elst) != 0) unsupportedEditOutput()
                    val durationUs = durationTicks / movieScale * 1_000_000 + durationTicks % movieScale * 1_000_000 / movieScale
                    if (editCount == 2L && i == 0) {
                        if (mediaTicks != -1L || video || durationUs > expected.audioPrimingToleranceUs) unsupportedEditOutput()
                        delayUs = durationUs
                    } else {
                        if (mediaTicks < 0 || mediaTicks / scale > 1) unsupportedEditOutput()
                        val mediaUs = mediaTicks / scale * 1_000_000 + mediaTicks % scale * 1_000_000 / scale
                        if ((video && mediaUs != 0L) || (!video && mediaUs > expected.audioPrimingToleranceUs) ||
                            (editCount == 2L && mediaUs != 0L) ||
                            abs(durationUs - expected.durationUs) > trackTolerance + EditVerificationBounds.TIMESTAMP_ROUNDING_US) unsupportedEditOutput()
                        presentationOffsetUs = delayUs - mediaUs
                    }
                }
            }
            val minf = only(mdiaChildren, "minf")
            val minfChildren = children(minf.payload, minf.end)
            val dinf = only(minfChildren, "dinf")
            val dref = child(dinf, "dref")
            if (uint(dref.payload, dref) != 0L || uint(dref.payload + 4, dref) != 1L) unsupportedEditOutput()
            val dataRef = children(dref.payload + 8, dref.end).singleOrNull() ?: invalidEditOutput()
            if (dataRef.type != "url " || dataRef.end - dataRef.payload != 4L || uint(dataRef.payload, dataRef) != 1L) unsupportedEditOutput()
            val stbl = only(minfChildren, "stbl")
            val tables = children(stbl.payload, stbl.end)
            if (tables.any { it.type in setOf("senc", "saiz", "saio", "sbgp", "sgpd") }) unsupportedEditOutput()
            val stsd = only(tables, "stsd")
            if (uint(stsd.payload, stsd) != 0L || uint(stsd.payload + 4, stsd) != 1L) unsupportedEditOutput()
            val entry = children(stsd.payload + 8, stsd.end).singleOrNull() ?: invalidEditOutput()
            if (entry.type != (if (video) "avc1" else "mp4a") || ushort(entry.payload + 6, entry) != 1) unsupportedEditOutput()
            var profile: Int? = null
            if (video) {
                if (ushort(entry.payload + 24, entry) != expected.width || ushort(entry.payload + 26, entry) != expected.height) invalidEditOutput()
                val config = children(entry.payload + 78, entry.end)
                val avcc = only(config, "avcC")
                val configBytes = avcc.end - avcc.payload
                if (configBytes !in 7..EditVerificationBounds.MAX_CONFIG_BYTES.toLong()) unsupportedEditOutput()
                profile = EditAvcConfigPolicy.requireSupported(bytes(avcc.payload, configBytes.toInt(), avcc.payload, avcc.end), expected.width, expected.height)
                config.filter { it.type == "pasp" }.let { pasp ->
                    if (pasp.size > 1) invalidEditOutput()
                    pasp.singleOrNull()?.let {
                        if (it.end - it.payload != 8L || uint(it.payload, it) == 0L || uint(it.payload, it) != uint(it.payload + 4, it)) unsupportedEditOutput()
                    }
                }
                config.filter { it.type == "colr" }.forEach {
                    val type = bytes(it.payload, 4, it.payload, it.end).toString(Charsets.ISO_8859_1)
                    if (type !in setOf("nclc", "nclx") || it.end - it.payload != (if (type == "nclx") 11L else 10L)) unsupportedEditOutput()
                    val transfer = ushort(it.payload + 6, it)
                    if (transfer !in setOf(1, 2, 6, 13)) unsupportedEditOutput() // No PQ/HLG/unknown custom transfer.
                }
                if (config.any { it.type in setOf("sinf", "mdcv", "clli", "dvcC", "dvvC") }) unsupportedEditOutput()
            } else {
                val audio = expected.audio as EditAudioProfile.Aac
                if (ushort(entry.payload + 16, entry) != audio.channels ||
                    uint(entry.payload + 24, entry) != (audio.sampleRate.toLong() shl 16)) invalidEditOutput()
                val config = children(entry.payload + 28, entry.end)
                val esds = only(config, "esds")
                if (esds.end - esds.payload !in 6..EditVerificationBounds.MAX_CONFIG_BYTES.toLong() ||
                    config.any { it.type == "sinf" }) unsupportedEditOutput()
            }

            val stsz = only(tables, "stsz")
            if (uint(stsz.payload, stsz) != 0L) invalidEditOutput()
            val commonSize = uint(stsz.payload + 4, stsz)
            val count = uint(stsz.payload + 8, stsz)
            if (count !in 1..EditVerificationBounds.MAX_SAMPLES.toLong() ||
                (video && count != expected.videoFrameCount) ||
                stsz.end - stsz.payload != 12 + (if (commonSize == 0L) count * 4 else 0)) invalidEditOutput()
            val sizes = IntArray(count.toInt()) { index ->
                val value = if (commonSize != 0L) commonSize else uint(stsz.payload + 12 + index * 4L, stsz)
                if (value !in 1..EditVerificationBounds.MAX_SAMPLE_BYTES.toLong()) unsupportedEditOutput()
                value.toInt()
            }
            val sampleBytes = sizes.sumOf { it.toLong() }
            if (sampleBytes > expected.maxBytes) invalidEditOutput()
            val stts = only(tables, "stts")
            val timingEntries = fullTable(stts, 8)
            var timedSamples = 0L
            var ticks = 0L
            var lastDuration = 0L
            repeat(timingEntries) { index ->
                checkpoint()
                val at = stts.payload + 8 + index * 8L
                val run = uint(at, stts); val delta = uint(at + 4, stts)
                if (run <= 0 || run > count - timedSamples || delta <= 0 || delta > scale) invalidEditOutput()
                val stepUs = delta * 1_000_000.0 / scale
                val target = if (video) 1_000_000.0 / expected.frameRate
                    else 1024_000_000.0 / (expected.audio as EditAudioProfile.Aac).sampleRate
                val terminalOnly = index == timingEntries - 1 && run == 1L
                if (stepUs > target + EditVerificationBounds.TIMESTAMP_ROUNDING_US ||
                    ((video || !terminalOnly) && abs(stepUs - target) > EditVerificationBounds.TIMESTAMP_ROUNDING_US)) invalidEditOutput()
                timedSamples += run; ticks += run * delta; lastDuration = delta
            }
            if (timedSamples != count || declaredTicks <= 0 || abs(ticks - declaredTicks) > 1) invalidEditOutput()
            tables.filter { it.type == "ctts" }.let { offsets ->
                if (offsets.size > 1) invalidEditOutput()
                offsets.singleOrNull()?.let { ctts ->
                    val entries = fullTable(ctts, 8)
                    var covered = 0L
                    repeat(entries) { i ->
                        val run = uint(ctts.payload + 8 + i * 8L, ctts)
                        if (run <= 0 || run > count - covered || uint(ctts.payload + 12 + i * 8L, ctts) != 0L) unsupportedEditOutput()
                        covered += run
                    }
                    if (covered != count) invalidEditOutput()
                }
            }
            val stsc = only(tables, "stsc")
            val chunkRuns = fullTable(stsc, 12)
            val offsets = tables.filter { it.type == "stco" || it.type == "co64" }.singleOrNull() ?: invalidEditOutput()
            val offsetWidth = if (offsets.type == "stco") 4 else 8
            val chunkCount = fullTable(offsets, offsetWidth)
            val firstChunks = IntArray(chunkRuns)
            val samplesPerChunk = IntArray(chunkRuns)
            repeat(chunkRuns) { i ->
                val at = stsc.payload + 8 + i * 12L
                val firstChunk = uint(at, stsc)
                val inChunk = uint(at + 4, stsc)
                if (firstChunk !in 1..chunkCount.toLong() || inChunk !in 1..count || uint(at + 8, stsc) != 1L ||
                    (i == 0 && firstChunk != 1L) || (i > 0 && firstChunk <= firstChunks[i - 1])) invalidEditOutput()
                firstChunks[i] = firstChunk.toInt(); samplesPerChunk[i] = inChunk.toInt()
            }
            var run = 0
            var sample = 0
            repeat(chunkCount) { index ->
                checkpoint()
                if (run + 1 < chunkRuns && index + 1 >= firstChunks[run + 1]) run++
                val n = samplesPerChunk[run]
                if (n > sizes.size - sample) invalidEditOutput()
                var chunkSize = 0L
                repeat(n) { chunkSize += sizes[sample++] }
                val at = offsets.payload + 8 + index * offsetWidth.toLong()
                val offset = if (offsetWidth == 4) uint(at, offsets) else long(at, offsets)
                if (offset < 0 || offset > size || chunkSize > size - offset ||
                    media.none { offset >= it.start && chunkSize <= it.end - offset }) invalidEditOutput()
                chunkExtents.add(Extent(offset, offset + chunkSize))
            }
            if (sample != sizes.size) invalidEditOutput()
            tables.filter { it.type == "stss" }.let { sync ->
                if (sync.size > 1) invalidEditOutput()
                sync.singleOrNull()?.let { table ->
                    val n = fullTable(table, 4)
                    var last = 0L
                    repeat(n) { i ->
                        val number = uint(table.payload + 8 + i * 4L, table)
                        if (number !in 1..count || number <= last || (i == 0 && video && number != 1L)) invalidEditOutput()
                        last = number
                    }
                }
            }
            output.add(EditMp4Track(kind, sizes, sampleBytes, scale, ticks, lastDuration,
                if (video) expected.width else 0, if (video) expected.height else 0, 0, profile, presentationOffsetUs))
        }
        if (output.none { it.kind == "vide" }) invalidEditOutput()
        val sorted = chunkExtents.sortedBy { it.start }
        if (sorted.zipWithNext().any { (a, b) -> a.end > b.start } || channel.size() != size) invalidEditOutput()
        return output.toList()
    }
}

/** No upstream implementation is copied. Parses only the bounded AVC fields this slice accepts. */
internal object EditAvcConfigPolicy {
    fun requireSupported(config: ByteArray, expectedWidth: Int, expectedHeight: Int): Int {
        fun byte(index: Int) = config.getOrNull(index)?.toInt()?.and(255) ?: invalidEditOutput()
        if (config.size !in 9..EditVerificationBounds.MAX_CONFIG_BYTES || byte(0) != 1 ||
            byte(1) !in setOf(66, 77, 100) || byte(4) and 3 != 3) unsupportedEditOutput()
        val profile = byte(1)
        val spsCount = byte(5) and 31
        if (spsCount != 1) unsupportedEditOutput()
        val length = (byte(6) shl 8) or byte(7)
        if (length !in 4..EditVerificationBounds.MAX_CONFIG_BYTES || 8 + length >= config.size) invalidEditOutput()
        val nal = config.copyOfRange(8, 8 + length)
        if ((nal[0].toInt() and 0x9f) != 7 || nal[1].toInt() and 255 != profile) unsupportedEditOutput()
        val rbsp = ArrayList<Byte>()
        var zeros = 0
        for (i in 1 until nal.size) {
            val value = nal[i].toInt() and 255
            if (zeros >= 2 && value == 3) { zeros = 0; continue }
            rbsp.add(nal[i]); zeros = if (value == 0) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        if (bits.read(8) != profile) unsupportedEditOutput()
        bits.read(8); bits.read(8); bits.ue() // constraints, level, sequence id
        if (profile == 100) {
            if (bits.ue() != 1 || bits.ue() != 0 || bits.ue() != 0) unsupportedEditOutput()
            bits.read(1)
            if (bits.read(1) != 0) unsupportedEditOutput() // Custom scaling matrices are outside M6-A.
        }
        if (bits.ue() > 12) unsupportedEditOutput()
        when (bits.ue()) {
            0 -> if (bits.ue() > 12) unsupportedEditOutput()
            1 -> {
                bits.read(1); bits.ue(); bits.ue() // Signed values use the same bounded code length.
                val offsets = bits.ue()
                if (offsets > 256) unsupportedEditOutput()
                repeat(offsets) { bits.ue() }
            }
            2 -> Unit
            else -> unsupportedEditOutput()
        }
        if (bits.ue() > 16) unsupportedEditOutput()
        bits.read(1)
        val widthMbs = bits.ue() + 1
        val heightMapUnits = bits.ue() + 1
        if (widthMbs !in 1..128 || heightMapUnits !in 1..128 || bits.read(1) != 1) unsupportedEditOutput()
        bits.read(1)
        var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
        if (bits.read(1) != 0) { cropLeft = bits.ue(); cropRight = bits.ue(); cropTop = bits.ue(); cropBottom = bits.ue() }
        if (widthMbs * 16 - 2 * (cropLeft + cropRight) != expectedWidth ||
            heightMapUnits * 16 - 2 * (cropTop + cropBottom) != expectedHeight) invalidEditOutput()
        if (bits.read(1) != 0) {
            if (bits.read(1) != 0) {
                when (bits.read(8)) {
                    0, 1 -> Unit
                    255 -> { val sarWidth = bits.read(16); if (sarWidth <= 0 || bits.read(16) != sarWidth) unsupportedEditOutput() }
                    else -> unsupportedEditOutput()
                }
            }
            if (bits.read(1) != 0) bits.read(1)
            if (bits.read(1) != 0) {
                bits.read(3); bits.read(1)
                if (bits.read(1) != 0) {
                    bits.read(8)
                    if (bits.read(8) !in setOf(1, 2, 6, 13)) unsupportedEditOutput()
                    bits.read(8)
                }
            }
            // Remaining VUI fields do not change admitted chroma, bit depth, geometry or transfer.
        }
        var at = 8 + length
        val ppsCount = byte(at++)
        if (ppsCount != 1) unsupportedEditOutput()
        val ppsLength = (byte(at) shl 8) or byte(at + 1); at += 2
        if (ppsLength <= 0 || ppsLength > config.size - at || byte(at) and 31 != 8) invalidEditOutput()
        at += ppsLength
        if (profile != 100 && at != config.size) invalidEditOutput()
        // Optional High-profile avcC extension must still declare 4:2:0, 8-bit and no extra SPS.
        if (at != config.size && (config.size - at != 4 || byte(at) and 3 != 1 ||
                byte(at + 1) and 7 != 0 || byte(at + 2) and 7 != 0 || byte(at + 3) != 0)) unsupportedEditOutput()
        return profile
    }

    private class Bits(private val bytes: ByteArray) {
        private var bit = 0
        fun read(n: Int): Int {
            if (n !in 0..24 || bit + n > bytes.size * 8) invalidEditOutput()
            var value = 0
            repeat(n) { value = (value shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1); bit++ }
            return value
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { if (++zeros > 20) unsupportedEditOutput() }
            return ((1 shl zeros) - 1) + read(zeros)
        }
    }
}
