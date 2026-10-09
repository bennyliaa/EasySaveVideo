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
class LocalAssetAdapterTest {
    @Test fun noTailFinalizesExactlyOnceOnlyAtFinalAssetResource() {
        val result = run(noTail())
        assertEquals(listOf(0, 2), result.boundaries)
        assertEquals(2, result.samples.getValue(C.TRACK_TYPE_VIDEO).size)
        assertEquals(1, result.finalizations)
    }
    @Test fun legacyTerminalAudAndEosSkipsFlushAndNeverAddsGhost() {
        val ts = SyntheticTs(false)
        val result = run(listOf(ts.segment(90000, false, false, false), ts.segment(93000, true, true, false)))
        assertEquals(2, result.samples.getValue(C.TRACK_TYPE_VIDEO).size)
        assertEquals(0, result.finalizations)
    }
    @Test fun sameReaderUsedByActualFactoryAndInspectPathProducesSamePayloadsAndRelativeTimeline() {
        val chunks = noTail(audio = true)
        val inspect = run(chunks)
        val hls = run(chunks, hls = true)
        assertEquals(normalize(inspect.samples), normalize(hls.samples))
        assertEquals(2, hls.samples.getValue(C.TRACK_TYPE_AUDIO).size)
        assertEquals(listOf(0, 2), hls.boundaries)
        assertEquals(1, hls.finalizations)
    }
    @Test fun finalUnboundedVideoPesNoTailIsAdmittedWithSameSamples() {
        assertEquals(run(noTail()).samples, run(noTail(unbounded = true)).samples)
    }
    @Test fun inheritedConfigurationAndMultisliceAudGroupAsTwoPictures() {
        val ts = SyntheticTs(false)
        val chunks = listOf(varied(ts, 90000, 0, true), varied(ts, 93000, 1, false))
        val inspect = run(chunks)
        val hls = run(chunks, hls = true)
        assertEquals(2, inspect.samples.getValue(C.TRACK_TYPE_VIDEO).size)
        assertEquals(normalize(inspect.samples), normalize(hls.samples))
    }
    @Test fun segmentAndConcatenatedReadsHaveIdenticalSamples() {
        val chunks = noTail(audio = true)
        assertEquals(run(chunks).samples, run(listOf(SyntheticTs.concat(*chunks.toTypedArray()))).samples)
    }
    @Test fun byteByByteReadsAndPeekAheadDoNotChangeFinalization() {
        assertEquals(run(noTail()).samples, run(noTail(), maxRead = 1, peek = true).samples)
    }
    @Test fun incompleteTransferCannotAuthorizeTail() {
        val full = noTail()
        val short = full.toMutableList().apply { this[1] = this[1].copyOf(this[1].size - 188) }
        rejects { run(full, delivered = short) }
    }
    @Test fun sameLengthMutationRejectsBeforeFlush() {
        val full = noTail()
        val mutated = full.map(ByteArray::clone)
        mutated[1][mutated[1].lastIndex] = (mutated[1].last().toInt() xor 1).toByte()
        rejects { run(full, delivered = mutated) }
    }
    @Test fun falsePendingEvidenceCannotHideMissingSample() {
        val chunks = noTail()
        rejects { run(chunks, evidence = evidence(chunks).copy(terminalPendingVcl = false)) }
    }
    @Test fun truePendingEvidenceCannotFlushAlreadyDelimitedTail() {
        val ts = SyntheticTs(false)
        val chunks = listOf(ts.segment(90000, true, true, false))
        rejects { run(chunks, evidence = evidence(chunks).copy(terminalPendingVcl = true)) }
    }
    @Test fun missingAudioCountIsNotRepairedByAvcFlush() {
        val chunks = noTail(audio = true)
        rejects { run(chunks, evidence = evidence(chunks).copy(audioFrameCount = 3)) }
    }
    @Test fun finalizationFailureIsStickyAndCallbackIsNeverRetried() {
        val gate = FinalAccessUnitGate(2, 0, true)
        var calls = 0
        rejects { gate.finish({ 1L }, { 0L }) { calls++; throw IOException("TEST") } }
        rejects { gate.finish({ 1L }, { 0L }) { calls++ } }
        assertEquals(1, calls)
        assertEquals(1, gate.callbackCount)
    }
    @Test fun successfulFinalizationIsIdempotentAndReadingAfterItRejects() {
        val gate = FinalAccessUnitGate(2, 0, true)
        var count = 1L
        repeat(3) { gate.finish({ count }, { 0L }) { count++ } }
        assertEquals(2L, count)
        assertEquals(1, gate.callbackCount)
        rejects { gate.readingAllowed() }
    }
    @Test fun finalizerRejectsUnexpectedZeroOrMultiplePendingSamples() {
        for (count in listOf(0L, 2L, 3L)) {
            val gate = FinalAccessUnitGate(2, 0, true)
            rejects { gate.finish({ count }, { 0L }) { fail("must not flush") } }
            assertEquals(0, gate.callbackCount)
        }
    }
    @Test fun outOfOrderResourceCannotBorrowFinality() {
        val session = session(noTail())
        rejects { session.open(1, 0, -1, 564) }
    }
    @Test fun rangedOrShortOpensRejectAndPoisonSession() {
        for ((position, requested, returned) in listOf(Triple(1L, -1L, 563L), Triple(0L, 188L, 188L), Triple(0L, -1L, 188L))) {
            val session = session(noTail())
            rejects { session.open(0, position, requested, returned) }
            rejects { session.check() }
        }
    }
    @Test fun foreignGenerationPermitRejects() {
        val chunks = noTail()
        val first = session(chunks)
        val second = session(chunks)
        val foreign = first.open(0, 0, -1, chunks[0].size.toLong())
        second.open(0, 0, -1, chunks[0].size.toLong())
        rejects { second.received(foreign, chunks[0], 0, chunks[0].size) }
    }
    @Test fun inputRecreationAtSameOffsetRejects() {
        val chunks = noTail()
        val session = session(chunks)
        session.claimExtraction()
        session.open(0, 0, -1, chunks[0].size.toLong())
        session.bind(Any(), 0, chunks[0].size.toLong())
        rejects { session.bind(Any(), 0, chunks[0].size.toLong()) }
    }
    @Test fun closeWithoutParserEofIsAbandonmentNotCompletion() {
        val chunks = noTail()
        val session = session(chunks)
        val permit = session.open(0, 0, -1, chunks[0].size.toLong())
        session.received(permit, chunks[0], 0, chunks[0].size)
        rejects { session.close(permit) }
        rejects { session.verifyComplete() }
    }
    @Test fun receivingAllBytesWithoutConsumingThemCannotFinalize() {
        val chunks = noTail()
        val session = session(chunks)
        session.claimExtraction()
        val permit = session.open(0, 0, -1, chunks[0].size.toLong())
        val input = Any()
        session.bind(input, 0, chunks[0].size.toLong())
        session.received(permit, chunks[0], 0, chunks[0].size)
        rejects { session.extractorEof(permit, input, 188) }
    }
    @Test fun cancellationRevokesPermitsBeforeFinalization() {
        val chunks = noTail()
        val session = session(chunks)
        val permit = session.open(0, 0, -1, chunks[0].size.toLong())
        session.revoke()
        rejects { session.received(permit, chunks[0], 0, chunks[0].size) }
        rejects { session.verifyComplete() }
    }
    @Test fun cancellationCheckpointFailureIsStickyEvenIfCallbackLaterRecovers() {
        val chunks = noTail()
        var cancelled = true
        val session = LocalAssetReadSession(resources(chunks), evidence(chunks)) {
            if (cancelled) throw IOException("TEST")
        }
        rejects { session.check() }
        cancelled = false
        rejects { session.check() }
    }
    @Test fun sameInputPositionCannotMoveBackwards() {
        val chunks = noTail()
        val session = session(chunks)
        session.claimExtraction()
        session.open(0, 0, -1, chunks[0].size.toLong())
        val input = Any()
        session.bind(input, 0, chunks[0].size.toLong())
        session.bind(input, 188, chunks[0].size.toLong())
        rejects { session.bind(input, 0, chunks[0].size.toLong()) }
    }
    @Test fun changedIdentityBeforeReadCompletionRejects() {
        val chunks = noTail()
        var changed = false
        val session = LocalAssetReadSession(resources(chunks) { if (changed) throw IOException("TEST") }, evidence(chunks)) {}
        val permit = session.open(0, 0, -1, chunks[0].size.toLong())
        changed = true
        rejects { session.received(permit, chunks[0], 0, chunks[0].size) }
    }
    @Test fun duplicateExtractionGenerationAndSeekAreRejected() {
        val session = session(noTail())
        val reader = LocalAvcExtractor(session, TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET))
        rejects { LocalAvcExtractor(session, TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET)) }
        rejects { reader.seek(0, 0) }
    }
    @Test fun localDataSourceDecoratorRejectsUnapprovedAddressAndRange() {
        val chunks = noTail()
        for (uri in listOf("file:///s0.ts", "https://example.invalid/s0.ts", "esv-local:///s00.ts", "esv-local:///s2.ts")) {
            val factory = PermitDataSourceFactory(DataSource.Factory { BytesSource(chunks, chunks, 64 * 1024) }, session(chunks), chunks.size)
            rejects { factory.createDataSource().open(DataSpec.Builder().setUri(uri).build()) }
        }
        val factory = PermitDataSourceFactory(DataSource.Factory { BytesSource(chunks, chunks, 64 * 1024) }, session(chunks), chunks.size)
        rejects { factory.createDataSource().open(DataSpec.Builder().setUri("esv-local:///s0.ts").setPosition(188).build()) }
    }
    @Test fun reusableHlsChunkRejectsRecreationAndTrickPlay() {
        for (truncated in listOf(false, true)) {
            val session = session(noTail())
            val chunk = LocalHlsChunkExtractor(LocalAvcExtractor(session, TimestampAdjuster(0)), session)
            assertTrue(chunk.isReusable)
            assertFalse(chunk.isPackedAudioExtractor)
            rejects { if (truncated) chunk.onTruncatedSegmentParsed() else chunk.recreate() }
        }
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
