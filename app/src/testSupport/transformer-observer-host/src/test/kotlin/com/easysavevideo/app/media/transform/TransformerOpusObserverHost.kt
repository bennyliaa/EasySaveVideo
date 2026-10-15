package com.easysavevideo.app.media.transform

import android.media.MediaCodec
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.SampleConsumer
import com.easysavevideo.app.save.conversion.WebmTrackTransport
import com.easysavevideo.app.save.conversion.WebmTransportFacts
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual observer + pinned Media3 carriers/TOC utility; deterministic delegates, never native proof. */
internal object TransformerOpusObserverHost {
    private var passed = 0
    fun run() {
        for (channels in 1..2) for (nativeTrimmed in listOf(false, true)) {
            test("finite native tail branch channels=$channels trimmed=$nativeTrimmed") {
                val f = Fixture(51, 312, 648, channels)
                f.queuePackets(nonzeroPosition = true, quantized = true)
                val raw = if (nativeTrimmed) f.effective else f.duration - f.head
                f.source(raw, listOf(1, 479, 5760, 17, 1921))
                f.finishSource(); val result = f.finish()
                check(result.sourcePcmFrames == 48_000L && result.encoderPcmFrames == 48_000L)
                check(result.sourceEndLocalPtsUs == 1_000_000L)
                check(f.graph.accepted.toByteArray().contentEquals(pcmBytes(0, 48_000, channels)))
                check(result.headMapping == TransformerOpusHeadMapping.UNVERIFIED_HEAD_REMOVED_ONCE_ZERO_PCM_ORIGIN)
            }
        }
        for ((packets, head, tail) in listOf(Triple(51, 312, 312), Triple(3, 312, 0), Triple(8, 0, 5760), Triple(2, 312, 312))) {
            for (nativeTrimmed in listOf(false, true)) test("H/T finite boundary $head/$tail packets=$packets trimmed=$nativeTrimmed") {
                val f = Fixture(packets, head, tail)
                f.queuePackets(); f.source(if (nativeTrimmed) f.effective else f.duration - head, listOf(41, 5760, 1))
                f.finishSource(); check(f.finish().sourcePcmFrames == f.effective.toLong())
                check(f.graph.accepted.toByteArray().contentEquals(pcmBytes(0, f.effective, 1)))
            }
        }
        test("noninteger millisecond tail keeps exact frame math") {
            val f = Fixture(1, 0, 3); f.queuePackets(); f.source(960); f.finishSource()
            val result = f.finish(); check(result.sourcePcmFrames == 957L && result.discardPaddingUs == 62L)
        }
        test("short source endpoint and observed zero suffix are independent") {
            val f = Fixture(30, 0, 0, silence = 19_200)
            f.queuePackets(); f.source(28_800); f.finishSource(); val result = f.finish()
            check(result.sourcePcmFrames == 28_800L && result.encoderPcmFrames == 48_000L && result.sourceEndLocalPtsUs == 600_000L)
        }
        test("encoder suffix is inspected, not inferred from duration") {
            val f = Fixture(1, 0, 0, silence = 12); f.graph.nonzeroSilence = true
            f.queuePackets(); f.source(960); rejected { f.finishSource() }
        }
        test("null and false downstream preserve exact input and commit once") {
            val f = Fixture(9, 0, 312); f.queuePackets(); f.source(5760)
            val input = f.preparePcm(5760, 1001)
            check(input === f.sourceConsumer.inputBuffer)
            f.graph.available = false; check(!f.sourceConsumer.queueInputBuffer()); check(!f.sourceConsumer.queueInputBuffer())
            f.graph.available = true; f.graph.falseRemaining = 2
            check(!f.sourceConsumer.queueInputBuffer()); check(!f.sourceConsumer.queueInputBuffer())
            check(f.sourceConsumer.queueInputBuffer())
            f.sourceFrom(6761, f.duration - 6761); f.finishSource(); check(f.finish().sourcePcmFrames == f.effective.toLong())
            check(f.graph.accepted.toByteArray().contentEquals(pcmBytes(0, f.effective, 1)))
        }
        test("false EOS retries do not redrain or double trim") {
            val f = Fixture(2, 312, 312); f.queuePackets(); f.source(f.duration - f.head)
            f.graph.falseEosRemaining = 2; f.finishSource(); check(f.finish().sourcePcmFrames == f.effective.toLong())
            check(f.graph.eosCalls == 3 && f.graph.accepted.size() == f.effective * 2)
        }
        test("same-thread graph acceptance and EOS encoder reentrancy are supported") {
            val f = Fixture(8, 0, 312, silence = 13)
            f.queuePackets(); f.source(f.duration); f.finishSource(); val result = f.finish()
            check(result.encoderPcmFrames == result.sourcePcmFrames + 13)
        }
        test("cross-thread encoder acceptance cannot race uncommitted S") {
            val f = Fixture(8, 0, 0)
            f.graph.crossThread = true
            f.queuePackets(); f.source(f.duration); f.graph.crossThread = false; f.finishSource(); check(f.finish().encoderPcmFrames == f.duration.toLong())
        }
        test("PCM clock cannot be reset by first decoder output") {
            val f = Fixture(2, 0, 0); f.queuePackets(); f.preparePcm(0, 960).timeUs = 20_000
            rejected { f.sourceConsumer.queueInputBuffer() }
        }
        test("compressed real gap cannot be hidden by a contiguous PCM clock") {
            val f = Fixture(2, 0, 0); f.decoder.queueInputBuffer(packet(0)); rejected { f.decoder.queueInputBuffer(packet(120_000)) }
        }
        test("missing first packet cannot pass completed loaded callback count") {
            val f = Fixture(3, 0, 0)
            f.decoder.queueInputBuffer(packet(0)); f.decoder.queueInputBuffer(packet(20_000))
            f.endDecoder(); f.source(1920); f.finishSource(); rejected { f.finish() }
        }
        test("actual completed transport is mandatory and exact") {
            val f = Fixture(1, 0, 0); f.queuePackets(); f.source(960); f.finishSource(); f.finishVideo()
            rejected { f.observer.finish() }
            val g = Fixture(1, 0, 0); g.queuePackets(); g.source(960); g.finishSource(); g.finishVideo()
            rejected { g.observer.finish(g.facts(callbacks = 2)) }
        }
        test("EOS requires observed native ended, finite N and positive S") {
            val f = Fixture(1, 0, 0); f.decoder.queueInputBuffer(packet(0)); f.source(960)
            rejected { f.finishSource() }
            val g = Fixture(2, 312, 312); g.queuePackets(); g.source(g.effective - 1); rejected { g.finishSource() }
        }
        test("padding followed by another AU and repeated terminal padding reject") {
            val f = Fixture(2, 0, 3); f.decoder.queueInputBuffer(packet(0, paddingNs = 62_500))
            rejected { f.decoder.queueInputBuffer(packet(20_000)) }
            val g = Fixture(2, 0, 3); g.decoder.queueInputBuffer(packet(0, paddingNs = 62_500))
            rejected { g.decoder.queueInputBuffer(packet(20_000, paddingNs = 62_500)) }
        }
        for (ns in listOf(-1L, 1L, 120_000_001L)) test("invalid padding ns=$ns rejects") {
            val f = Fixture(1, 0, 0); rejected { f.decoder.queueInputBuffer(packet(0, paddingNs = ns)) }
        }
        test("truncated padding rejects; empty supplemental body is absent padding") {
            val f = Fixture(1, 0, 0); val bad = packet(0, paddingNs = 0); requireNotNull(bad.supplementalData).limit(7)
            rejected { f.decoder.queueInputBuffer(bad) }
            val g = Fixture(1, 0, 0, emptySupplement = true); g.queuePackets(); g.source(960); g.finishSource(); g.finish()
        }
        for (toc in listOf(byteArrayOf(), byteArrayOf(0xfb.toByte()), byteArrayOf(0xfb.toByte(), 0), byteArrayOf(0xfb.toByte(), 63))) {
            test("TOC utility duration admission length=${toc.size} count=${toc.lastOrNull()}") {
                val f = Fixture(1, 0, 0); val b = packet(0); b.data = ByteBuffer.wrap(toc)
                rejected { f.decoder.queueInputBuffer(b) }
            }
        }
        test("throwing native queue cannot count or retry") {
            val f = Fixture(1, 0, 0); f.nativeDecoder.throwQueue = true
            rejected { f.decoder.queueInputBuffer(packet(0)) }; rejected { f.decoder.queueInputBuffer(packet(0)) }
            check(f.nativeDecoder.queues == 1)
        }
        test("original exact CSD is required and snapshots are defensive") {
            val f = Fixture(1, 312, 0); val spec = TransformerOpusSourceSpec.fromTransport(f.transport)
            val changed = f.opusFormat().buildUpon().setInitializationData(f.csd().also { it[1][0] = 0 }).build()
            check(!spec.matches(changed)); check(spec.matches(f.opusFormat()))
            val o = TransformerSourceObserver(TransformerSourceTimeline(1_000_000, 3, 0), TransformerAudioFormat(48_000, 1), spec) { }
            rejected { o.audioDecoder(FakeCodec(1), changed) }
        }
        test("decoder preallocation guard checks limit even when remaining is small") {
            val f = Fixture(1, 0, 0)
            f.nativeDecoder.outputData = ByteBuffer.allocate(11_522).apply { position(11_520) }
            rejected { f.decoder.outputBuffer }
            val g = Fixture(1, 0, 0); g.nativeDecoder.outputData = ByteBuffer.allocate(3)
            rejected { g.decoder.outputBuffer }
        }
        test("changed upstream false tuple and mutated pending PCM reject") {
            val f = Fixture(8, 0, 0); f.queuePackets(); f.source(5760); val input = f.preparePcm(5760, 960)
            f.graph.falseRemaining = 1; check(!f.sourceConsumer.queueInputBuffer()); input.timeUs++
            rejected { f.sourceConsumer.queueInputBuffer() }
            val g = Fixture(8, 0, 0); g.queuePackets(); g.source(5760); g.preparePcm(5760, 960)
            g.graph.falseRemaining = 1; check(!g.sourceConsumer.queueInputBuffer()); requireNotNull(g.graph.buffer.data).put(0, 99)
            rejected { g.sourceConsumer.queueInputBuffer() }
        }
        test("cancel clears bounded PCM and later callbacks fail") {
            val f = Fixture(2, 0, 0); f.queuePackets(); f.source(960); val input = f.sourceConsumer.inputBuffer!!
            f.cancelled = true; rejected { f.sourceConsumer.queueInputBuffer() }
            f.decoder.release() // Same stopped renderer thread, normal actual release return.
            check((0 until requireNotNull(input.data).capacity()).all { requireNotNull(input.data).get(it).toInt() == 0 })
            rejected { f.sourceConsumer.inputBuffer }
        }
        test("throwing consumer preserves primary failure and wipes partially limited PCM") {
            val f = Fixture(8, 0, 0); f.queuePackets(); f.source(5760)
            val input = f.preparePcm(5760, 960)
            val primary = IllegalStateException("synthetic-primary")
            f.graph.throwFailure = primary
            try { f.sourceConsumer.queueInputBuffer(); error("Expected primary") } catch (failure: Throwable) { check(failure === primary) }
            val expected = pcmBytes(5760, 960, 1)
            check(expected.indices.all { requireNotNull(input.data).get(it) == expected[it] })
            f.decoder.release()
            val all = requireNotNull(input.data).duplicate().apply { clear() }
            check((0 until all.limit()).all { all.get(it).toInt() == 0 })
            rejected { f.sourceConsumer.queueInputBuffer() }
        }
        test("publish-then-throw never wipes a graph-owned downstream carrier") {
            val f = Fixture(8, 0, 0); f.queuePackets(); f.source(5760); f.preparePcm(5760, 960)
            val primary = IllegalStateException("synthetic-after-publish")
            f.graph.throwAfterPublish = primary
            try { f.sourceConsumer.queueInputBuffer(); error("Expected primary") } catch (failure: Throwable) { check(failure === primary) }
            val expected = pcmBytes(0, 960, 1)
            check((expected.indices).all { requireNotNull(f.graph.buffer.data).get(it) == expected[it] })
            rejected { f.sourceConsumer.queueInputBuffer() }
            check((expected.indices).all { requireNotNull(f.graph.buffer.data).get(it) == expected[it] })
        }
        test("video failure and finish serialize behind in-flight audio without lock inversion or wipe") {
            val f = Fixture(8, 0, 0); f.queuePackets()
            val videoNative = FakeCodec(1).apply { info.set(0,0,0,MediaCodec.BUFFER_FLAG_CODEC_CONFIG); hasOutput = true }
            val video = f.observer.videoDecoder(videoNative)
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            f.graph.blockEntered = entered; f.graph.blockProceed = proceed
            val audioFailure = AtomicReference<Throwable?>(); val videoFailure = AtomicReference<Throwable?>(); val finishFailure = AtomicReference<Throwable?>()
            val audioThread = Thread { try { f.source(5760); f.preparePcm(5760, 960); check(f.sourceConsumer.queueInputBuffer()) } catch (failure: Throwable) { audioFailure.set(failure) } }
            val videoThread = Thread { try { video.outputBufferInfo } catch (failure: Throwable) { videoFailure.set(failure) } }
            val finishThread = Thread { try { f.observer.finish(f.transport) } catch (failure: Throwable) { finishFailure.set(failure) } }
            audioThread.start(); check(entered.await(2,TimeUnit.SECONDS))
            val expected = pcmBytes(0, 960, 1)
            videoThread.start(); finishThread.start()
            awaitBlocked(videoThread); awaitBlocked(finishThread)
            check((expected.indices).all { requireNotNull(f.graph.buffer.data).get(it) == expected[it] })
            proceed.countDown()
            listOf(audioThread,videoThread,finishThread).forEach { it.join(2000); check(!it.isAlive) }
            check(audioFailure.get() == null && videoFailure.get() is TransformerFailure && finishFailure.get() is TransformerFailure)
        }
        test("concurrent video failure cannot wipe renderer-borrowed input before queue") {
            val f = Fixture(2, 0, 0); f.queuePackets()
            val primary = IllegalStateException("synthetic-video-failure")
            val video = f.observer.videoDecoder(FakeCodec(1).apply { outputInfoFailure = primary })
            val input = f.preparePcm(0,960); val expected = pcmBytes(0,960,1)
            val observed = AtomicReference<Throwable?>()
            val thread = Thread { try { video.outputBufferInfo } catch (failure: Throwable) { observed.set(failure) } }
            thread.start(); thread.join(2000); check(!thread.isAlive && observed.get() === primary)
            check(expected.indices.all { requireNotNull(input.data).get(it) == expected[it] })
            rejected { f.sourceConsumer.queueInputBuffer() }
            check(expected.indices.all { requireNotNull(input.data).get(it) == expected[it] })
            f.decoder.release()
            val all = requireNotNull(input.data).duplicate().apply { clear() }
            check((0 until all.limit()).all { all.get(it).toInt() == 0 })
        }
        test("throwing or foreign-thread decoder release retains borrowed PCM") {
            for (foreign in listOf(false,true)) {
                val f = Fixture(2,0,0); f.queuePackets()
                val input = f.preparePcm(0,960); val expected = pcmBytes(0,960,1)
                val primary = IllegalStateException("synthetic-release-failure")
                if (foreign) {
                    val thread = Thread { f.decoder.release() }; thread.start(); thread.join(2000); check(!thread.isAlive)
                } else {
                    f.nativeDecoder.releaseFailure = primary
                    try { f.decoder.release(); error("Expected primary") } catch (failure: Throwable) { check(failure === primary) }
                }
                check(expected.indices.all { requireNotNull(input.data).get(it) == expected[it] })
                rejected { f.sourceConsumer.queueInputBuffer() }
                check(expected.indices.all { requireNotNull(input.data).get(it) == expected[it] })
            }
        }
        test("decoder cleanup before source EOS clears PCM and prevents continuation") {
            val f = Fixture(2, 0, 0); f.queuePackets(); f.source(960)
            val input = requireNotNull(f.sourceConsumer.inputBuffer)
            f.decoder.release()
            val all = requireNotNull(input.data).duplicate().apply { clear() }
            check((0 until all.limit()).all { all.get(it).toInt() == 0 })
            rejected { f.sourceConsumer.queueInputBuffer() }
        }
        test("second finish and input after accepted EOS fail") {
            val f = Fixture(1, 0, 0); f.queuePackets(); f.source(960); f.finishSource(); f.finish()
            rejected { f.observer.finish(f.transport) }
            val g = Fixture(1, 0, 0); g.queuePackets(); g.source(960); g.finishSource()
            rejected { g.sourceConsumer.inputBuffer }
        }
        println("PASS: $passed actual Opus observer hook cases. Native decode/head/AAC/playback acceptance NOT_RUN.")
    }

    private class Fixture(val packets: Int, val head: Int, val tail: Int, val channels: Int = 1,
        val silence: Int = 0, val emptySupplement: Boolean = false) {
        val duration = packets * 960
        val effective = duration - head - tail
        var cancelled = false
        val transport = facts()
        val observer = TransformerSourceObserver(TransformerSourceTimeline(1_000_000, 3, 0), TransformerAudioFormat(48_000, channels),
            TransformerOpusSourceSpec.fromTransport(transport)) { if (cancelled) error("cancelled") }
        val nativeDecoder = FakeCodec(channels).apply { configuration = opusFormat() }
        val decoder = observer.audioDecoder(nativeDecoder, opusFormat())
        val nativeEncoder = FakeCodec(channels)
        val encoder = observer.audioEncoder(nativeEncoder).also { it.inputFormat }
        val graph = Graph(channels, encoder, nativeEncoder, silence)
        val sourceConsumer = requireNotNull(observer.sourcePcm(pcmFormat(channels), graph))
        fun csd(): List<ByteArray> {
            val header = byteArrayOf(79,112,117,115,72,101,97,100,1,channels.toByte(),(head and 255).toByte(),(head shr 8).toByte(),0,0,0,0,0,0,0)
            return listOf(header, ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(head * 1_000_000_000L / 48_000).array(),
                ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000).array())
        }
        fun opusFormat(): Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).setSampleRate(48_000).setChannelCount(channels)
            .setInitializationData(csd()).build()
        fun facts(callbacks: Long = packets.toLong()): WebmTransportFacts = WebmTransportFacts(1234, 1_000_000,
            listOf(WebmTrackTransport(1,C.TRACK_TYPE_VIDEO,MimeTypes.VIDEO_VP8,320,240,-1,-1,emptyList(),3,0,666_666,0,333_333_333),
                WebmTrackTransport(2,C.TRACK_TYPE_AUDIO,MimeTypes.AUDIO_OPUS,-1,-1,48_000,channels,csd(),callbacks,0,(packets-1)*20_000L,
                    if (tail > 0) 12 else if (emptySupplement) 4 else 0,null)),
            4,0,0,10,10,1_000_000,timecodeScaleNs = 1_000_000)
        fun queuePackets(nonzeroPosition: Boolean = false, quantized: Boolean = false) {
            for (i in 0 until packets) {
                val pts = i * 20_000L + if (quantized && i != 0 && i != packets - 1) 1000 else 0
                decoder.queueInputBuffer(packet(pts, if (i == packets-1 && tail > 0) tail * 1_000_000_000L / 48_000 else null,
                    nonzeroPosition, emptySupplement && i == packets-1))
            }
            endDecoder()
        }
        fun endDecoder() { decoder.queueInputBuffer(pcmBuffer(0,0,channels,true)); nativeDecoder.ended = true; check(decoder.isEnded) }
        fun preparePcm(start: Int, frames: Int): DecoderInputBuffer {
            val buffer = requireNotNull(sourceConsumer.inputBuffer)
            buffer.ensureSpaceForWrite(frames * channels * 2)
            requireNotNull(buffer.data).put(pcmBytes(start, frames, channels)).flip(); buffer.timeUs = opusTimeUs(start.toLong())
            return buffer
        }
        fun source(frames: Int, chunks: List<Int> = listOf(960)) = sourceFrom(0, frames, chunks)
        fun sourceFrom(start: Int, frames: Int, chunks: List<Int> = listOf(960)) {
            var offset = start; var index = 0
            while (offset < start + frames) {
                val take = minOf(chunks[index++ % chunks.size], start + frames - offset)
                preparePcm(offset,take)
                var attempts = 0
                while (!sourceConsumer.queueInputBuffer()) check(++attempts < 10)
                graph.joinWorkers(); offset += take
            }
        }
        fun finishSource() {
            val input = requireNotNull(sourceConsumer.inputBuffer); requireNotNull(input.data).limit(0); input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            var attempts = 0
            while (!sourceConsumer.queueInputBuffer()) check(++attempts < 10)
            graph.joinWorkers()
        }
        fun finishVideo() {
            val native = FakeCodec(channels); val video = observer.videoDecoder(native)
            repeat(3) { i -> native.info.set(0,0,i*1_000_000L/3,0); native.hasOutput = true; video.outputBufferInfo; video.releaseOutputBuffer(i*1_000_000L/3) }
            video.queueInputBuffer(pcmBuffer(0,0,channels,true)); native.ended = true; check(video.isEnded)
        }
        fun finish(): TransformerAudioObservation { finishVideo(); return requireNotNull(observer.finish(transport).audio) }
    }
    private class Graph(val channels: Int, val encoder: Codec, val native: FakeCodec, val silence: Int) : SampleConsumer {
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply { data = ByteBuffer.allocate(23_040) }
        val accepted = ByteArrayOutputStream()
        var available = true
        var falseRemaining = 0
        var falseEosRemaining = 0
        var eosCalls = 0
        var nonzeroSilence = false
        var crossThread = false
        var throwFailure: Throwable? = null
        var throwAfterPublish: Throwable? = null
        var blockEntered: CountDownLatch? = null
        var blockProceed: CountDownLatch? = null
        private val threads = mutableListOf<Thread>()
        private val threadFailure = AtomicReference<Throwable?>()
        override fun getInputBuffer(): DecoderInputBuffer? = if (available) buffer else null
        override fun queueInputBuffer(): Boolean {
            blockEntered?.countDown()
            blockProceed?.let { check(it.await(3,TimeUnit.SECONDS)) }
            blockEntered = null; blockProceed = null
            throwFailure?.let { throw it }
            if (buffer.isEndOfStream) {
                eosCalls++
                if (falseEosRemaining-- > 0) return false
                if (silence > 0) encoder.queueInputBuffer(pcmBuffer(opusTimeUs((accepted.size() / (2*channels)).toLong()),silence,channels).apply {
                    if (nonzeroSilence) requireNotNull(data).put(0,1)
                })
                encoder.queueInputBuffer(pcmBuffer(0,0,channels,true)); native.ended = true; check(encoder.isEnded)
            } else {
                if (falseRemaining-- > 0) return false
                val bytes = ByteArray(requireNotNull(buffer.data).remaining()); requireNotNull(buffer.data).duplicate().get(bytes); accepted.write(bytes)
                val b = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
                    data = ByteBuffer.wrap(bytes); timeUs = buffer.timeUs
                }
                if (crossThread) {
                    val ready = CountDownLatch(1)
                    val thread = Thread { ready.countDown(); try { encoder.queueInputBuffer(b) } catch (failure: Throwable) { threadFailure.set(failure) } }
                    threads.add(thread); thread.start(); check(ready.await(2,TimeUnit.SECONDS))
                } else encoder.queueInputBuffer(b)
            }
            throwAfterPublish?.let { throw it }
            buffer.clear()
            return true
        }
        fun joinWorkers() {
            threads.forEach { it.join(2000); check(!it.isAlive) }; threads.clear()
            threadFailure.get()?.let { throw it }
        }
    }
    private class FakeCodec(val channels: Int) : Codec {
        val info = MediaCodec.BufferInfo()
        var configuration: Format = pcmFormat(channels)
        var hasOutput = false
        var outputData: ByteBuffer? = null
        var ended = false
        var throwQueue = false
        var outputInfoFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        var queues = 0
        override fun getConfigurationFormat(): Format = configuration
        override fun getName() = "synthetic"
        override fun getInputSurface(): Surface = error("not native")
        override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer) = true
        override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) { queues++; if (throwQueue) error("synthetic"); inputBuffer.data = null }
        override fun signalEndOfInputStream() = Unit
        override fun getInputFormat(): Format = pcmFormat(channels)
        override fun getOutputFormat(): Format = pcmFormat(channels)
        override fun getOutputBuffer(): ByteBuffer? = outputData
        override fun getOutputBufferInfo(): MediaCodec.BufferInfo? { outputInfoFailure?.let { throw it }; return if (hasOutput) info else null }
        override fun releaseOutputBuffer(render: Boolean) { hasOutput = false }
        override fun releaseOutputBuffer(renderPresentationTimeUs: Long) { hasOutput = false }
        override fun isEnded() = ended
        override fun release() { releaseFailure?.let { throw it } }
    }
    private fun packet(pts: Long, paddingNs: Long? = null, nonzeroPosition: Boolean = false, emptySupplement: Boolean = false) =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
            timeUs = pts
            data = if (nonzeroPosition) ByteBuffer.wrap(byteArrayOf(0,0,0xf8.toByte(),0)).apply { position(2) }
                else ByteBuffer.wrap(byteArrayOf(0xf8.toByte(),0))
            if (paddingNs != null || emptySupplement) {
                addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
                supplementalData = if (paddingNs == null) ByteBuffer.allocate(0)
                    else ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(paddingNs).apply { flip() }
            }
        }
    private fun pcmFormat(channels: Int): Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(48_000).setChannelCount(channels).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
    private fun pcmBuffer(pts: Long, frames: Int, channels: Int, eos: Boolean = false) =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
            data = ByteBuffer.allocate(frames * 2 * channels); timeUs = pts; if (eos) addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        }
    private fun pcmBytes(start: Int, frames: Int, channels: Int) = ByteArray(frames * channels * 2) { i ->
        val absolute = start * channels * 2 + i
        ((absolute * 31 + absolute / 19) % 251).toByte()
    }
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        check(thread.state == Thread.State.BLOCKED)
    }
    private fun rejected(work: () -> Unit) { try { work() } catch (_: TransformerFailure) { return } catch (_: IllegalStateException) { return }; error("Expected rejection") }
    private fun test(name: String, work: () -> Unit) { work(); passed++; println("PASS $name") }
}
