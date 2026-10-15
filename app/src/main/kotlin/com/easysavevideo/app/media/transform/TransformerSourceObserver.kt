package com.easysavevideo.app.media.transform

import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.container.OpusUtil
import androidx.media3.decoder.DecoderInputBuffer
import com.easysavevideo.app.save.conversion.WebmTransportFacts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.transformer.Codec
import androidx.media3.transformer.SampleConsumer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal data class TransformerSourceTimeline(val durationUs: Long, val frameRate: Int, val demuxedVideoStartUs: Long) {
    init { require(durationUs in 1..1_800_000_000L && frameRate in 1..60 && demuxedVideoStartUs in -1000..1000) }
}
internal data class TransformerVideoObservation(val displayedFrames: Long, val firstLocalPtsUs: Long,
    val lastLocalPtsUs: Long, val sourceDurationUs: Long, val frameRate: Int, val maximumFrameDeviationUs: Long)
internal data class TransformerAudioObservation(val sourceFirstLocalPtsUs: Long, val sourceEndLocalPtsUs: Long,
    val sourcePcmFrames: Long, val encoderPcmFrames: Long, val sampleRate: Int, val channels: Int,
    val codecDelayUs: Long = 0, val seekPrerollUs: Long = 0, val discardPaddingUs: Long = 0,
    val headMapping: TransformerOpusHeadMapping = TransformerOpusHeadMapping.UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN)
/** Technical observations only. Native fixtures must still establish the Opus audible-boundary mapping. */
internal data class TransformerSourceObservation(val video: TransformerVideoObservation, val audio: TransformerAudioObservation?)

/**
 * Actual single-source hooks. Technical facts cannot create storage/publication or native mapping
 * acceptance. Opus retains at most 120 ms PCM plus one bounded input and the downstream pending
 * buffer; native output lifetimes and compressed payloads are never retained.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class TransformerSourceObserver(
    private val timeline: TransformerSourceTimeline,
    private val audio: TransformerAudioFormat?,
    private val opusSource: TransformerOpusSourceSpec? = null,
    private val checkpoint: () -> Unit,
) {
    constructor(timeline: TransformerSourceTimeline, audio: TransformerAudioFormat?, checkpoint: () -> Unit) :
        this(timeline, audio, null, checkpoint)
    private val unavailable = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private var videoBound = false
    private var decoderAudioBound = false
    private var encoderAudioBound = false
    private var sourceConsumer: SampleConsumer? = null
    private var wrappedConsumer: SampleConsumer? = null
    private val videoTerminal = Terminal()
    private val audioDecoderTerminal = Terminal()
    private val audioEncoderTerminal = Terminal()
    private val video = VideoLedger()
    private val opus = OpusLedger()
    private var adapter: SourcePcmAdapter? = null
    private var encoderFrames = 0L
    private var appendedSilenceFrames = 0L
    private var encoderEos = false
    // Same-thread reentrancy may observe an acceptance while another thread is serialized here.
    private var sourceFramesInFlight = 0L
    init { require(audio == null || (audio.sampleRate == 48_000 && audio.channels in 1..2)) }

    private fun active() {
        try { checkpoint() } catch (failure: Throwable) { unavailable.set(true); throw failure }
        if (unavailable.get() || finished.get()) reject()
    }
    private fun reject(): Nothing { unavailable.set(true); throw TransformerFailure(TransformerError.UNSUPPORTED_PROFILE) }
    private inline fun <T> accepted(block: () -> T): T = try { block() } catch (failure: Throwable) { unavailable.set(true); throw failure }

    @Synchronized fun videoDecoder(codec: Codec): Codec {
        active(); if (videoBound) reject(); videoBound = true
        return object : Codec by codec {
            private var serial = 0L
            private var pending: PendingVideo? = null
            override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) = synchronized(this@TransformerSourceObserver) {
                active(); val eos = decoderInputEos(inputBuffer)
                videoTerminal.beforeInput()
                accepted { codec.queueInputBuffer(inputBuffer) }
                videoTerminal.acceptedInput(eos)
            }
            override fun getOutputFormat(): Format? = synchronized(this@TransformerSourceObserver) {
                active(); return@synchronized accepted { codec.outputFormat } // May dequeue; never counts a frame.
            }
            override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = synchronized(this@TransformerSourceObserver) {
                active()
                val info = accepted { codec.outputBufferInfo } ?: run { if (pending != null) reject(); return@synchronized null }
                val copy = BufferTuple(info.presentationTimeUs, info.offset, info.size, info.flags)
                if (copy.pts == C.TIME_UNSET || copy.pts == C.TIME_END_OF_SOURCE || copy.offset < 0 || copy.size < 0 || copy.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) reject()
                val held = pending
                if (held == null) {
                    if (serial == Long.MAX_VALUE) reject()
                    pending = PendingVideo(++serial, copy)
                } else if (held.tuple != copy || held.attempted) reject()
                return@synchronized info // Return the delegate's exact object; never keep its mutable fields.
            }
            override fun releaseOutputBuffer(renderPresentationTimeUs: Long) = synchronized(this@TransformerSourceObserver) {
                active(); val held = pending ?: reject()
                if (held.attempted) reject()
                video.validate(renderPresentationTimeUs)
                held.attempted = true
                accepted { codec.releaseOutputBuffer(renderPresentationTimeUs) }
                video.commit(renderPresentationTimeUs)
                pending = null
            }
            override fun releaseOutputBuffer(render: Boolean) = synchronized(this@TransformerSourceObserver) {
                active()
                val held = pending
                if (render) {
                    // Unexpected overload cannot mint local-time evidence, but still releases once.
                    unavailable.set(true)
                    if (held?.attempted == true) reject()
                    if (held != null) held.attempted = true
                    accepted { codec.releaseOutputBuffer(true) }
                    pending = null
                    reject()
                }
                if (held == null || held.attempted) reject()
                held.attempted = true
                accepted { codec.releaseOutputBuffer(false) }
                pending = null // Discard contributes no displayed observation.
            }
            override fun isEnded(): Boolean = synchronized(this@TransformerSourceObserver) {
                active(); val ended = accepted { codec.isEnded }
                if (ended) { if (pending != null) reject(); videoTerminal.outputEnded() }
                return@synchronized ended
            }
        }
    }

    @Synchronized fun audioDecoder(codec: Codec, originalFormat: Format): Codec {
        active()
        val spec = opusSource ?: reject()
        if (audio == null || decoderAudioBound || spec.channels != audio.channels || !spec.matches(originalFormat)) reject()
        if (!spec.matches(accepted { codec.configurationFormat })) reject()
        decoderAudioBound = true
        return object : Codec by codec {
            override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) = synchronized(this@TransformerSourceObserver) {
                active(); val eos = decoderInputEos(inputBuffer)
                audioDecoderTerminal.beforeInput()
                val packet = if (eos) null else opus.snapshot(inputBuffer)
                if (eos && (inputBuffer.hasSupplementalData() || opus.packets == 0L)) reject()
                accepted { codec.queueInputBuffer(inputBuffer) }
                if (packet != null) opus.commit(packet)
                audioDecoderTerminal.acceptedInput(eos)
            }
            override fun getOutputFormat(): Format? = synchronized(this@TransformerSourceObserver) {
                active(); accepted { codec.outputFormat }?.also { checkPcm(it) }
            }
            override fun getOutputBuffer(): ByteBuffer? = synchronized(this@TransformerSourceObserver) {
                active()
                accepted { codec.outputBuffer }?.also {
                    // Renderer calls ensureSpaceForWrite(limit), BEFORE sourcePcm.queueInputBuffer.
                    val frameBytes = 2 * spec.channels
                    if (it.limit() > MAX_OPUS_TAIL_FRAMES * frameBytes || !it.hasRemaining() ||
                        it.position() % frameBytes != 0 || it.remaining() % frameBytes != 0) reject()
                }
            }
            override fun isEnded(): Boolean = synchronized(this@TransformerSourceObserver) {
                active(); val ended = accepted { codec.isEnded }
                if (ended) audioDecoderTerminal.outputEnded()
                ended
            }
            override fun release() {
                // Pinned ExoAssetLoaderBaseRenderer releases onReset, on its stopped input thread.
                // An exception or a foreign thread proves no safe return of borrowed PCM.
                try { codec.release() }
                catch (failure: Throwable) { unavailable.set(true); throw failure }
                synchronized(this@TransformerSourceObserver) {
                    if (adapter?.downstreamEosAccepted != true) unavailable.set(true)
                    adapter?.afterDecoderReleaseReturned()
                }
            }
        }
    }

    /** Before AudioGraphInput and its silence appender; never an effects audio processor. */
    @Synchronized fun sourcePcm(format: Format, consumer: SampleConsumer?): SampleConsumer? {
        active(); checkPcm(format)
        if (opusSource == null) reject()
        if (consumer == null) return null
        if (sourceConsumer != null) {
            if (sourceConsumer !== consumer) reject()
            return wrappedConsumer
        }
        sourceConsumer = consumer
        return SourcePcmAdapter(consumer).also { adapter = it; wrappedConsumer = it }
    }

    /** Only actual accepted AAC encoder input counts E; zero suffixes count A separately. */
    @Synchronized fun audioEncoder(codec: Codec): Codec {
        active(); if (audio == null || opusSource == null || encoderAudioBound) reject(); encoderAudioBound = true
        return object : Codec by codec {
            private var formatChecked = false
            override fun getInputFormat(): Format = synchronized(this@TransformerSourceObserver) {
                active(); val format = accepted { codec.inputFormat }
                checkPcm(format); formatChecked = true
                format
            }
            override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) = synchronized(this@TransformerSourceObserver) {
                active(); if (!formatChecked || encoderEos) reject()
                val snapshot = pcmTuple(inputBuffer, 4 * 1024 * 1024)
                val source = adapter ?: reject()
                audioEncoderTerminal.beforeInput()
                if (snapshot.frames.toLong() > MAX_OPUS_PCM_FRAMES - encoderFrames) reject()
                var silence = 0L
                if (snapshot.eos) {
                    if (!source.sourceEnded || sourceFramesInFlight != 0L) reject()
                } else {
                    // The pinned AudioSampleExporter uses this exact zero-based floor clock.
                    if (snapshot.pts != opusTimeUs(encoderFrames)) reject()
                    val end = encoderFrames + snapshot.frames
                    if (!source.sourceEnded) {
                        if (end > source.acceptedFrames + sourceFramesInFlight) reject()
                    } else if (end > source.acceptedFrames) {
                        val skipFrames = maxOf(0L, source.acceptedFrames - encoderFrames).toInt()
                        val data = inputBuffer.data ?: reject()
                        val firstZeroByte = data.position() + skipFrames * 2 * audio.channels
                        for (i in firstZeroByte until data.limit()) if (data.get(i).toInt() != 0) reject()
                        silence = snapshot.frames - skipFrames.toLong()
                    }
                }
                accepted { codec.queueInputBuffer(inputBuffer) }
                encoderFrames += snapshot.frames
                appendedSilenceFrames += silence
                if (snapshot.eos) encoderEos = true
                audioEncoderTerminal.acceptedInput(snapshot.eos)
            }
            override fun isEnded(): Boolean = synchronized(this@TransformerSourceObserver) {
                active(); val ended = accepted { codec.isEnded }
                if (ended) audioEncoderTerminal.outputEnded()
                ended
            }
        }
    }

    /** The registered owner supplies its actual completed loaded transport, never preflight alone. */
    @Synchronized fun finish(completedTransport: WebmTransportFacts? = null): TransformerSourceObservation {
        active()
        if (!finished.compareAndSet(false, true)) reject()
        if (!videoBound || !videoTerminal.complete()) reject()
        val displayed = video.result()
        val heard = if (audio == null) {
            if (opusSource != null || decoderAudioBound || encoderAudioBound || sourceConsumer != null) reject()
            null
        } else {
            val spec = opusSource ?: reject()
            val loaded = completedTransport ?: reject()
            val loadedAudio = loaded.tracks.singleOrNull { it.trackType == C.TRACK_TYPE_AUDIO } ?: reject()
            val source = adapter ?: reject()
            if (!spec.matchesCompleted(loaded) || !decoderAudioBound || !encoderAudioBound || sourceConsumer == null ||
                !audioDecoderTerminal.complete() || !audioEncoderTerminal.complete() || !source.downstreamEosAccepted ||
                !source.sourceEnded || !encoderEos || sourceFramesInFlight != 0L ||
                loadedAudio.sourceSampleCallbacks != opus.packets || loadedAudio.firstSourceTimestampUs != opus.firstPts ||
                loadedAudio.lastSourceTimestampUs != opus.lastPts || loadedAudio.supplementalBytes != opus.supplementalBytes ||
                source.acceptedFrames !in 1..MAX_OPUS_PCM_FRAMES || source.acceptedFrames > encoderFrames ||
                encoderFrames != source.acceptedFrames + appendedSilenceFrames) reject()
            TransformerAudioObservation(spec.sourceOriginUs, spec.sourceOriginUs + opusTimeUs(source.acceptedFrames),
                source.acceptedFrames, encoderFrames, OPUS_RATE, audio.channels,
                spec.codecDelayUs, spec.seekPrerollUs, opus.paddingNs / 1000, spec.headMapping)
        }
        adapter?.clear()
        return TransformerSourceObservation(displayed, heard)
    }

    private inner class OpusLedger {
        var durationFrames = 0L; private set
        var packets = 0L; private set
        var firstPts: Long? = null; private set
        var lastPts: Long? = null; private set
        var paddingFrames = 0L; private set
        var paddingNs = 0L; private set
        var supplementalBytes = 0L; private set
        private var paddingSeen = false
        fun snapshot(buffer: DecoderInputBuffer): PacketTuple {
            val spec = opusSource ?: reject()
            val data = buffer.data ?: reject()
            if (paddingSeen || packets >= MAX_OPUS_PACKETS || !data.hasRemaining()) reject()
            // OpusUtil uses absolute indexes 0/1. A duplicate alone is incorrect for nonzero position.
            val payload = data.asReadOnlyBuffer().slice()
            if ((payload.get(0).toInt() and 3) == 3 && payload.remaining() < 2) reject()
            val frames = OpusUtil.parsePacketAudioSampleCount(payload)
            if (frames !in 1..MAX_OPUS_TAIL_FRAMES ||
                frames.toLong() > MAX_OPUS_PCM_FRAMES + 2 * MAX_OPUS_TAIL_FRAMES - durationFrames ||
                buffer.timeUs !in 0..1_800_001_000L ||
                abs(buffer.timeUs - spec.compressedOriginUs - opusTimeUs(durationFrames)) > spec.timestampToleranceUs ||
                (lastPts != null && buffer.timeUs <= lastPts!!)) reject()
            var tailNs: Long? = null
            var supplementBytes = 0
            if (buffer.hasSupplementalData()) {
                val supplement = buffer.supplementalData ?: reject()
                val bytes = supplement.remaining()
                if (bytes != 0 && bytes != 8) reject()
                supplementBytes = 4 + bytes // Loaded TrackOutput includes the removed size prefix.
                if (bytes == 8) {
                    tailNs = supplement.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN).long
                    try { opusExactFrames(tailNs) } catch (_: TransformerFailure) { reject() }
                }
            }
            return PacketTuple(buffer.timeUs, frames, tailNs, supplementBytes)
        }
        fun commit(packet: PacketTuple) {
            durationFrames += packet.frames; packets++
            if (firstPts == null) firstPts = packet.pts
            lastPts = packet.pts; supplementalBytes += packet.supplementBytes
            packet.paddingNs?.let { paddingSeen = true; paddingNs = it; paddingFrames = opusExactFrames(it) }
        }
    }

    private inner class SourcePcmAdapter(private val downstream: SampleConsumer) : SampleConsumer by downstream {
        private val frameBytes = 2 * requireNotNull(audio).channels
        private val maxInputBytes = MAX_OPUS_TAIL_FRAMES * frameBytes
        private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED).apply {
            data = ByteBuffer.allocateDirect(maxInputBytes).order(ByteOrder.nativeOrder())
        }
        private val inputStorage = requireNotNull(input.data)
        private val tail = TransformerPcmTail(frameBytes)
        private var inputIssued = false
        private var inputThread: Thread? = null
        private var queueInProgress = false
        private var upstream: PcmTuple? = null
        private var pending: DownstreamPcm? = null
        private var tailResolved = false
        var rawFrames = 0L; private set
        var acceptedFrames = 0L; private set
        var sourceEnded = false; private set
        var downstreamEosAccepted = false; private set
        override fun getInputBuffer(): DecoderInputBuffer = synchronized(this@TransformerSourceObserver) {
            active(); if (downstreamEosAccepted || queueInProgress) reject()
            val thread = Thread.currentThread()
            if (inputThread != null && inputThread !== thread) reject()
            if (inputThread == null) inputThread = thread
            inputIssued = true
            input
        }
        override fun queueInputBuffer(): Boolean = synchronized(this@TransformerSourceObserver) {
            active()
            if (inputThread !== Thread.currentThread() || queueInProgress || !inputIssued || downstreamEosAccepted || input.data !== inputStorage) reject()
            queueInProgress = true
            try {
                val tuple = pcmTuple(input, maxInputBytes)
                val previous = upstream
                if (previous != null && previous != tuple) reject()
                if (previous == null) {
                    if (sourceEnded) reject()
                    upstream = tuple
                    if (!tuple.eos) {
                        val spec = opusSource ?: reject()
                        if (!decoderAudioBound || rawFrames + tuple.frames > opus.durationFrames - spec.headFrames ||
                            rawFrames + tuple.frames > MAX_OPUS_PCM_FRAMES + MAX_OPUS_TAIL_FRAMES ||
                            tuple.pts !in 0..1_800_121_000L ||
                            abs(tuple.pts - spec.nativePcmOriginUs - opusTimeUs(rawFrames)) > spec.timestampToleranceUs) reject()
                    }
                }
                if (tuple.eos) return@synchronized queueSourceEos()
                if (tailResolved) reject()
                val overflow = maxOf(0, tail.frames + tuple.frames - MAX_OPUS_TAIL_FRAMES)
                if (overflow > 0 && !forward(overflow, false, includeInput = true)) return@synchronized false
                val oldRemoved = minOf(overflow, tail.frames)
                tail.removeHead(oldRemoved)
                tail.append(inputStorage, overflow - oldRemoved)
                rawFrames += tuple.frames
                acceptUpstream()
                true
            } finally { queueInProgress = false }
        }
        private fun queueSourceEos(): Boolean {
            if (!tailResolved) {
                if (!audioDecoderTerminal.complete()) reject()
                val spec = opusSource ?: reject()
                val beforeTail = opus.durationFrames - spec.headFrames
                val effective = beforeTail - opus.paddingFrames
                if (effective !in 1..MAX_OPUS_PCM_FRAMES) reject()
                val drop = when (rawFrames) {
                    beforeTail -> opus.paddingFrames.toInt()
                    effective -> 0
                    else -> reject()
                }
                if (drop > tail.frames || acceptedFrames + tail.frames - drop != effective) reject()
                tail.removeTail(drop); tailResolved = true
            }
            if (tail.frames > 0) {
                val frames = tail.frames
                if (!forward(frames, false, includeInput = false)) return false
                tail.removeHead(frames)
            }
            // These final source facts MUST be visible before graph/encoder EOS reentrancy.
            sourceEnded = true
            if (!forward(0, true, includeInput = false)) return false
            downstreamEosAccepted = true
            acceptUpstream()
            return true
        }
        private fun forward(frames: Int, eos: Boolean, includeInput: Boolean): Boolean {
            var held = pending
            if (held == null) {
                val buffer = accepted { downstream.inputBuffer } ?: return false
                buffer.clear()
                buffer.ensureSpaceForWrite(frames * frameBytes)
                val data = buffer.data ?: reject()
                if (data.position() != 0 || data.capacity() < frames * frameBytes) reject()
                for (i in 0 until frames * frameBytes) {
                    val value = if (i < tail.frames * frameBytes) tail.byteAt(i)
                        else if (includeInput) inputStorage.get(inputStorage.position() + i - tail.frames * frameBytes) else reject()
                    data.put(value)
                }
                data.flip(); buffer.timeUs = opusTimeUs(acceptedFrames)
                if (eos) buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                held = DownstreamPcm(buffer, pcmTuple(buffer, maxInputBytes), frames, eos)
                pending = held
            } else {
                if (held.frames != frames || held.eos != eos || pcmTuple(held.buffer, maxInputBytes) != held.tuple) reject()
                // Compare exact bounded retained bytes on every false retry; no payload copy/hash.
                val data = held.buffer.data ?: reject()
                for (i in 0 until frames * frameBytes) {
                    val expected = if (i < tail.frames * frameBytes) tail.byteAt(i)
                        else if (includeInput) inputStorage.get(inputStorage.position() + i - tail.frames * frameBytes) else reject()
                    if (data.get(data.position() + i) != expected) reject()
                }
            }
            if (sourceFramesInFlight != 0L) reject()
            sourceFramesInFlight = frames.toLong()
            held.acceptanceUnknown = true
            val queued = try { accepted { downstream.queueInputBuffer() } }
                finally { sourceFramesInFlight = 0L }
            held.acceptanceUnknown = false
            if (queued) {
                acceptedFrames += frames
                pending = null
            } else if (!sourceEnded && encoderFrames > acceptedFrames) reject() // False may not expose NEW source PCM.
            return queued
        }
        private fun acceptUpstream() {
            input.clear(); input.data = inputStorage; inputStorage.clear()
            inputIssued = false; upstream = null
        }
        fun afterDecoderReleaseReturned() {
            if (downstreamEosAccepted || (!queueInProgress && (inputThread == null || inputThread === Thread.currentThread()))) clear()
            // Otherwise the stopped-renderer premise is unknown; keep the exact run references.
        }
        fun clear() = synchronized(this@TransformerSourceObserver) {
            tail.clear()
            val wipe = inputStorage.duplicate().apply { clear() }
            for (i in 0 until wipe.limit()) wipe.put(i, 0)
            inputStorage.clear()
            // A delegate may publish then throw. Never wipe its carrier after a queue attempt.
            // Keep an uncertain reference with this existing run until actual session cleanup.
            if (pending?.acceptanceUnknown != true) pending = null
            upstream = null; inputIssued = false
        }
    }

    private fun decoderInputEos(buffer: DecoderInputBuffer): Boolean {
        if (buffer.isEncrypted || (buffer.isEndOfStream && buffer.data?.hasRemaining() == true)) reject()
        return buffer.isEndOfStream
    }
    private fun checkPcm(format: Format) {
        val expected = audio ?: reject()
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.pcmEncoding != C.ENCODING_PCM_16BIT ||
            format.sampleRate != 48_000 || format.channelCount != expected.channels) reject()
    }
    private fun pcmTuple(buffer: DecoderInputBuffer, maximumBytes: Int): PcmTuple {
        val expected = audio ?: reject()
        val bytes = buffer.data?.remaining() ?: if (buffer.isEndOfStream) 0 else reject()
        if (buffer.isEncrypted || buffer.hasSupplementalData() || bytes < 0 || bytes > maximumBytes || bytes % (2 * expected.channels) != 0 ||
            (buffer.isEndOfStream && bytes != 0) || (!buffer.isEndOfStream && bytes == 0)) reject()
        return PcmTuple(buffer.timeUs, bytes / (2 * expected.channels), buffer.isEndOfStream, buffer.data?.position() ?: 0, buffer.data?.limit() ?: 0)
    }
    private data class BufferTuple(val pts: Long, val offset: Int, val size: Int, val flags: Int)
    private data class PendingVideo(val token: Long, val tuple: BufferTuple, var attempted: Boolean = false)
    private data class PcmTuple(val pts: Long, val frames: Int, val eos: Boolean, val position: Int, val limit: Int)
    private data class PacketTuple(val pts: Long, val frames: Int, val paddingNs: Long?, val supplementBytes: Int)
    private data class DownstreamPcm(val buffer: DecoderInputBuffer, val tuple: PcmTuple, val frames: Int, val eos: Boolean, var acceptanceUnknown: Boolean = false)
    private inner class Terminal {
        private var inputEos = false
        private var ended = false
        fun beforeInput() { if (inputEos || ended) reject() }
        fun acceptedInput(eos: Boolean) { if (eos) inputEos = true }
        fun outputEnded() { if (!inputEos) reject(); ended = true }
        fun complete(): Boolean = inputEos && ended
    }
    private inner class VideoLedger {
        private var count = 0L
        private var first = 0L
        private var last = 0L
        private var maximumDeviation = 0L
        fun validate(pts: Long) {
            if (count >= 108_000 || pts < 0 || pts > timeline.durationUs + 1_001_000L ||
                abs(pts - count * 1_000_000L / timeline.frameRate) > 1000 ||
                (count > 0 && (pts <= last || abs(pts - last - 1_000_000L / timeline.frameRate) > 1000))) reject()
        }
        fun commit(pts: Long) {
            maximumDeviation = maxOf(maximumDeviation, abs(pts - count * 1_000_000L / timeline.frameRate))
            if (count == 0L) first = pts; last = pts; count++
        }
        fun result(): TransformerVideoObservation {
            val allowance = 1_000_000L / timeline.frameRate + 1000
            if (count !in 3..108_000 || abs(first) > 1000 ||
                abs(count * 1_000_000L / timeline.frameRate - timeline.durationUs) > allowance ||
                abs(last + 1_000_000L / timeline.frameRate - timeline.durationUs) > allowance) reject()
            return TransformerVideoObservation(count, first, last, timeline.durationUs, timeline.frameRate, maximumDeviation)
        }
    }
}
