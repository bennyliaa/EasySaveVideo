package com.easysavevideo.app.save.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.muxer.AnnexBToAvccConverter
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.easysavevideo.save.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Executes the real admitted 1.11.1 muxer and extractor. No decoder, framework MediaExtractor,
 * HlsMediaSource lifecycle, Transformer or Android-device acceptance is implied. */
class PinnedMuxConservationTest {
    @Test fun noTailParserOutputConservedThroughPinnedMp4ToEof() {
        val ts = SyntheticTs(false)
        val original = parseTs((0..2).map { ts.segment(90_000L + it * 3_000, false, false, false) })
        assertEquals(3, original.getValue(C.TRACK_TYPE_VIDEO).samples.size)
        compare(original, roundTrip(original))
    }

    @Test fun multisliceInheritedConfigurationAndNonSyncFinalGopAreConserved() {
        val original = parseTs(profile())
        val video = original.getValue(C.TRACK_TYPE_VIDEO)
        assertEquals(listOf(1, 0, 0, 0), video.samples.map { it.flags })
        assertEquals(listOf(7, 8), video.format.initializationData.map { it[3].toInt() and 31 })
        val actual = roundTrip(original)
        compare(original, actual)
        val proof = accumulate(actual.getValue(C.TRACK_TYPE_VIDEO), false)
        assertEquals(0L, proof.videoTail().precedingSync.index)
        assertEquals(3L, proof.videoTail().lastSample.index)
    }

    @Test fun optionalAacAndVideoPayloadsFlagsAndCountsConserved() {
        val original = parseTs(profile(audioOffsetTicks = 0))
        compare(original, roundTrip(original))
    }

    @Test fun delayedAudioUsesTenKhzMovieEditListHalfUp() {
        val original = parseTs(profile(audioOffsetTicks = 140)) // 1,555 us input offset.
        val actual = roundTrip(original)
        compare(original, actual)
        assertEquals(1_555L, avOffset(original))
        assertEquals(1_600L, avOffset(actual))
    }

    @Test fun audioLeadingVideoUsesSignedAvOffsetAndSameTrackRelativeProof() {
        val original = parseTs(profile(audioOffsetTicks = -140))
        val actual = roundTrip(original)
        compare(original, actual)
        assertEquals(-1_556L, avOffset(original))
        assertEquals(-1_600L, avOffset(actual))
    }

    @Test fun absoluteNinetyKhzHalfUpBeforeSubtractingOriginIsNotRelativeTruncation() {
        val original = parseTs(profile(videoStepTicks = 3_001)).mapValues { (_, track) ->
            track.copy(samples = track.samples.map { it.copy(pts = it.pts + 6) })
        }
        val actual = roundTrip(original)
        compare(original, actual)
        val source = original.getValue(C.TRACK_TYPE_VIDEO).samples
        val observed = actual.getValue(C.TRACK_TYPE_VIDEO).samples
        val originTick = halfUp(source.first().pts, 90_000)
        val expected = source.map { (halfUp(it.pts, 90_000) - originTick) * 1_000_000 / 90_000 }
        assertEquals(expected, observed.map { it.pts - observed.first().pts })
        assertNotEquals(source.map { it.pts - source.first().pts }, expected)
    }

    @Test fun largeAbsoluteThirtyThreeBitPtsOriginPreservesShortRelativeSequence() {
        val origin = (1L shl 33) - 100_000
        val original = parseTs(profile(originTicks = origin))
        assertTrue(original.getValue(C.TRACK_TYPE_VIDEO).samples.first().pts > 24L * 60 * 60 * 1_000_000)
        val actual = roundTrip(original)
        assertEquals(0L, actual.getValue(C.TRACK_TYPE_VIDEO).samples.first().pts)
        compare(original, actual)
    }

    @Test fun threeAndFourByteAnnexBFramingNormalizesToActualMp4NalPayloads() {
        val original = parseTs(profile()).mapValues { (_, track) ->
            track.copy(samples = track.samples.map { sample ->
                val avcc = canonicalAvcSample(sample.bytes, AvcSampleFraming.ANNEX_B)
                sample.copy(bytes = annexB(avcc, fourByte = true))
            })
        }
        compare(original, roundTrip(original))
    }

    @Test fun canonicalFramingMatchesActualPinnedConverterIncludingZeroAndEscapeBytes() {
        val payloads = listOf(
            "00000165aa00000301bb",
            "0000000165aa00000301bb00",
            "00000165aa00000301bb0000",
            "00000000000165aa00000301bb000000",
            "00000109f00000000165aa00000300bb00000101cc",
        )
        payloads.forEach { hex ->
            val original = java.util.HexFormat.of().parseHex(hex)
            val converted = AnnexBToAvccConverter.DEFAULT.process(ByteBuffer.wrap(original))
            val bytes = ByteArray(converted.remaining()).also { converted.get(it) }
            assertArrayEquals(bytes, canonicalAvcSample(original, AvcSampleFraming.ANNEX_B))
            assertArrayEquals(bytes, canonicalAvcSample(bytes, AvcSampleFraming.AVCC))
        }
    }

    @Test fun changedLastAudioPayloadIsDetectedWithoutWeakeningVideoProof() {
        val original = parseTs(profile(audioOffsetTicks = 140))
        val changed = original.mapValues { (type, track) ->
            if (type != C.TRACK_TYPE_AUDIO) track else track.copy(samples = track.samples.dropLast(1) +
                track.samples.last().let { it.copy(bytes = it.bytes.clone().also { bytes -> bytes[0] = (bytes[0].toInt() xor 4).toByte() }) })
        }
        val actual = roundTrip(changed)
        assertEquals(accumulate(original.getValue(C.TRACK_TYPE_VIDEO), true).finish(),
            accumulate(actual.getValue(C.TRACK_TYPE_VIDEO), false).finish())
        val before = accumulate(original.getValue(C.TRACK_TYPE_AUDIO), true).finish()
        val after = accumulate(actual.getValue(C.TRACK_TYPE_AUDIO), false).finish()
        assertEquals(before.sampleCount, after.sampleCount)
        assertNotEquals(before.sequenceSha256, after.sequenceSha256)
        assertNotEquals(before.lastSample, after.lastSample)
    }

    @Test fun droppedLastSampleInActualMp4IsDetected() {
        val original = parseTs(profile())
        val changed = alterVideo(original) { it.dropLast(1) }
        val actual = roundTrip(changed)
        assertEquals(3, actual.getValue(C.TRACK_TYPE_VIDEO).samples.size)
        assertMismatch(original, actual)
    }

    @Test fun duplicatedLastSampleInActualMp4IsDetected() {
        val original = parseTs(profile())
        val changed = alterVideo(original) { it + it.last().copy(pts = it.last().pts + 33_334) }
        val actual = roundTrip(changed)
        assertEquals(5, actual.getValue(C.TRACK_TYPE_VIDEO).samples.size)
        assertMismatch(original, actual)
    }

    @Test fun changedLastPayloadWithSameCountAndTimingIsDetected() {
        val original = parseTs(profile())
        val changed = alterVideo(original) { samples -> samples.dropLast(1) + samples.last().let { sample ->
            sample.copy(bytes = sample.bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 4).toByte() })
        } }
        val actual = roundTrip(changed)
        assertEquals(4, actual.getValue(C.TRACK_TYPE_VIDEO).samples.size)
        assertEquals(original.getValue(C.TRACK_TYPE_VIDEO).samples.map { it.pts - 1_000_000 },
            actual.getValue(C.TRACK_TYPE_VIDEO).samples.map { it.pts })
        assertMismatch(original, actual)
    }

    @Test fun changedLastSyncFlagWithSamePayloadAndTimingIsDetected() {
        val original = parseTs(profile())
        val actual = roundTrip(alterVideo(original) { it.dropLast(1) + it.last().copy(flags = 1) })
        assertMismatch(original, actual)
    }

    @Test fun changedLastTimestampByOneVideoTickIsDetected() {
        val original = parseTs(profile())
        val actual = roundTrip(alterVideo(original) { it.dropLast(1) + it.last().copy(pts = it.last().pts + 12) })
        assertMismatch(original, actual)
    }

    private data class Sample(val pts: Long, val flags: Int, val bytes: ByteArray)
    private data class Track(val type: Int, val format: Format, val samples: List<Sample>)
    private class Capture(private val type: Int, private val mp4: Boolean) : TrackOutput {
        var format: Format? = null
        val samples = ArrayList<Sample>()
        private val pending = ByteArrayOutputStream()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEnd: Boolean, part: Int): Int {
            val bytes = ByteArray(length)
            val size = input.read(bytes, 0, length)
            if (size == C.RESULT_END_OF_INPUT) { check(allowEnd); return size }
            check(size > 0)
            pending.write(bytes, 0, size)
            return size
        }
        override fun sampleData(input: ParsableByteArray, length: Int, part: Int) {
            val bytes = ByteArray(length); input.readBytes(bytes, 0, length); pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            check(crypto == null && flags and C.BUFFER_FLAG_ENCRYPTED == 0)
            // Pinned TrackSampleTable annotates only its last sample. It is not a stored sync flag.
            val supported = C.BUFFER_FLAG_KEY_FRAME or if (mp4) C.BUFFER_FLAG_LAST_SAMPLE else 0
            check(flags and supported.inv() == 0) { "Unexpected sample flags: $flags" }
            val bytes = pending.toByteArray()
            val from = bytes.size - offset - size
            check(from == 0 && size > 0)
            samples += Sample(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME, bytes.copyOfRange(from, from + size))
            pending.reset(); pending.write(bytes, bytes.size - offset, offset)
        }
        fun track() = Track(type, requireNotNull(format), samples.toList())
    }

    private fun parseTs(chunks: List<ByteArray>): Map<Int, Track> {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val evidence = CompatTsProfileProbe.inspectSources(chunks.map { CompatTsInput(it.size.toLong()) { ByteArrayInputStream(it) } })
        val resources = chunks.map { AssetResource(it.size.toLong(), sha(it)) {} }
        val session = LocalAssetReadSession(resources, evidence) {}
        val sources = PermitDataSourceFactory(DataSource.Factory { BytesSource(chunks) }, session, chunks.size)
        val tracks = linkedMapOf<Int, Capture>()
        val extractor = LocalAvcExtractor(session, TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET))
        extractor.init(output(tracks, false))
        try {
            for (index in chunks.indices) {
                val source = sources.createDataSource()
                val length = source.open(DataSpec.Builder().setUri("esv-local:///s$index.ts").build())
                try {
                    val input = DefaultExtractorInput(source, 0, length)
                    val position = PositionHolder()
                    var calls = 0
                    while (true) {
                        check(++calls < 100_000)
                        val result = extractor.read(input, position)
                        if (result == Extractor.RESULT_END_OF_INPUT) break
                        check(result == Extractor.RESULT_CONTINUE)
                    }
                } finally { source.close() }
            }
            session.verifyComplete()
            assertEquals(1, extractor.finalizationCalls)
            return tracks.mapValues { it.value.track() }
        } finally { extractor.release() }
    }

    private fun output(tracks: MutableMap<Int, Capture>, mp4: Boolean) = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(type) { Capture(type, mp4) }
        override fun endTracks() {}
        override fun seekMap(map: SeekMap) {}
    }

    private fun roundTrip(original: Map<Int, Track>): Map<Int, Track> {
        val directory = File(requireNotNull(System.getProperty("esv.mux.output")))
        val file = File.createTempFile("synthetic-", ".mp4", directory)
        try {
            val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(file.absolutePath))
                .setSampleBatchingEnabled(false).setAttemptStreamableOutputEnabled(false).build()
            val ids = original.mapValues { muxer.addTrack(it.value.format) }
            try {
                original.values.flatMap { track -> track.samples.map { track.type to it } }
                    .sortedBy { it.second.pts }.forEach { (type, sample) ->
                        muxer.writeSampleData(ids.getValue(type), ByteBuffer.wrap(sample.bytes.clone()),
                            BufferInfo(sample.pts, sample.bytes.size, sample.flags))
                    }
            } finally { muxer.close() }
            assertTrue(file.length() in 1..1_000_000)
            val bytes = file.readBytes()
            val tracks = linkedMapOf<Int, Capture>()
            val extractor = Mp4Extractor()
            extractor.init(output(tracks, true))
            fun inputAt(position: Long): DefaultExtractorInput {
                check(position in 0..bytes.size.toLong())
                val source = ByteArrayInputStream(bytes, position.toInt(), bytes.size - position.toInt())
                return DefaultExtractorInput(DataReader { buffer, offset, length -> source.read(buffer, offset, length) }, position, bytes.size.toLong())
            }
            var input = inputAt(0)
            val position = PositionHolder()
            var reachedEof = false
            try {
                var calls = 0
                while (!reachedEof) {
                    check(++calls < 100_000)
                    when (extractor.read(input, position)) {
                        Extractor.RESULT_END_OF_INPUT -> reachedEof = true
                        Extractor.RESULT_SEEK -> input = inputAt(position.position)
                        Extractor.RESULT_CONTINUE -> Unit
                        else -> error("Unexpected extractor result")
                    }
                }
                assertTrue(reachedEof)
                return tracks.mapValues { it.value.track() }
            } finally { extractor.release() }
        } finally { check(file.delete()) }
    }

    private fun accumulate(track: Track, input: Boolean): StreamSampleAccumulator = StreamSampleAccumulator(
        if (track.type == C.TRACK_TYPE_VIDEO) StreamSampleKind.AVC else StreamSampleKind.AAC,
        if (track.type == C.TRACK_TYPE_VIDEO) 90_000 else track.format.sampleRate,
        timestampsAreMuxInput = input,
    ).also { accumulator ->
        track.samples.forEach { accumulator.add(it.pts, it.flags, it.bytes, AvcSampleFraming.ANNEX_B) }
    }

    private fun compare(original: Map<Int, Track>, actual: Map<Int, Track>) {
        assertEquals(original.keys, actual.keys)
        original.forEach { (type, source) ->
            val observed = actual.getValue(type)
            assertEquals(source.format.sampleMimeType, observed.format.sampleMimeType)
            if (type == C.TRACK_TYPE_VIDEO) {
                assertEquals(source.format.width, observed.format.width)
                assertEquals(source.format.height, observed.format.height)
            } else {
                assertEquals(source.format.channelCount, observed.format.channelCount)
                assertEquals(source.format.sampleRate, observed.format.sampleRate)
            }
            assertEquals(source.samples.size, observed.samples.size)
            source.samples.zip(observed.samples).forEach { (before, after) ->
                assertEquals(before.flags, after.flags)
                if (type == C.TRACK_TYPE_VIDEO) assertArrayEquals(canonicalAvcSample(before.bytes, AvcSampleFraming.ANNEX_B),
                    canonicalAvcSample(after.bytes, AvcSampleFraming.ANNEX_B))
                else assertArrayEquals(before.bytes, after.bytes)
            }
            val expected = accumulate(source, true)
            val extracted = accumulate(observed, false)
            assertEquals(expected.finish(), extracted.finish())
            assertEquals(expected.endpoint(), extracted.endpoint())
            if (type == C.TRACK_TYPE_VIDEO) assertEquals(expected.videoTail(), extracted.videoTail())
        }
        if (original.containsKey(C.TRACK_TYPE_AUDIO)) {
            val expected = halfUp(kotlin.math.abs(avOffset(original)), 10_000) * 100 * if (avOffset(original) < 0) -1 else 1
            assertEquals(expected, avOffset(actual))
        }
    }

    private fun assertMismatch(original: Map<Int, Track>, actual: Map<Int, Track>) {
        val before = accumulate(original.getValue(C.TRACK_TYPE_VIDEO), true).finish()
        val after = accumulate(actual.getValue(C.TRACK_TYPE_VIDEO), false).finish()
        assertNotEquals(before, after)
        assertNotEquals(before.sequenceSha256, after.sequenceSha256)
        assertNotEquals(before.lastSample, after.lastSample)
    }
    private fun alterVideo(tracks: Map<Int, Track>, change: (List<Sample>) -> List<Sample>): Map<Int, Track> =
        tracks.mapValues { (type, track) -> if (type == C.TRACK_TYPE_VIDEO) track.copy(samples = change(track.samples)) else track }
    private fun avOffset(tracks: Map<Int, Track>) = tracks.getValue(C.TRACK_TYPE_AUDIO).samples.first().pts -
        tracks.getValue(C.TRACK_TYPE_VIDEO).samples.first().pts
    private fun halfUp(us: Long, timescale: Int) = (us * timescale + 500_000) / 1_000_000

    /** Original synthetic 2-macroblock AUD pictures. First IDR contains SPS/PPS; remaining P
     * pictures inherit configuration. Slice entropy and AAC payload intentionally are not valid decode fixtures. */
    private fun profile(audioOffsetTicks: Long? = null, videoStepTicks: Long = 3_000, originTicks: Long = 90_000): List<ByteArray> {
        val ts = SyntheticTs(audioOffsetTicks != null)
        val sps = SyntheticTs.Bits().apply {
            bits(66, 8); bits(0, 8); bits(30, 8); ue(0); ue(0); ue(2); ue(1)
            bit(false); ue(1); ue(0); bit(true); bit(true); bit(false); bit(false)
        }.finish()
        return (0..3).map { index ->
            fun slice(first: Int) = SyntheticTs.nal(if (index == 0) 5 else 1, SyntheticTs.Bits().apply {
                ue(first); ue(if (index == 0) 2 else 0); ue(0); bits(index, 4)
                if (index == 0) ue(0)
                bits(0xa0 + index, 8)
            }.finish())
            val payload = SyntheticTs.concat(SyntheticTs.nal(9, byteArrayOf(0xf0.toByte())),
                if (index == 0) SyntheticTs.nal(7, sps) else byteArrayOf(),
                if (index == 0) SyntheticTs.nal(8, SyntheticTs.pps()) else byteArrayOf(), slice(0), slice(1))
            SyntheticTs.concat(ts.packet(0, SyntheticTs.concat(byteArrayOf(0), ts.pat()), true),
                ts.packet(4096, SyntheticTs.concat(byteArrayOf(0), ts.pmt()), true),
                ts.packets(256, SyntheticTs.pes(originTicks + index * videoStepTicks, payload, false, 0xe0)),
                if (audioOffsetTicks == null) byteArrayOf() else ts.packets(257, SyntheticTs.pes(
                    originTicks + audioOffsetTicks + index * 1_920,
                    java.util.HexFormat.of().parseHex("fff14c80013ffc2110"), false, 0xc0)))
        }
    }

    private fun annexB(avcc: ByteArray, fourByte: Boolean): ByteArray {
        val input = ByteBuffer.wrap(avcc)
        val output = ByteArrayOutputStream()
        while (input.hasRemaining()) {
            val size = input.int
            output.write(if (fourByte) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1))
            output.write(ByteArray(size).also { input.get(it) })
        }
        return output.toByteArray()
    }
    private class BytesSource(private val chunks: List<ByteArray>) : DataSource {
        private var uri: Uri? = null
        private var input: ByteArrayInputStream? = null
        override fun addTransferListener(listener: TransferListener) {}
        override fun getUri(): Uri? = uri
        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            val index = spec.uri.toString().substringAfter("/s").substringBefore(".ts").toInt()
            input = ByteArrayInputStream(chunks[index]); return chunks[index].size.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = input!!.read(buffer, offset, length)
        override fun close() { input?.close(); input = null; uri = null }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
