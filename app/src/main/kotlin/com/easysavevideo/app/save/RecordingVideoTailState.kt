package com.easysavevideo.app.save

import com.easysavevideo.recording.RecordingTrackEvidence
import com.easysavevideo.recording.RecordingResumeBoundary
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import kotlin.math.abs
import java.io.RandomAccessFile

/** Raw identities are produced solely by the recording verifier's complete local MP4 scan. */
internal data class RecordingTailSample(val timeUs: Long, val flags: Int, val inputBytes: Int, val sha256: String)

/** Decoder work is independently bounded even when the complete MP4 scan is large. */
internal object RecordingGopBounds {
    const val MAX_RESUMES = 32
    const val MAX_GOPS = 35
    const val MAX_FRAMES = 600
    const val MAX_GOP_BYTES = 8L * 1024 * 1024
    const val MAX_SELECTED_BYTES = 32L * 1024 * 1024
}

/** A complete sync-to-next-sync interval; a null next sync means actual extractor EOF. */
internal data class RecordingSelectedGop(
    val startSampleIndex: Long,
    val samples: List<RecordingTailSample>,
    val nextSync: RecordingTailSample?,
)

/**
 * Full-track accounting and a bounded selection of first, temporal-middle, final and resumed GOPs.
 * The rolling GOP is bounded too: an oversized GOP fails closed rather than growing memory while
 * waiting to discover whether it is the final GOP. All retained identities come from this scan.
 */
internal class RecordingSampleScan(
    private val video: Boolean,
    private val expectedSamples: Long,
    private val midpointOffsetUs: Long = 0,
    resumeBoundaries: List<RecordingResumeBoundary>? = emptyList(),
    private val checkpoint: () -> Unit = {},
) {
    private val boundaries = resumeBoundaries?.toList() ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    private val actualBoundaries = ArrayList<RecordingResumeBoundary>()
    private var nextBoundary = 0
    private var count = 0L
    private var first = -1L
    private var last = -1L
    private var syncCount = 0L
    private var totalBytes = 0L
    private var gopBytes = 0L
    private var gopStart = 0L
    private val gop = ArrayList<RecordingTailSample>()
    private val selected = ArrayList<RecordingSelectedGop>()
    private var selectedBytes = 0L
    private var middleSelected = false
    private var ended = false
    private var failed = false
    init {
        require(expectedSamples in 1..10_000_000 && midpointOffsetUs >= 0)
        if (boundaries.size > RecordingGopBounds.MAX_RESUMES) reject(SaveError.UNSUPPORTED_MEDIA)
        if ((!video && boundaries.isNotEmpty()) || boundaries.any { it.sampleIndex !in 1 until expectedSamples || it.ptsUs <= 0 } ||
            boundaries.zipWithNext().any { (a, b) -> b.sampleIndex <= a.sampleIndex || b.ptsUs <= a.ptsUs }) reject()
    }

    fun sample(sample: RecordingTailSample) {
        active()
        if (sample.timeUs < 0 || sample.timeUs <= last || sample.flags !in 0..1 ||
            sample.inputBytes !in 1..4 * 1024 * 1024 || !sample.sha256.matches(Regex("[a-f0-9]{64}")) ||
            count >= expectedSamples || (video && count == 0L && sample.flags != 1)) reject()
        if (first < 0) first = sample.timeUs
        if (video) {
            val boundary = boundaries.getOrNull(nextBoundary)
            if (boundary != null && boundary.sampleIndex == count) {
                if (sample.flags != 1 || abs(sample.timeUs - boundary.ptsUs) > 100) reject()
                actualBoundaries.add(RecordingResumeBoundary(count, sample.timeUs)); nextBoundary++
            }
            if (sample.flags == 1) {
                if (gop.isNotEmpty()) finishGop(sample)
                syncCount++; gopStart = count; gop.clear(); gopBytes = 0
            }
            gopBytes += sample.inputBytes
            if (gop.size >= RecordingGopBounds.MAX_FRAMES || gopBytes > RecordingGopBounds.MAX_GOP_BYTES)
                reject(SaveError.UNSUPPORTED_MEDIA)
            gop.add(sample)
        }
        last = sample.timeUs
        count++
        totalBytes += sample.inputBytes
        if (totalBytes > 2L * 1024 * 1024 * 1024) reject(SaveError.BYTE_LIMIT_EXCEEDED)
    }

    private fun finishGop(nextSync: RecordingTailSample?) {
        val middleOffset = midpointOffsetUs
        val coversMiddle = gop.first().timeUs - first <= middleOffset &&
            (nextSync == null || nextSync.timeUs - first > middleOffset)
        if (gopStart == 0L || coversMiddle || nextSync == null || actualBoundaries.any { it.sampleIndex == gopStart }) {
            selectedBytes += gopBytes
            if (selected.size >= RecordingGopBounds.MAX_GOPS || selectedBytes > RecordingGopBounds.MAX_SELECTED_BYTES)
                reject(SaveError.UNSUPPORTED_MEDIA)
            selected.add(RecordingSelectedGop(gopStart, gop.toList(), nextSync))
            if (coversMiddle) middleSelected = true
        }
    }

    /** Exact stts duration avoids Android's version-dependent tkhd/mdhd KEY_DURATION semantics. */
    fun finish(timing: RecordingTrackTiming): RecordingTrackEvidence {
        active()
        if (count != expectedSamples || count != timing.sampleCount || count <= 0 || nextBoundary != boundaries.size ||
            abs((last - first) - timing.spanUs) > 100 || timing.lastDurationUs !in 1..1_000_000 ||
            (video && midpointOffsetUs != timing.spanUs / 2)) reject()
        if (video) {
            finishGop(null)
            if (!middleSelected || selected.first().startSampleIndex != 0L || selected.last().nextSync != null) reject()
        }
        ended = true
        return RecordingTrackEvidence(count, first, last, timing.lastDurationUs,
            syncSampleCount = syncCount, firstSampleIsKeyFrame = video)
    }
    fun selectedGops(): List<RecordingSelectedGop> {
        if (!video || !ended || failed || selected.isEmpty()) reject()
        return selected.toList()
    }
    fun observedResumeBoundaries(): List<RecordingResumeBoundary> {
        if (!video || !ended || failed || nextBoundary != boundaries.size) reject()
        return actualBoundaries.toList()
    }
    private fun active() {
        try { checkpoint() } catch (failure: Throwable) { failed = true; throw failure }
        if (failed || ended) reject()
    }
    private fun reject(code: SaveError = SaveError.INVALID_MEDIA): Nothing { failed = true; throw SaveFailure(code) }
}

/** Every selected GOP must decode every exact access unit and drain its own actual output EOS. */
internal class RecordingVideoTailState(
    private val gop: RecordingSelectedGop,
    private val checkpoint: () -> Unit,
) {
    private val expected = gop.samples
    private var queued = 0
    private var decoded = 0
    private var inputEos = false
    private var outputEos = false
    private var failed = false
    private var lastOutput = -1L
    val complete: Boolean get() = outputEos && !failed
    val terminalOutputTimeUs: Long get() = if (complete) lastOutput else -1
    val decodedSamples: Int get() = decoded
    val queuedSamples: Int get() = queued
    val canQueueInput: Boolean get() = !failed && !inputEos && !outputEos
    init {
        if (expected.size > RecordingGopBounds.MAX_FRAMES ||
            expected.sumOf { it.inputBytes.toLong() } > RecordingGopBounds.MAX_GOP_BYTES) reject(SaveError.UNSUPPORTED_MEDIA)
        if (gop.startSampleIndex < 0 || expected.isEmpty() || expected.first().flags != 1 ||
            expected.drop(1).any { it.flags != 0 } ||
            expected.any { it.timeUs < 0 || it.inputBytes !in 1..4 * 1024 * 1024 ||
                !it.sha256.matches(Regex("[a-f0-9]{64}")) } ||
            expected.zipWithNext().any { (a, b) -> b.timeUs <= a.timeUs } ||
            gop.nextSync?.let { it.flags != 1 || it.timeUs <= expected.last().timeUs || it.inputBytes !in 1..4 * 1024 * 1024 } == true) reject()
    }
    fun input(sample: RecordingTailSample) {
        active()
        if (inputEos || queued >= expected.size || sample != expected[queued]) reject()
        queued++
    }
    /** Caller separately confirms a real extractor EOF when all three end values are -1. */
    fun inputEndOfStream(nextTimeUs: Long = -1, nextFlags: Int = -1, nextBytes: Long = -1) {
        active()
        val next = gop.nextSync
        if (inputEos || queued != expected.size ||
            (if (next == null) nextTimeUs != -1L || nextFlags != -1 || nextBytes != -1L
             else nextTimeUs != next.timeUs || nextFlags != next.flags || nextBytes != next.inputBytes.toLong())) reject()
        inputEos = true
    }
    fun output(timeUs: Long, bytes: Int, codecConfig: Boolean, endOfStream: Boolean): Boolean {
        active()
        if (bytes < 0) reject()
        var progress = false
        if (bytes > 0 && !codecConfig) {
            // These are exact extractor PTS supplied to this decoder, not mux-rounded times.
            if (decoded >= queued || decoded >= expected.size || timeUs < 0 || timeUs <= lastOutput ||
                timeUs != expected[decoded].timeUs) reject()
            decoded++; lastOutput = timeUs; progress = true
        }
        if (endOfStream) {
            if (codecConfig || !inputEos || decoded != expected.size) reject()
            outputEos = true; progress = true
        }
        return progress
    }
    private fun active() {
        try { checkpoint() } catch (failure: Throwable) { failed = true; throw failure }
        if (failed || outputEos) reject()
    }
    private fun reject(code: SaveError = SaveError.INVALID_MEDIA): Nothing { failed = true; throw SaveFailure(code) }
}

internal class RecordingDecodeProgress(startedNanos: Long, private val timeoutNanos: Long) {
    private var lastProgress = startedNanos
    init { require(timeoutNanos > 0) }
    fun madeProgress(nowNanos: Long) {
        if (nowNanos < lastProgress) throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
        lastProgress = nowNanos
    }
    fun check(nowNanos: Long) {
        if (nowNanos < lastProgress || nowNanos - lastProgress >= timeoutNanos)
            throw SaveFailure(SaveError.DEADLINE_EXCEEDED)
    }
}

internal data class RecordingDecodedVideoFormat(
    val mime: String, val width: Int, val height: Int, val cropLeft: Int, val cropRight: Int,
    val cropTop: Int, val cropBottom: Int, val color: Int,
)
internal class RecordingVideoFormatState(private val width: Int, private val height: Int) {
    private var first: RecordingDecodedVideoFormat? = null
    private var formatEvent = false
    private var outputSeen = false
    /** A fresh decoder may announce the same format again; the cross-GOP signature is retained. */
    fun beginGop() { formatEvent = false; outputSeen = false }
    fun accept(actual: RecordingDecodedVideoFormat, changeEvent: Boolean) {
        if (actual.mime != "video/raw" || actual.width !in 1..8192 || actual.height !in 1..8192 ||
            actual.width.toLong() * actual.height > 16_777_216 ||
            actual.cropLeft !in 0 until actual.width || actual.cropRight !in actual.cropLeft until actual.width ||
            actual.cropTop !in 0 until actual.height || actual.cropBottom !in actual.cropTop until actual.height ||
            actual.cropRight - actual.cropLeft + 1 != width || actual.cropBottom - actual.cropTop + 1 != height ||
            (first != null && first != actual) || (changeEvent && (formatEvent || outputSeen)))
            throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
        first = actual
        if (changeEvent) formatEvent = true else outputSeen = true
    }
}

/** Local ISO BMFF timing only, kept separate from any transport or HLS conservation contract. */
internal data class RecordingTrackTiming(
    val sampleCount: Long, val timescale: Long, val durationTicks: Long, val lastDurationTicks: Long,
) {
    init {
        require(sampleCount in 1..10_000_000 && timescale in 1..1_000_000_000)
        require(durationTicks > 0 && lastDurationTicks in 1..minOf(durationTicks, timescale))
        require(if (sampleCount == 1L) durationTicks == lastDurationTicks else durationTicks > lastDurationTicks)
        require(durationTicks / timescale <= 24L * 60 * 60)
    }
    private fun micros(ticks: Long) = ticks / timescale * 1_000_000 + ticks % timescale * 1_000_000 / timescale
    val durationUs: Long get() = micros(durationTicks)
    val spanUs: Long get() = micros(durationTicks - lastDurationTicks)
    val lastDurationUs: Long get() = micros(lastDurationTicks)
}

/**
 * Original bounded read-only atom walk. MediaExtractor exposes no Java sample duration, and
 * KEY_DURATION has changed from media-relative to presentation-relative across Android versions.
 * Reading stts prevents a leading audio edit or metadata rounding from extending the final sample.
 */
internal object RecordingMp4TimingProbe {
    private data class Atom(val type: String, val payload: Long, val end: Long)
    fun read(input: RandomAccessFile, checkpoint: () -> Unit): Map<String, RecordingTrackTiming> {
        var boxes = 0
        fun invalid(): Nothing = throw SaveFailure(SaveError.INVALID_MEDIA)
        fun children(start: Long, end: Long): List<Atom> {
            val result = ArrayList<Atom>()
            var at = start
            while (at < end) {
                checkpoint()
                if (++boxes > 4096 || end - at < 8) invalid()
                input.seek(at)
                val size32 = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).toString(Charsets.ISO_8859_1)
                val header = if (size32 == 1L) 16L else 8L
                if (end - at < header) invalid()
                val size = when (size32) { 0L -> end - at; 1L -> input.readLong(); else -> size32 }
                if (size < header || size > end - at) invalid()
                result.add(Atom(type, at + header, at + size)); at += size
            }
            return result
        }
        fun only(parent: Atom, type: String): Atom = children(parent.payload, parent.end).filter { it.type == type }
            .singleOrNull() ?: invalid()
        fun uint(at: Long, atom: Atom): Long {
            if (at < atom.payload || atom.end - at < 4) invalid()
            input.seek(at); return input.readInt().toLong() and 0xffffffffL
        }
        val moov = children(0, input.length()).filter { it.type == "moov" }.singleOrNull() ?: invalid()
        val tracks = children(moov.payload, moov.end).filter { it.type == "trak" }
        if (tracks.size !in 1..2) invalid()
        val result = linkedMapOf<String, RecordingTrackTiming>()
        for (track in tracks) {
            checkpoint()
            val mdia = only(track, "mdia")
            val handler = only(mdia, "hdlr")
            if (handler.end - handler.payload < 12) invalid()
            input.seek(handler.payload + 8)
            val kind = ByteArray(4).also(input::readFully).toString(Charsets.ISO_8859_1)
            if (kind !in setOf("vide", "soun") || result.containsKey(kind)) invalid()
            val mdhd = only(mdia, "mdhd")
            val versionAndFlags = uint(mdhd.payload, mdhd)
            val version = versionAndFlags ushr 24
            if (versionAndFlags and 0xffffffL != 0L || version !in 0L..1L) invalid()
            val scaleAt = mdhd.payload + if (version == 1L) 20 else 12
            val scale = uint(scaleAt, mdhd)
            if (scale !in 1..1_000_000_000L) invalid()
            val declaredTicks = if (version == 0L) uint(scaleAt + 4, mdhd) else {
                if (mdhd.end - (scaleAt + 4) < 8) invalid()
                input.seek(scaleAt + 4); input.readLong().also { if (it <= 0) invalid() }
            }
            val stts = only(only(only(mdia, "minf"), "stbl"), "stts")
            if (uint(stts.payload, stts) != 0L) invalid()
            val entries = uint(stts.payload + 4, stts)
            if (entries !in 1..10_000_000L || stts.end - stts.payload != 8 + entries * 8) invalid()
            var count = 0L
            var ticks = 0L
            var lastDelta = 0L
            repeat(entries.toInt()) { index ->
                checkpoint()
                val at = stts.payload + 8 + index.toLong() * 8
                val run = uint(at, stts); val delta = uint(at + 4, stts)
                if (run <= 0 || run > 10_000_000 - count || delta <= 0) invalid()
                val addition = run * delta
                if (ticks > Long.MAX_VALUE - addition) invalid()
                count += run; ticks += addition; lastDelta = delta
            }
            if (ticks <= 0 || ticks / scale > 24L * 60 * 60 || lastDelta > scale ||
                (if (count == 1L) ticks != lastDelta else ticks <= lastDelta) || declaredTicks <= 0 || abs(ticks - declaredTicks) > 1) invalid()
            result[kind] = RecordingTrackTiming(count, scale, ticks, lastDelta)
        }
        if (!result.containsKey("vide")) invalid()
        return result
    }
}
