package com.easysavevideo.app.save.stream

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/** Public-API composition, pinned to the admitted Media3 1.11.1 bytes. No source fork.
 * The SAME implementation is used for inspection and HLS extraction. Neither this extractor nor
 * TsExtractor's per-input EOF may finalize elementary readers before the verified asset end.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalAvcExtractor(
    private val session: LocalAssetReadSession,
    timestampAdjuster: TimestampAdjuster,
) : Extractor {
    private val gate = FinalAccessUnitGate(session.evidence.videoAccessUnitCount,
        session.evidence.audioFrameCount, session.evidence.terminalPendingVcl)
    private val tracks = HashMap<Int, CountingTrack>()
    private val trackTypes = HashMap<Int, Int>()
    private var avc: H264Reader? = null
    private var initialized = false
    private var finished = false
    private var released = false
    private val payloadFactory = object : TsPayloadReader.Factory {
        private val base = DefaultTsPayloadReaderFactory(
            DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS, emptyList())
        override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = SparseArray()
        override fun createPayloadReader(type: Int, info: TsPayloadReader.EsInfo): TsPayloadReader? = when (type) {
            TsExtractor.TS_STREAM_TYPE_H264 -> {
                requireValid(avc == null)
                val reader = H264Reader(SeiReader(emptyList(), MimeTypes.VIDEO_MP2T),
                    false, false, MimeTypes.VIDEO_MP2T) // AUD only. Never global DETECT_ACCESS_UNITS.
                avc = reader
                shield(PesReader(reader))
            }
            TsExtractor.TS_STREAM_TYPE_AAC_ADTS -> {
                requireValid(session.evidence.audioPid != null)
                shield(base.createPayloadReader(type, info) ?: reject())
            }
            else -> null // Raw preflight rejects all other tracks; never instantiate them.
        }
    }
    private val delegate = TsExtractor(TsExtractor.MODE_HLS, 0, SubtitleParser.Factory.UNSUPPORTED,
        timestampAdjuster, payloadFactory, TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
    val finalizationCalls get() = gate.callbackCount

    init { session.claimExtraction() }

    override fun sniff(input: ExtractorInput): Boolean = protected {
        requireValid(!released && !finished)
        // Sniff may peek but cannot advance parser state or finalization.
        session.bind(input, input.position, input.length)
        delegate.sniff(input)
    }

    override fun init(output: ExtractorOutput) = protected {
        requireValid(!initialized && !released)
        initialized = true
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                requireValid(type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_AUDIO)
                val previous = trackTypes[id]
                if (previous != null) {
                    requireValid(previous == type)
                    return tracks.getValue(type)
                }
                requireValid(!tracks.containsKey(type))
                trackTypes[id] = type
                return CountingTrack(type, output.track(id, type)).also { tracks[type] = it }
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(map: SeekMap) = output.seekMap(map)
        })
    }

    override fun read(input: ExtractorInput, position: PositionHolder): Int = protected {
        requireValid(initialized && !released)
        val permit = session.bind(input, input.position, input.length)
        if (permit.extractorEof) {
            session.extractorEof(permit, input, input.position)
            return@protected Extractor.RESULT_END_OF_INPUT
        }
        gate.readingAllowed()
        val result = delegate.read(input, position)
        requireValid(result != Extractor.RESULT_SEEK)
        if (result == Extractor.RESULT_END_OF_INPUT) {
            val finalResource = session.extractorEof(permit, input, input.position)
            if (finalResource) {
                gate.finish({ tracks[C.TRACK_TYPE_VIDEO]?.samples ?: 0L },
                    { tracks[C.TRACK_TYPE_AUDIO]?.samples ?: 0L },
                    { (avc ?: reject()).endOfInputReached() })
                session.check()
                session.finishAsset(permit)
                finished = true
            }
        }
        result
    }

    override fun seek(position: Long, timeUs: Long): Unit = reject()
    override fun release() { released = true; delegate.release() }
    override fun getUnderlyingImplementation(): Extractor = delegate

    /** The wrapper is deliberately NOT a PesReader. It prevents scratch-header reparse on EVERY
     * intermediate input EOF for both video and AAC, while retaining normal PES parsing. */
    private fun shield(reader: TsPayloadReader) = object : TsPayloadReader {
        override fun init(clock: TimestampAdjuster, output: ExtractorOutput, ids: TsPayloadReader.TrackIdGenerator) =
            reader.init(clock, output, ids)
        override fun consume(bytes: ParsableByteArray, flags: Int) {
            gate.readingAllowed(); reader.consume(bytes, flags)
        }
        override fun seek(): Unit = reject()
    }

    /** Bound parser buffering and reject config-only/empty/extra video metadata before forwarding.
     * Main integration still retains its stronger canonical payload/timestamp conservation proof.
     */
    private inner class CountingTrack(private val type: Int, private val output: TrackOutput) : TrackOutput {
        private val pending = ByteArrayOutputStream()
        var samples = 0L
            private set
        override fun format(format: Format) = output.format(format)
        override fun sampleData(input: DataReader, length: Int, allowEnd: Boolean, part: Int): Int {
            requireValid(length >= 0)
            if (length == 0) return 0
            val bytes = ByteArray(minOf(length, 64 * 1024))
            val count = input.read(bytes, 0, bytes.size)
            if (count == C.RESULT_END_OF_INPUT) { if (allowEnd) return count else throw EOFException() }
            requireValid(count in 1..bytes.size)
            sampleData(ParsableByteArray(bytes, count), count, part)
            return count
        }
        override fun sampleData(input: ParsableByteArray, length: Int, part: Int) {
            session.check()
            requireValid(part == TrackOutput.SAMPLE_DATA_PART_MAIN && length >= 0 && length <= input.bytesLeft())
            requireValid(pending.size() <= MAX_PENDING_BYTES - length)
            pending.write(input.data, input.position, length)
            output.sampleData(input, length, part)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            session.check()
            requireValid(size in 1..MAX_SAMPLE_BYTES && offset >= 0 && crypto == null)
            val bytes = pending.toByteArray()
            requireValid(offset <= bytes.size && size <= bytes.size - offset)
            val start = bytes.size - offset - size
            requireValid(start == 0 && timeUs != C.TIME_UNSET && flags and C.BUFFER_FLAG_ENCRYPTED == 0)
            if (type == C.TRACK_TYPE_VIDEO) requireValid(hasVcl(bytes, start, size))
            val expected = if (type == C.TRACK_TYPE_VIDEO) session.evidence.videoAccessUnitCount else session.evidence.audioFrameCount
            requireValid(samples < expected)
            samples++
            output.sampleMetadata(timeUs, flags, size, offset, crypto)
            pending.reset()
            pending.write(bytes, bytes.size - offset, offset)
        }
    }

    private fun hasVcl(bytes: ByteArray, start: Int, length: Int): Boolean {
        val end = start + length
        var at = start
        while (at + 3 < end) {
            if (bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 1.toByte()) {
                val type = bytes[at + 3].toInt() and 31
                if (type == 1 || type == 5) return true
                at += 3
            } else at++
        }
        return false
    }
    private inline fun <T> protected(block: () -> T): T = try { session.check(); block() }
        catch (failure: Throwable) { session.revoke(); throw failure }
    private fun requireValid(ok: Boolean) { if (!ok) reject() }
    private fun reject(): Nothing { session.revoke(); throw IOException("LOCAL_AVC_REJECTED") }
    private companion object {
        const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
        const val MAX_PENDING_BYTES = MAX_SAMPLE_BYTES + 64 * 1024
    }
}
