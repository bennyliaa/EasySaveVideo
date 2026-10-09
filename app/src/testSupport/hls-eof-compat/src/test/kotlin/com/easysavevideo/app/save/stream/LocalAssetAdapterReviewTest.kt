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
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Executes admitted Media3 1.11.1 bytecode. Synthetic entropy is deliberately NOT decoder-valid. */
class LocalAssetAdapterReviewTest {
    @Test fun reviewPFramesInheritedConfigAndMultisliceReachActualReaders() {
        for (unbounded in listOf(false, true)) {
            val ts = SyntheticTs(false)
            val chunks = listOf(reviewPicture(ts, 90000, true, true, unbounded),
                reviewPicture(ts, 93000, false, false, unbounded), reviewPicture(ts, 96000, false, false, unbounded))
            val inspect = run(chunks)
            val hls = run(chunks, hls = true, maxRead = 1, peek = true)
            assertEquals(3, inspect.samples.getValue(C.TRACK_TYPE_VIDEO).size)
            assertEquals(listOf(C.BUFFER_FLAG_KEY_FRAME, 0, 0), inspect.samples.getValue(C.TRACK_TYPE_VIDEO).map { it.flags })
            assertEquals(normalize(inspect.samples), normalize(hls.samples))
            assertEquals(1, hls.finalizations)
        }
    }
    @Test fun reviewEosWithoutTrailingAudStillFinalizesExactlyOnce() {
        for (unbounded in listOf(false, true)) {
            val ts = SyntheticTs(false)
            val chunks = listOf(ts.segment(90000, false, true, unbounded))
            val result = run(chunks, hls = true)
            assertEquals(1, result.samples.getValue(C.TRACK_TYPE_VIDEO).size)
            assertEquals(1, result.finalizations)
        }
    }
    @Test fun reviewMultiplePesPerSegmentMatchSplitResources() {
        val ts = SyntheticTs(false)
        val chunks = (0 until 16).map { ts.segment(90000 + it * 3000L, false, false, true) }
        val split = run(chunks)
        val joined = run(listOf(SyntheticTs.concat(*chunks.toTypedArray())), hls = true)
        assertEquals(normalize(split.samples), normalize(joined.samples))
        assertEquals(16, joined.samples.getValue(C.TRACK_TYPE_VIDEO).size)
        assertEquals(1, joined.finalizations)
    }
    @Test fun reviewReleaseBeforeFinalTailCannotLaterComplete() {
        val chunks = noTail()
        val session = session(chunks)
        val reader = LocalAvcExtractor(session, TimestampAdjuster(0))
        reader.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = Capture()
            override fun endTracks() {}
            override fun seekMap(map: SeekMap) {}
        })
        reader.release()
        val source = PermitDataSourceFactory(DataSource.Factory { BytesSource(chunks, chunks, 65536) }, session, chunks.size).createDataSource()
        val length = source.open(DataSpec.Builder().setUri("esv-local:///s0.ts").build())
        rejects { reader.read(DefaultExtractorInput(source, 0, length), PositionHolder()) }
        rejects { source.close() }
        rejects { session.verifyComplete() }
    }
    private fun reviewPicture(ts: SyntheticTs, pts: Long, idr: Boolean, parameters: Boolean, unbounded: Boolean): ByteArray {
        val sps = SyntheticTs.Bits().apply {
            bits(66, 8); bits(0, 8); bits(30, 8); ue(0); ue(0); ue(2); ue(1)
            bit(false); ue(1); ue(0); bit(true); bit(true); bit(false); bit(false)
        }.finish()
        fun slice(first: Int) = SyntheticTs.nal(if (idr) 5 else 1, SyntheticTs.Bits().apply {
            ue(first); ue(if (idr) 2 else 0); ue(0); bits(if (idr) 0 else ((pts - 90000) / 3000).toInt(), 4)
            if (idr) ue(0)
            bits(0xaa, 8)
        }.finish())
        val payload = SyntheticTs.concat(SyntheticTs.nal(9, byteArrayOf(0xf0.toByte())),
            if (parameters) SyntheticTs.nal(7, sps) else byteArrayOf(),
            if (parameters) SyntheticTs.nal(8, SyntheticTs.pps()) else byteArrayOf(), slice(0), slice(1))
        return SyntheticTs.concat(ts.packet(0, SyntheticTs.concat(byteArrayOf(0), ts.pat()), true),
            ts.packet(4096, SyntheticTs.concat(byteArrayOf(0), ts.pmt()), true),
            ts.packets(256, SyntheticTs.pes(pts, payload, unbounded, 0xe0)))
    }

    private data class Sample(val pts: Long, val flags: Int, val bytes: Int, val hash: String)
    private data class Result(val samples: Map<Int, List<Sample>>, val boundaries: List<Int>, val finalizations: Int)
    private fun run(chunks: List<ByteArray>, hls: Boolean = false, delivered: List<ByteArray> = chunks,
        maxRead: Int = 64 * 1024, peek: Boolean = false, evidence: CompatTsProfileEvidence = evidence(chunks)): Result {
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val session = LocalAssetReadSession(resources(chunks), evidence) {}
        val sources = PermitDataSourceFactory(DataSource.Factory { BytesSource(chunks, delivered, maxRead) }, session, chunks.size)
        val tracks = HashMap<Int, Capture>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(type) { Capture() }
            override fun endTracks() {}
            override fun seekMap(map: SeekMap) {}
        }
        var extractor: LocalAvcExtractor? = if (hls) null else LocalAvcExtractor(session,
            TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET)).also { it.init(output) }
        val hlsFactory = if (hls) LocalHlsExtractorFactory(session) else null
        var chunkExtractor: HlsMediaChunkExtractor? = null
        val boundaries = ArrayList<Int>()
        for (index in chunks.indices) {
            val source = sources.createDataSource()
            val uri = Uri.parse("esv-local:///s$index.ts")
            val length = source.open(DataSpec.Builder().setUri(uri).build())
            try {
                val input = DefaultExtractorInput(source, 0, length)
                if (peek) { input.peekFully(ByteArray(10), 0, 10); input.resetPeekPosition() }
                if (hls && chunkExtractor == null) {
                    chunkExtractor = hlsFactory!!.createExtractor(uri, Format.Builder().build(), emptyList(),
                        TimestampAdjuster(0), emptyMap(), input, PlayerId.UNSET)
                    chunkExtractor.init(output)
                    extractor = (chunkExtractor as LocalHlsChunkExtractor).extractor
                }
                val holder = PositionHolder()
                fun read() = if (hls) chunkExtractor!!.read(input)
                    else extractor!!.read(input, holder) != Extractor.RESULT_END_OF_INPUT
                while (read()) {}
                assertFalse(read()) // Same EOF is idempotent, including intermediate resources.
                assertFalse(read())
                boundaries += tracks[C.TRACK_TYPE_VIDEO]?.samples?.size ?: 0
            } finally { source.close() }
        }
        session.verifyComplete()
        val result = Result(tracks.mapValues { it.value.samples.toList() }, boundaries, extractor!!.finalizationCalls)
        extractor.release()
        return result
    }
    private class Capture : TrackOutput {
        val samples = ArrayList<Sample>()
        private val pending = ByteArrayOutputStream()
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEnd: Boolean, part: Int): Int = throw AssertionError()
        override fun sampleData(input: ParsableByteArray, length: Int, part: Int) {
            val bytes = ByteArray(length); input.readBytes(bytes, 0, length); pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            val from = bytes.size - offset - size
            samples += Sample(timeUs, flags, size, sha(bytes.copyOfRange(from, from + size)))
            pending.reset(); pending.write(bytes, bytes.size - offset, offset)
        }
    }
    private class BytesSource(private val approved: List<ByteArray>, private val delivered: List<ByteArray>, private val maxRead: Int) : DataSource {
        private var uri: Uri? = null
        private var input: ByteArrayInputStream? = null
        override fun addTransferListener(listener: TransferListener) {}
        override fun getUri(): Uri? = uri
        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            val index = spec.uri.toString().substringAfter("/s").substringBefore(".ts").toInt()
            input = ByteArrayInputStream(delivered[index])
            return approved[index].size.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = input!!.read(buffer, offset, minOf(length, maxRead))
        override fun close() { input?.close(); input = null; uri = null }
    }
    private fun evidence(chunks: List<ByteArray>) = CompatTsProfileProbe.inspectSources(chunks.map {
        CompatTsInput(it.size.toLong()) { ByteArrayInputStream(it) }
    })
    private fun session(chunks: List<ByteArray>) = LocalAssetReadSession(resources(chunks), evidence(chunks)) {}
    private fun resources(chunks: List<ByteArray>, identity: () -> Unit = {}) = chunks.map { AssetResource(it.size.toLong(), sha(it), identity) }
    private fun noTail(audio: Boolean = false, unbounded: Boolean = false): List<ByteArray> {
        val ts = SyntheticTs(audio)
        return listOf(ts.segment(90000, false, false, unbounded), ts.segment(93000, false, false, unbounded))
    }
    private fun normalize(samples: Map<Int, List<Sample>>): Map<Int, List<Sample>> {
        val first = samples.getValue(C.TRACK_TYPE_VIDEO).first().pts
        return samples.mapValues { (_, entries) -> entries.map { it.copy(pts = it.pts - first) } }
    }
    private fun varied(ts: SyntheticTs, pts: Long, idr: Int, parameters: Boolean): ByteArray {
        val sps = SyntheticTs.Bits().apply {
            bits(66, 8); bits(0, 8); bits(30, 8); ue(0); ue(0); ue(2); ue(1)
            bit(false); ue(1); ue(0); bit(true); bit(true); bit(false); bit(false)
        }.finish()
        fun slice(first: Int) = SyntheticTs.nal(5, SyntheticTs.Bits().apply {
            ue(first); ue(2); ue(0); bits(0, 4); ue(idr); bits(0xaa, 8)
        }.finish())
        val payload = SyntheticTs.concat(SyntheticTs.nal(9, byteArrayOf(0xf0.toByte())),
            if (parameters) SyntheticTs.nal(7, sps) else byteArrayOf(),
            if (parameters) SyntheticTs.nal(8, SyntheticTs.pps()) else byteArrayOf(), slice(0), slice(1))
        return SyntheticTs.concat(ts.packet(0, SyntheticTs.concat(byteArrayOf(0), ts.pat()), true),
            ts.packet(4096, SyntheticTs.concat(byteArrayOf(0), ts.pmt()), true),
            ts.packets(256, SyntheticTs.pes(pts, payload, false, 0xe0)))
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected bounded rejection") } catch (_: IOException) { /* Expected. */ }
    }
    private companion object {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
