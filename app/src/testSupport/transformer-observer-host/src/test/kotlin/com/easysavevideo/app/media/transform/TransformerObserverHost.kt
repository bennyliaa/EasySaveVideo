package com.easysavevideo.app.media.transform

import android.media.MediaCodec
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.SampleConsumer
import java.nio.ByteBuffer

/** Real production public wrappers + pinned Media3 carriers; synthetic delegates, not native evidence. */
object TransformerObserverHost {
    private var passed = 0
    @JvmStatic fun main(args: Array<String>) {
        test("video repeated info, mutable reuse, format polling, zero-size surface, renderer offset") {
            val o = observer(); val native = FakeCodec(); val wrapped = o.videoDecoder(native)
            native.publish(-1, 0); wrapped.outputBufferInfo; wrapped.releaseOutputBuffer(false)
            for (i in 0..2) {
                native.publish(9_000_000L + i * 1_000_000, 0)
                wrapped.outputFormat // Must not count.
                check(wrapped.outputBufferInfo === wrapped.outputBufferInfo)
                wrapped.releaseOutputBuffer(i * 1_000_000L)
            }
            end(wrapped, native)
            val facts = o.finish(); check(facts.video.displayedFrames == 3L && facts.video.firstLocalPtsUs == 0L)
            check(native.longReleases == 3 && native.falseReleases == 1)
            rejected { o.finish() }
        }
        test("held tuple mutation is sticky") {
            val o = observer(); val n = FakeCodec(); val w = o.videoDecoder(n)
            n.publish(0); w.outputBufferInfo; n.info.presentationTimeUs = 1
            rejected { w.outputBufferInfo }; rejected { o.finish() }; check(n.longReleases == 0)
        }
        test("one pending lifetime cannot render twice") {
            val o = observer(); val n = FakeCodec(); val w = o.videoDecoder(n)
            n.publish(0); w.outputBufferInfo; w.releaseOutputBuffer(0L)
            rejected { w.releaseOutputBuffer(0L) }; check(n.longReleases == 1)
        }
        test("throwing long release cannot retry or mint observation") {
            val o = observer(); val n = FakeCodec(); val w = o.videoDecoder(n)
            n.publish(0); w.outputBufferInfo; n.throwRelease = true
            rejected { w.releaseOutputBuffer(0L) }; rejected { w.releaseOutputBuffer(0L) }
            check(n.longReleases == 1); rejected { o.finish() }
        }
        test("unexpected true delegates once and poisons") {
            val o = observer(); val n = FakeCodec(); val w = o.videoDecoder(n)
            n.publish(0); w.outputBufferInfo
            rejected { w.releaseOutputBuffer(true) }; check(n.trueReleases == 1)
            rejected { w.releaseOutputBuffer(true) }; check(n.trueReleases == 1)
        }
        test("pending buffer prevents EOS completion") {
            val o = observer(); val n = FakeCodec(); val w = o.videoDecoder(n)
            w.queueInputBuffer(buffer(0, 0, true)); n.publish(0); w.outputBufferInfo; n.ended = true
            rejected { w.isEnded }; rejected { o.finish() }
        }
        test("delayed first displayed frame is never rebased") {
            val o = observer(); val n = FakeCodec(); val w = o.videoDecoder(n)
            n.publish(500_000); w.outputBufferInfo; rejected { w.releaseOutputBuffer(500_000L) }
            check(n.longReleases == 0)
        }
        test("missing multiple-frame tail is rejected") {
            val o = observer(5_000_000); finishVideo(o)
            rejected { o.finish() }
        }
        TransformerOpusObserverHost.run()
        println("PASS: $passed production video observer contracts plus Opus hook cases. NOT_RUN: Android codecs, GL, native Opus mapping, AAC presentation/playable output.")
    }
    private fun observer(duration: Long = 3_000_000, audio: Boolean = false) =
        TransformerSourceObserver(TransformerSourceTimeline(duration, 1, 0), if (audio) TransformerAudioFormat(48_000, 1) else null) { }
    private fun pcm() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(48_000).setChannelCount(1).build()
    private fun buffer(pts: Long, frames: Int, eos: Boolean = false) = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL).apply {
        timeUs = pts; data = ByteBuffer.allocate(frames * 2); if (eos) addFlag(C.BUFFER_FLAG_END_OF_STREAM)
    }
    private fun end(codec: Codec, native: FakeCodec) { codec.queueInputBuffer(buffer(0, 0, true)); native.ended = true; check(codec.isEnded) }
    private fun finishVideo(o: TransformerSourceObserver) {
        val n = FakeCodec(); val w = o.videoDecoder(n)
        for (i in 0..2) { n.publish(i * 1_000_000L, 0); w.outputBufferInfo; w.releaseOutputBuffer(i * 1_000_000L) }
        end(w, n)
    }
    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: TransformerFailure) { return } catch (_: IllegalStateException) { return }
        error("Expected rejection")
    }
    private fun test(name: String, work: () -> Unit) { work(); passed++; println("PASS $name") }
    private class FakeConsumer : SampleConsumer {
        var pending = buffer(0, 0)
        var accept = true
        var throwQueue = false
        var queues = 0
        override fun getInputBuffer(): DecoderInputBuffer = pending
        override fun queueInputBuffer(): Boolean {
            queues++; if (throwQueue) error("synthetic")
            if (accept) { pending.timeUs = Long.MIN_VALUE; pending.data = null; pending.clear() }
            return accept
        }
    }
    private class FakeCodec : Codec {
        val info = MediaCodec.BufferInfo()
        var held = false
        var ended = false
        var throwRelease = false
        var throwQueue = false
        var queues = 0
        var longReleases = 0
        var trueReleases = 0
        var falseReleases = 0
        fun publish(pts: Long, size: Int = 1) { info.set(0, size, pts, 0); held = true }
        override fun getConfigurationFormat(): Format = pcm()
        override fun getName(): String = "synthetic"
        override fun getInputSurface(): Surface = error("not a real codec")
        override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean = true
        override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) { queues++; if (throwQueue) error("synthetic"); inputBuffer.data = null }
        override fun signalEndOfInputStream() = Unit
        override fun getInputFormat(): Format = pcm()
        override fun getOutputFormat(): Format = pcm()
        override fun getOutputBuffer(): ByteBuffer? = null
        override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = if (held) info else null
        override fun releaseOutputBuffer(render: Boolean) { if (render) trueReleases++ else falseReleases++; if (throwRelease) error("synthetic"); held = false }
        override fun releaseOutputBuffer(renderPresentationTimeUs: Long) { longReleases++; if (throwRelease) error("synthetic"); held = false }
        override fun isEnded(): Boolean = ended
        override fun release() = Unit
    }
}
