package com.easysavevideo.app.media.transform

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.easysavevideo.app.save.storage.OwnedDirectTranscodeAttempt
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.transformer.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean


/** Private two-caller mechanism. It neither acquires product ownership nor publishes output. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
internal class TransformerEngine(context: Context) {
    private val app = context.applicationContext
    private val handler get() = SharedTransformerRuntime.handler
    fun execute(spec: TransformerRunSpec, target: TransformerOwnedTarget, session: TransformerSession): TransformerEngineFacts {
        val run = requireIssuedTransformerRun(spec) // Must precede all IO/native/resource actions.
        validateTransformerEditorItems(run)
        session.checkpoint()
        run.items.forEach { session.checkpoint(); session.retain(it.source) }
        target.verifyIdentity()
        if (target.sizeBytes() != 0L || target.availableBytes() < run.output.requiredFreeBytes) fail(TransformerError.INSUFFICIENT_SPACE)
        val muxer = TransformerBoundedMuxer(run.output, target, session)
        session.retainedObjects.add(muxer)
        TransformerSilentDiagnostics.begin(session)
        session.hasTransformer = true
        session.lastProgressNanos = System.nanoTime()
        if (!handler.post {
            try { configure(run, target, muxer, session) }
            catch (failure: Throwable) { session.completion.completeExceptionally(failure) }
        }) fail(TransformerError.EXECUTION_FAILED)
        val exported = awaitExport(run.output, target, session)
        validateExport(exported, run.output)
        target.verifyIdentity()
        return muxer.evidence()
    }

    /** Sole Direct entry. A freely constructed RunSpec never reaches this method. */
    fun executeOwnedDirect(attempt: OwnedDirectTranscodeAttempt): TransformerEngineFacts {
        attempt.claimOwnedDirect(this) // First operation, before IO, native factories or scheduling.
        val session = attempt.directSession(this)
        val output = attempt.directOutput(this)
        val target = attempt.directTarget(this)
        val fixedSource = attempt.directMediaSource(this)
        val transport = attempt.directTransport(this)
        val observer = TransformerSourceObserver(attempt.directTimeline(this), output.audio,
            if (output.audio == null) null else TransformerOpusSourceSpec.fromTransport(transport), session::requireEntered)
        fun original() { attempt.directOriginalFailure(this, session)?.let { throw it } }
        session.requireEntered(); original()
        target.verifyIdentity()
        if (target.sizeBytes() != 0L || target.availableBytes() < output.requiredFreeBytes) fail(TransformerError.INSUFFICIENT_SPACE)
        val muxer = TransformerBoundedMuxer(output, target, session)
        session.retainedObjects.add(muxer)
        val callbackLock = Any()
        var callbackItem: EditedMediaItem? = null
        var sourceClaimed = false
        var reportedDuration: Long? = null
        var reportedTracks: Int? = null
        val formats = mutableMapOf<Int, Format>()
        val outputFormats = mutableMapOf<Int, Format>()
        val expectedTypes = transport.tracks.map { it.trackType }.toSet()
        val selectedTypes = if (output.audio == null) setOf(C.TRACK_TYPE_VIDEO)
            else setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        if (expectedTypes != selectedTypes || transport.tracks.size != selectedTypes.size)
            fail(TransformerError.UNSUPPORTED_PROFILE)
        val originalItem = EditedMediaItem.Builder(fixedSource.mediaItem).build()
        val factories = TransformerNativeFactories(app, output, session, TransformerInputCodecs.VP8_OPUS, observer)
        val mediaSources = object : MediaSource.Factory {
            // This source was already configured with its exact no-DRM/no-retry/download policies.
            // No call needs to reconfigure it; an unprovable replacement is never delegated.
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = fail(TransformerError.INVALID_REQUEST)
            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = fail(TransformerError.INVALID_REQUEST)
            override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                session.requireEntered(); original()
                synchronized(callbackLock) {
                    val actual = callbackItem ?: fail(TransformerError.INVALID_REQUEST)
                    if (sourceClaimed || mediaItem !== actual.mediaItem || mediaItem !== fixedSource.mediaItem)
                        fail(TransformerError.INVALID_REQUEST)
                    sourceClaimed = true
                }
                return object : MediaSource by fixedSource {
                    override fun prepareSource(caller: MediaSource.MediaSourceCaller, playerId: PlayerId, bandwidthMeter: BandwidthMeter) {
                        session.playbackThreads.add(Thread.currentThread()); session.nativeThreads.add(Thread.currentThread())
                        fixedSource.prepareSource(caller, playerId, bandwidthMeter)
                    }
                }
            }
        }
        val loaders = AssetLoader.Factory { item, looper, listener, settings ->
            session.requireEntered(); original()
            validateOwnedDirectItem(item, fixedSource.mediaItem, output.audio != null)
            synchronized(callbackLock) {
                if (callbackItem != null) fail(TransformerError.INVALID_REQUEST)
                callbackItem = item // The actual rebuilt item, never the pre-sequence wrapper.
            }
            session.nativeThreads.add(looper.thread)
            if (session.loadersCreated.incrementAndGet() > session.limits.maxLoaders) fail(TransformerError.SOURCE_LIMIT)
            val wrapped = object : AssetLoader.Listener {
                override fun onDurationUs(durationUs: Long) {
                    session.requireEntered(); original()
                    synchronized(callbackLock) {
                        if (durationUs != output.durationUs || (reportedDuration != null && reportedDuration != durationUs))
                            fail(TransformerError.UNSUPPORTED_PROFILE)
                        reportedDuration = durationUs
                    }
                    listener.onDurationUs(durationUs)
                }
                override fun onTrackCount(trackCount: Int) {
                    session.requireEntered(); original()
                    synchronized(callbackLock) {
                        if (reportedTracks != null || trackCount != transport.tracks.size) fail(TransformerError.UNSUPPORTED_PROFILE)
                        reportedTracks = trackCount
                    }
                    listener.onTrackCount(trackCount)
                }
                override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean {
                    session.requireEntered(); original()
                    val type = MimeTypes.getTrackType(inputFormat.sampleMimeType)
                    val expected = transport.tracks.singleOrNull { it.trackType == type } ?: fail(TransformerError.UNSUPPORTED_PROFILE)
                    if (inputFormat.sampleMimeType != expected.mimeType || inputFormat.drmInitData != null ||
                        inputFormat.cryptoType != C.CRYPTO_TYPE_NONE || ColorInfo.isTransferHdr(inputFormat.colorInfo) ||
                        supportedOutputTypes and AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED == 0 ||
                        inputFormat.initializationData.size != expected.initializationData.size ||
                        !inputFormat.initializationData.zip(expected.initializationData).all { (a, b) -> a.contentEquals(b) })
                        fail(TransformerError.UNSUPPORTED_PROFILE)
                    when (type) {
                        C.TRACK_TYPE_VIDEO -> if (inputFormat.sampleMimeType != MimeTypes.VIDEO_VP8 ||
                            inputFormat.width != expected.width || inputFormat.height != expected.height ||
                            inputFormat.rotationDegrees != 0 || inputFormat.pixelWidthHeightRatio != 1f) fail(TransformerError.UNSUPPORTED_PROFILE)
                        C.TRACK_TYPE_AUDIO -> if (inputFormat.sampleMimeType != MimeTypes.AUDIO_OPUS || output.audio == null ||
                            inputFormat.sampleRate != expected.sampleRate || inputFormat.channelCount != expected.channelCount)
                            fail(TransformerError.UNSUPPORTED_PROFILE)
                        else -> fail(TransformerError.UNSUPPORTED_PROFILE)
                    }
                    synchronized(callbackLock) { if (formats.put(type, inputFormat) != null) fail(TransformerError.UNSUPPORTED_PROFILE) }
                    if (!listener.onTrackAdded(inputFormat, AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED)) fail(TransformerError.UNSUPPORTED_PROFILE)
                    return true
                }
                override fun onOutputFormat(format: Format): SampleConsumer? {
                    session.requireEntered(); original()
                    val type = MimeTypes.getTrackType(format.sampleMimeType)
                    synchronized(callbackLock) {
                        if (type !in expectedTypes || !formats.containsKey(type) || format.drmInitData != null ||
                            format.cryptoType != C.CRYPTO_TYPE_NONE || ColorInfo.isTransferHdr(format.colorInfo) ||
                            outputFormats[type]?.let { it != format } == true) fail(TransformerError.UNSUPPORTED_PROFILE)
                        outputFormats[type] = format
                    }
                    return factories.sourcePcm(format, listener.onOutputFormat(format))
                }
                override fun onError(exportException: ExportException) { listener.onError(exportException) }
            }
            trackedLoader(session) {
                ExoPlayerAssetLoader.Factory(app, factories, Clock.DEFAULT, mediaSources, null, null, newLoadControl())
                    .createAssetLoader(item, looper, wrapped, settings)
            }
        }
        TransformerSilentDiagnostics.begin(session)
        session.hasTransformer = true
        session.lastProgressNanos = System.nanoTime()
        if (!handler.post {
            try {
                session.requireEntered(); original()
                val transformer = configuredTransformer(loaders, factories, muxer, session, directListener(attempt, session))
                session.transformer.set(transformer)
                session.requireEntered(); original()
                val sequence = EditedMediaItemSequence.Builder(expectedTypes).addItem(originalItem).build()
                val composition = Composition.Builder(sequence).setTransmuxVideo(false).setTransmuxAudio(false).build()
                transformer.start(composition, target.muxerToken)
            } catch (failure: Throwable) {
                val code = when (failure) {
                    is ExportException -> failure.errorCode
                    is TransformerFailure -> if (failure.code == TransformerError.UNSUPPORTED_PROFILE)
                        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED else ExportException.ERROR_CODE_UNSPECIFIED
                    else -> ExportException.ERROR_CODE_UNSPECIFIED
                }
                attempt.completeDirectNativeFailure(this, session, code)
            }
        }) fail(TransformerError.EXECUTION_FAILED)
        val exported = awaitExport(output, target, session)
        original()
        validateExport(exported, output)
        synchronized(callbackLock) {
            if (!sourceClaimed || callbackItem == null || reportedDuration != output.durationUs ||
                reportedTracks != transport.tracks.size || formats.keys != expectedTypes || outputFormats.keys != expectedTypes)
                fail(TransformerError.UNSUPPORTED_PROFILE)
        }
        target.verifyIdentity(); original()
        val encoded = muxer.evidence()
        val completed = attempt.directCompletedTransport(this) // Same actual loaded EOF facts retained by the owner.
        val observation = observer.finish(completed) // Still inside the real worker entry; exactly once.
        original()
        if (encoded.videoSamples != observation.video.displayedFrames ||
            observation.video.displayedFrames != output.expectedVideoFrames) fail(TransformerError.UNSUPPORTED_PROFILE)
        return encoded.copy(sourceObservation = observation)
    }

    /** The original object comes only from registered boundaries, never an arbitrary cause scan. */
    private fun directListener(attempt: OwnedDirectTranscodeAttempt, session: TransformerSession): Transformer.Listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            val original = attempt.directOriginalFailure(this@TransformerEngine, session)
            if (original != null) session.completion.completeExceptionally(original)
            else session.completion.complete(exportResult)
        }
        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            attempt.completeDirectNativeFailure(this@TransformerEngine, session, exportException.errorCode)
        }
        override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest, fallbackTransformationRequest: TransformationRequest) {
            attempt.completeDirectNativeFailure(this@TransformerEngine, session, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED)
            session.abandon()
        }
    }
    private fun editorListener(session: TransformerSession): Transformer.Listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) { session.completion.complete(exportResult) }
        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            session.completion.completeExceptionally(nativeFailure(exportException))
        }
        override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest, fallbackTransformationRequest: TransformationRequest) {
            session.completion.completeExceptionally(TransformerFailure(TransformerError.UNSUPPORTED_PROFILE)); session.abandon()
        }
    }
    private fun nativeFailure(error: ExportException): TransformerFailure = TransformerFailure(
        if (error.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
            error.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) TransformerError.UNSUPPORTED_PROFILE else TransformerError.EXECUTION_FAILED)

    private fun validateOwnedDirectItem(item: EditedMediaItem, expected: MediaItem, hasAudio: Boolean) {
        if (item.mediaItem !== expected || item.removeVideo || item.removeAudio == hasAudio || item.flattenForSlowMotion ||
            item.durationUs != C.TIME_UNSET || item.frameRate != C.RATE_UNSET_INT ||
            item.effects.audioProcessors.isNotEmpty() || item.effects.videoEffects.isNotEmpty() ||
            item.speedProvider !== SpeedProvider.DEFAULT || item.speedParameters !== SpeedParameters.DEFAULT)
            fail(TransformerError.INVALID_REQUEST)
    }
    private fun newLoadControl() = DefaultLoadControl.Builder().setTargetBufferBytes(8 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false).setBufferDurationsMs(500, 2000, 100, 100).build()
    private fun trackedLoader(session: TransformerSession, create: () -> AssetLoader): AssetLoader {
        val base = try { create() } catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
        session.retainedObjects.add(base)
        val released = AtomicBoolean(false)
        return object : AssetLoader by base {
            override fun release() {
                if (released.get()) return
                try { base.release(); released.set(true); session.loadersReleased.incrementAndGet() }
                catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
            }
        }
    }
    private fun configuredTransformer(loaders: AssetLoader.Factory, factories: TransformerNativeFactories,
        muxer: TransformerBoundedMuxer, session: TransformerSession, listener: Transformer.Listener): Transformer =
        Transformer.Builder(app).setLooper(handler.looper).setAssetLoaderFactory(loaders)
            .setEncoderFactory(factories).setVideoFrameProcessorFactory(transformerFrameProcessors(session)).setMuxerFactory(muxer)
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setPortraitEncodingEnabled(true).setUsePlatformDiagnostics(false)
            .experimentalSetTrimOptimizationEnabled(false).experimentalSetMp4EditListTrimEnabled(false)
            .experimentalSetMaxFramesInEncoder(5).setMaxDelayBetweenMuxerSamplesMs(session.limits.stallTimeoutMillis)
            .addListener(listener).build()
    private fun awaitExport(output: TransformerOutputSpec, target: TransformerOwnedTarget, session: TransformerSession): ExportResult {
        while (!session.completion.isDone) {
            session.checkpoint()
            target.verifyIdentity()
            if (target.sizeBytes() > output.maxBytes) fail(TransformerError.SOURCE_LIMIT)
            if (target.availableBytes() < TransformerMechanismPolicy.OUTPUT_RESERVE) fail(TransformerError.INSUFFICIENT_SPACE)
            try { session.completion.get(TransformerMechanismPolicy.POLL_MILLIS, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
        }
        return session.completion.get().also { session.checkpoint() }
    }
    private fun validateExport(exported: ExportResult, output: TransformerOutputSpec) {
        if (exported.exportException != null || exported.videoConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSCODED ||
            exported.videoEncoderName == null || (output.audio != null &&
                (exported.audioConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSCODED || exported.audioEncoderName == null)))
            fail(TransformerError.EXECUTION_FAILED)
    }

    private fun configure(spec: TransformerRunSpec.EditorAvcAac, target: TransformerOwnedTarget, muxer: TransformerBoundedMuxer, session: TransformerSession) {
        session.checkpoint()
        val factories = TransformerNativeFactories(app, spec.output, session)
        val local = TransformerDataSource(spec.items, session)
        val progressive = ProgressiveMediaSource.Factory(local, ExtractorsFactory { arrayOf(Mp4Extractor()) })
            .setDrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            .setDownloadExecutor { session.newDownloadExecutor() }
        val mediaSources = object : MediaSource.Factory by progressive {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val base = progressive.createMediaSource(mediaItem)
                val tracked = object : MediaSource by base {
                    override fun prepareSource(caller: MediaSource.MediaSourceCaller, playerId: PlayerId, bandwidthMeter: BandwidthMeter) {
                        session.playbackThreads.add(Thread.currentThread()); session.nativeThreads.add(Thread.currentThread())
                        base.prepareSource(caller, playerId, bandwidthMeter)
                    }
                }
                // ProgressiveMediaSource alone does not apply MediaItem's clip configuration.
                return ClippingMediaSource.Builder(tracked).setClippingConfiguration(mediaItem.clippingConfiguration)
                    .setEnableClippingInMediaPeriod(true).build()
            }
        }
        val loaders = AssetLoader.Factory { item, looper, listener, settings ->
            session.checkpoint()
            session.nativeThreads.add(looper.thread)
            if (session.loadersCreated.incrementAndGet() > session.limits.maxLoaders) fail(TransformerError.SOURCE_LIMIT)
            val sourceIndex = spec.items.indices.singleOrNull { item.mediaItem.localConfiguration?.uri.toString() == spec.items[it].address }
                ?: fail(TransformerError.INVALID_REQUEST)
            val expected = spec.items[sourceIndex].profile
            val wrapped = object : AssetLoader.Listener {
                override fun onDurationUs(durationUs: Long) { session.checkpoint(); listener.onDurationUs(durationUs) }
                override fun onTrackCount(trackCount: Int) {
                    if (trackCount != if (expected.audio == null) 1 else 2) fail(TransformerError.UNSUPPORTED_PROFILE)
                    listener.onTrackCount(trackCount)
                }
                override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean {
                    session.checkpoint()
                    if (inputFormat.drmInitData != null || inputFormat.cryptoType != C.CRYPTO_TYPE_NONE ||
                        supportedOutputTypes and AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED == 0) fail(TransformerError.UNSUPPORTED_PROFILE)
                    when (inputFormat.sampleMimeType) {
                        MimeTypes.VIDEO_H264 -> if (inputFormat.width != expected.width || inputFormat.height != expected.height ||
                            inputFormat.rotationDegrees != expected.rotationDegrees || inputFormat.pixelWidthHeightRatio != 1f ||
                            ColorInfo.isTransferHdr(inputFormat.colorInfo)) fail(TransformerError.UNSUPPORTED_PROFILE)
                        MimeTypes.AUDIO_AAC -> {
                            val audio = expected.audio ?: fail(TransformerError.UNSUPPORTED_PROFILE)
                            if (inputFormat.sampleRate != audio.sampleRate || inputFormat.channelCount != audio.channels) fail(TransformerError.UNSUPPORTED_PROFILE)
                        }
                        else -> fail(TransformerError.UNSUPPORTED_PROFILE)
                    }
                    if (!listener.onTrackAdded(inputFormat, AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED)) fail(TransformerError.UNSUPPORTED_PROFILE)
                    return true
                }
                override fun onOutputFormat(format: Format): SampleConsumer? { session.checkpoint(); return factories.sourcePcm(format, listener.onOutputFormat(format)) }
                override fun onError(exportException: ExportException) { listener.onError(exportException) }
            }
            // Each loader gets its own bounded LoadControl; instances must not cross playback threads.
            val load = DefaultLoadControl.Builder().setTargetBufferBytes(8 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false).setBufferDurationsMs(500, 2000, 100, 100).build()
            val base = try { ExoPlayerAssetLoader.Factory(app, factories, Clock.DEFAULT, mediaSources, null, null, load)
                .createAssetLoader(item, looper, wrapped, settings) }
            catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
            session.retainedObjects.add(base)
            val released = AtomicBoolean(false)
            object : AssetLoader by base {
                override fun release() {
                    if (released.get()) return
                    try { base.release(); released.set(true); session.loadersReleased.incrementAndGet() }
                    catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
                }
            }
        }
        val transformer = configuredTransformer(loaders, factories, muxer, session, editorListener(session))
        session.transformer.set(transformer)
        session.checkpoint()
        val items = spec.items.map { it.mediaItem }
        val types = if (spec.output.audio == null) setOf(C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        val sequence = EditedMediaItemSequence.Builder(types).addItems(items).build()
        val composition = Composition.Builder(sequence).setTransmuxVideo(false).setTransmuxAudio(false).build()
        transformer.start(composition, target.muxerToken)
    }

    private fun fail(code: TransformerError): Nothing = throw TransformerFailure(code)
}
