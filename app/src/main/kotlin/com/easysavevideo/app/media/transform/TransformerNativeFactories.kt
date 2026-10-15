package com.easysavevideo.app.media.transform

import android.content.Context
import android.media.MediaCodecInfo
import android.media.metrics.LogSessionId
import android.opengl.EGLDisplay
import android.view.Surface
import androidx.media3.common.*
import androidx.media3.common.util.Log
import androidx.media3.effect.DefaultGlObjectsProvider
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class TransformerInputCodecs { AVC_AAC, VP8_OPUS }

/** Counts actual Codec.release returns, including decoders that outlive ExoPlayer.release timeout. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class TransformerNativeFactories(
    context: Context,
    private val output: TransformerOutputSpec,
    private val session: TransformerSession,
    private val inputCodecs: TransformerInputCodecs = TransformerInputCodecs.AVC_AAC,
    private val sourceObserver: TransformerSourceObserver? = null,
) : Codec.DecoderFactory, Codec.EncoderFactory {
    init {
        require((inputCodecs == TransformerInputCodecs.AVC_AAC) == (sourceObserver == null))
        require(inputCodecs != TransformerInputCodecs.VP8_OPUS || output.audio == null || output.audio.sampleRate == 48_000)
    }
    private enum class Role { AUDIO_DECODER, VIDEO_DECODER, AUDIO_ENCODER, VIDEO_ENCODER }
    fun sourcePcm(format: Format, consumer: SampleConsumer?): SampleConsumer? =
        if (sourceObserver == null || format.sampleMimeType != MimeTypes.AUDIO_RAW) consumer else sourceObserver.sourcePcm(format, consumer)

    private val decoders = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(false).build()
    private val encoders = DefaultEncoderFactory.Builder(context)
        .setEnableFallback(false).setEnableFormatFallback(false).setEnableCodecDbLite(false)
        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(output.videoBitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR).setMaxBFrames(0).build())
        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(output.audioBitrate)
            .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build()).build()
    override fun audioNeedsEncoding() = true
    override fun videoNeedsEncoding() = true
    override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec = tracked(Role.AUDIO_DECODER, format) {
        val expected = if (inputCodecs == TransformerInputCodecs.AVC_AAC) MimeTypes.AUDIO_AAC else MimeTypes.AUDIO_OPUS
        if (format.sampleMimeType != expected) reject()
        decoders.createForAudioDecoding(format, null)
    }
    override fun createForVideoDecoding(format: Format, outputSurface: Surface, requestSdrToneMapping: Boolean, logSessionId: LogSessionId?): Codec = tracked(Role.VIDEO_DECODER) {
        val expected = if (inputCodecs == TransformerInputCodecs.AVC_AAC) MimeTypes.VIDEO_H264 else MimeTypes.VIDEO_VP8
        if (format.sampleMimeType != expected || requestSdrToneMapping || ColorInfo.isTransferHdr(format.colorInfo)) reject()
        decoders.createForVideoDecoding(format, outputSurface, false, null)
    }
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec = tracked(Role.AUDIO_ENCODER) {
        val audio = output.audio ?: reject()
        if (format.sampleMimeType != MimeTypes.AUDIO_AAC || format.channelCount != audio.channels || format.sampleRate != audio.sampleRate) reject()
        encoders.createForAudioEncoding(format, null)
    }
    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec = tracked(Role.VIDEO_ENCODER) {
        if (format.sampleMimeType != MimeTypes.VIDEO_H264 || format.width != output.width ||
            format.height != output.height || format.rotationDegrees != 0 || ColorInfo.isTransferHdr(format.colorInfo)) reject()
        encoders.createForVideoEncoding(format.buildUpon().setFrameRate(output.frameRate.toFloat()).build(), null)
    }
    private fun tracked(role: Role, originalAudioFormat: Format? = null, create: () -> Codec): Codec {
        session.checkpoint()
        if (session.codecsCreated.incrementAndGet() > session.limits.maxCodecs) {
            session.codecsCreated.decrementAndGet(); reject()
        }
        val codec = try { create() } catch (failure: Throwable) {
            val native = failure as? ExportException
            val formatUnsupported = native?.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
                native?.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            // Pinned factory policy/query failures use a null codec name before DefaultCodec
            // construction. Named constructor failures or unknown exceptions stay conservative.
            if (TransformerMechanismPolicy.codecCreationProvenAbsent(failure is TransformerFailure, formatUnsupported,
                    native?.codecInfo != null, native?.codecInfo?.name != null)) session.codecsCreated.decrementAndGet()
            else session.closeFailed.set(true)
            throw failure
        }
        session.retainedObjects.add(codec)
        val released = AtomicBoolean(false)
        val tracked = object : Codec by codec {
            override fun release() {
                if (released.get()) return
                if (!TransformerSilentDiagnostics.consistent(session)) session.releaseOrder.glFailed()
                if (!session.releaseOrder.allowsCodecRelease()) {
                    session.closeFailed.set(true)
                    throw TransformerFailure(TransformerError.INVALID_STATE)
                }
                try { codec.release(); released.set(true); session.codecsReleased.incrementAndGet() }
                catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
            }
        }
        return try {
            when (role) {
                Role.VIDEO_DECODER -> sourceObserver?.videoDecoder(tracked) ?: tracked
                Role.AUDIO_DECODER -> sourceObserver?.audioDecoder(tracked, originalAudioFormat ?: reject()) ?: tracked
                Role.AUDIO_ENCODER -> sourceObserver?.audioEncoder(tracked) ?: tracked
                Role.VIDEO_ENCODER -> tracked
            }
        } catch (failure: Throwable) {
            // The actual codec exists but has not escaped to Media3. Release this exact tracked
            // object once; constructor/decorator failure never fabricates absent native creation.
            try { tracked.release() } catch (_: Throwable) { session.closeFailed.set(true) }
            throw failure
        }
    }
    private fun reject(): Nothing = throw TransformerFailure(TransformerError.UNSUPPORTED_PROFILE)
}

/** Owns the GL executor/provider; a returning frameProcessor.release only queues native cleanup. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun transformerFrameProcessors(session: TransformerSession): VideoFrameProcessor.Factory = VideoFrameProcessor.Factory { context, views, color, automatic, listenerExecutor, listener ->
    session.checkpoint()
    if (session.processorsCreated.incrementAndGet() > 2) throw TransformerFailure(TransformerError.SOURCE_LIMIT)
    session.releaseOrder.registerGl()
    val executor = session.newExecutor("esv-edit-gl", 256, ownsGl = true)
    val delegateProvider = DefaultGlObjectsProvider()
    val display = AtomicReference<EGLDisplay?>()
    val provider = object : GlObjectsProvider by delegateProvider {
        override fun createEglContext(eglDisplay: EGLDisplay, openGlVersion: Int, configAttributes: IntArray): android.opengl.EGLContext {
            display.set(eglDisplay)
            return delegateProvider.createEglContext(eglDisplay, openGlVersion, configAttributes)
        }
    }
    session.retainedObjects.add(provider)
    val processor = try {
        DefaultVideoFrameProcessor.Factory.Builder().setExecutorService(executor).setGlObjectsProvider(provider).build()
            .create(context, views, color, automatic, listenerExecutor, listener)
    } catch (failure: Throwable) { session.releaseOrder.glFailed(); session.closeFailed.set(true); throw failure }
    session.retainedObjects.add(processor)
    val releasePosted = AtomicBoolean(false)
    object : VideoFrameProcessor by processor {
        override fun release() {
            if (!releasePosted.compareAndSet(false, true)) return
            session.releaseOrder.beginGlRelease()
            try {
                processor.release() // Enqueues shader cleanup on our executor; it does not wait.
                executor.execute {
                    try {
                        val current = display.get() ?: throw TransformerFailure(TransformerError.INVALID_STATE)
                        delegateProvider.release(current)
                        if (!session.releaseOrder.isPoisoned()) {
                            session.processorsReleased.incrementAndGet()
                            session.releaseOrder.confirmGlReleased()
                        }
                    } catch (_: Throwable) { session.releaseOrder.glFailed(); session.closeFailed.set(true) }
                }
                executor.shutdown() // Do not interrupt/replace the GL worker, ever.
                // Preserve Media3's required ordering: GL may hold decoder/encoder buffers.
                // The caller has a separate bounded watchdog; an unresponsive GL worker keeps
                // this native release blocked, retains every owner, and never admits more work.
                var interrupted = false
                while (!executor.isTerminated) {
                    try { executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
                if (session.releaseOrder.isPoisoned()) throw TransformerFailure(TransformerError.INVALID_STATE)
            } catch (failure: Throwable) {
                session.releaseOrder.glFailed(); session.closeFailed.set(true); throw failure
            }
        }
    }
}

/**
 * Pinned DefaultVideoFrameProcessor swallows GL cleanup exceptions. Capture only the boolean
 * release-failure signal, without storing/emitting tags, messages, paths or exception text.
 * The logger remains a silent sink process-wide. A changed log level invalidates this proof.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object TransformerSilentDiagnostics {
    private val active = AtomicReference<TransformerSession?>()
    fun begin(session: TransformerSession) {
        active.set(session)
        Log.setLogger(object : Log.Logger {
            override fun d(tag: String, message: String, throwable: Throwable?) = Unit
            override fun i(tag: String, message: String, throwable: Throwable?) = Unit
            override fun w(tag: String, message: String, throwable: Throwable?) = Unit
            override fun e(tag: String, message: String, throwable: Throwable?) {
                if (tag == "DefaultFrameProcessor" && message.startsWith("Error releasing")) active.get()?.let {
                    it.releaseOrder.glFailed(); it.closeFailed.set(true)
                }
            }
        })
        Log.setLogLevel(Log.LOG_LEVEL_ERROR)
    }
    fun consistent(session: TransformerSession): Boolean = active.get() === session && Log.getLogLevel() == Log.LOG_LEVEL_ERROR
    fun end(session: TransformerSession) {
        if (Log.getLogLevel() != Log.LOG_LEVEL_ERROR) session.closeFailed.set(true)
        if (session.outcomeReleased) { active.compareAndSet(session, null); Log.setLogLevel(Log.LOG_LEVEL_OFF) }
    }
}
