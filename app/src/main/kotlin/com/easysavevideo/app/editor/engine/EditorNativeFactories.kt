package com.easysavevideo.app.editor.engine

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
import com.easysavevideo.library.edit.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Counts actual Codec.release returns, including decoders that outlive ExoPlayer.release timeout. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class EditorNativeFactories(context: Context, private val plan: EditPlan, private val session: EditorSession) : Codec.DecoderFactory, Codec.EncoderFactory {
    private val decoders = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(false).build()
    private val encoders = DefaultEncoderFactory.Builder(context)
        .setEnableFallback(false).setEnableFormatFallback(false).setEnableCodecDbLite(false)
        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR).setMaxBFrames(0).build())
        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.audioBitrate)
            .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC).build()).build()
    override fun audioNeedsEncoding() = true
    override fun videoNeedsEncoding() = true
    override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec = tracked {
        if (format.sampleMimeType != MimeTypes.AUDIO_AAC) reject()
        decoders.createForAudioDecoding(format, null)
    }
    override fun createForVideoDecoding(format: Format, outputSurface: Surface, requestSdrToneMapping: Boolean, logSessionId: LogSessionId?): Codec = tracked {
        if (format.sampleMimeType != MimeTypes.VIDEO_H264 || requestSdrToneMapping || ColorInfo.isTransferHdr(format.colorInfo)) reject()
        decoders.createForVideoDecoding(format, outputSurface, false, null)
    }
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec = tracked {
        val audio = plan.expectation.audio as? EditAudioProfile.Aac ?: reject()
        if (format.sampleMimeType != MimeTypes.AUDIO_AAC || format.channelCount != audio.channels || format.sampleRate != audio.sampleRate) reject()
        encoders.createForAudioEncoding(format, null)
    }
    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec = tracked {
        if (format.sampleMimeType != MimeTypes.VIDEO_H264 || format.width != plan.expectation.width ||
            format.height != plan.expectation.height || format.rotationDegrees != 0 || ColorInfo.isTransferHdr(format.colorInfo)) reject()
        encoders.createForVideoEncoding(format.buildUpon().setFrameRate(plan.expectation.frameRate.toFloat()).build(), null)
    }
    private fun tracked(create: () -> Codec): Codec {
        session.checkpoint()
        if (session.codecsCreated.incrementAndGet() > EditorEnginePolicy.MAX_CODECS) {
            session.codecsCreated.decrementAndGet(); reject()
        }
        val codec = try { create() } catch (failure: Throwable) {
            val native = failure as? ExportException
            val formatUnsupported = native?.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
                native?.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            // Pinned factory policy/query failures use a null codec name before DefaultCodec
            // construction. Named constructor failures or unknown exceptions stay conservative.
            if (EditorEnginePolicy.codecCreationProvenAbsent(failure is EditFailure, formatUnsupported,
                    native?.codecInfo != null, native?.codecInfo?.name != null)) session.codecsCreated.decrementAndGet()
            else session.closeFailed.set(true)
            throw failure
        }
        session.retainedObjects.add(codec)
        val released = AtomicBoolean(false)
        return object : Codec by codec {
            override fun release() {
                if (released.get()) return
                if (!EditorSilentDiagnostics.consistent(session)) session.releaseOrder.glFailed()
                if (!session.releaseOrder.allowsCodecRelease()) {
                    session.closeFailed.set(true)
                    throw EditFailure(EditError.RELEASE_UNCONFIRMED)
                }
                try { codec.release(); released.set(true); session.codecsReleased.incrementAndGet() }
                catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
            }
        }
    }
    private fun reject(): Nothing = throw EditFailure(EditError.UNSUPPORTED_PROFILE)
}

/** Owns the GL executor/provider; a returning frameProcessor.release only queues native cleanup. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun editorFrameProcessors(session: EditorSession): VideoFrameProcessor.Factory = VideoFrameProcessor.Factory { context, views, color, automatic, listenerExecutor, listener ->
    session.checkpoint()
    if (session.processorsCreated.incrementAndGet() > 2) throw EditFailure(EditError.SOURCE_LIMIT)
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
                        val current = display.get() ?: throw EditFailure(EditError.RELEASE_UNCONFIRMED)
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
                if (session.releaseOrder.isPoisoned()) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
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
internal object EditorSilentDiagnostics {
    private val active = AtomicReference<EditorSession?>()
    fun begin(session: EditorSession) {
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
    fun consistent(session: EditorSession): Boolean = active.get() === session && Log.getLogLevel() == Log.LOG_LEVEL_ERROR
    fun end(session: EditorSession) {
        if (Log.getLogLevel() != Log.LOG_LEVEL_ERROR) session.closeFailed.set(true)
        if (session.outcomeReleased) { active.compareAndSet(session, null); Log.setLogLevel(Log.LOG_LEVEL_OFF) }
    }
}
