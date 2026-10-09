package com.easysavevideo.app.editor.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.*
import androidx.media3.common.util.Clock
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
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
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.edit.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded real Media3 1.11.1 decode/effect/encode path. Requires the composition root to hold
 * OrdinaryMediaNativeResources across probe, execute, independent verify, and owned publication.
 * This adapter does NOT publish, delete originals, accept arbitrary paths, or gate cancellation
 * behind an entitlement. All failure text is a bounded technical code.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class Media3EditorEngine(context: Context, private val inputs: EditorInputPort) {
    private val app = context.applicationContext
    fun requiresProcessRecovery(): Boolean = fence.requiresRestart

    fun probe(reference: EditInputRef, context: OperationContext): EditSourceMetadata =
        bounded(context, EditLimits.PREPARE_TIMEOUT_MILLIS, null) { session -> EditorMetadataProbe.inspect(reference, session) }

    fun execute(plan: EditPlan, output: EditorOwnedOutput, context: OperationContext): EditorEngineEvidence =
        bounded(context, EditLimits.EXECUTION_TIMEOUT_MILLIS, output) { session ->
            if (plan.request.operationId != context.operationId || plan.segments.size !in 1..plan.request.maxSegments) fail(EditError.INVALID_REQUEST)
            output.verifyIdentity()
            if (output.file.length() != 0L || output.availableBytes() < plan.requiredFreeBytes) fail(EditError.INSUFFICIENT_SPACE)
            val distinct = plan.segments.distinctBy { it.segment.source }
            // Reacquire current catalog authority, bind native fences, and compare observed profile.
            distinct.forEach {
                if (EditorMetadataProbe.inspect(it.segment.source, session) != it.metadata) fail(EditError.STALE_SOURCE)
            }
            val sources = plan.segments.map { it.segment.source }
            val muxer = EditorBoundedMuxer(plan, session)
            session.retainedObjects.add(muxer)
            EditorSilentDiagnostics.begin(session)
            session.hasTransformer = true
            session.lastProgressNanos = System.nanoTime()
            if (!handler.post {
                try { configure(plan, sources, muxer, session) }
                catch (_: Throwable) { session.completion.completeExceptionally(EditFailure(EditError.EXECUTION_FAILED)) }
            }) fail(EditError.EXECUTION_FAILED)
            while (!session.completion.isDone) {
                session.checkpoint()
                output.verifyIdentity()
                if (output.file.length() > plan.expectation.maxBytes) fail(EditError.SOURCE_LIMIT)
                if (output.availableBytes() < EditorEnginePolicy.OUTPUT_RESERVE) fail(EditError.INSUFFICIENT_SPACE)
                try { session.completion.get(EditorEnginePolicy.POLL_MILLIS, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
            val exported = session.completion.get()
            session.checkpoint()
            if (exported.exportException != null || exported.videoConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSCODED ||
                exported.videoEncoderName == null || (plan.expectation.audio is EditAudioProfile.Aac &&
                    (exported.audioConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSCODED || exported.audioEncoderName == null))) fail(EditError.EXECUTION_FAILED)
            // Re-hash current catalog handles after native reads, defending against in-place changes.
            distinct.forEach { verifyHash(it.segment.source, session) }
            output.verifyIdentity()
            muxer.evidence()
        }

    private fun configure(plan: EditPlan, references: List<EditInputRef>, muxer: EditorBoundedMuxer, session: EditorSession) {
        session.checkpoint()
        val factories = EditorNativeFactories(app, plan, session)
        val local = EditorCatalogDataSource(references, session)
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
            if (session.loadersCreated.incrementAndGet() > EditLimits.MAX_RECORDED_SEGMENTS) fail(EditError.SOURCE_LIMIT)
            val sourceIndex = plan.segments.indices.singleOrNull { item.mediaItem.localConfiguration?.uri.toString() == EditorCatalogDataSource.address(it) }
                ?: fail(EditError.INVALID_REQUEST)
            val expected = plan.segments[sourceIndex].metadata
            val wrapped = object : AssetLoader.Listener {
                override fun onDurationUs(durationUs: Long) { session.checkpoint(); listener.onDurationUs(durationUs) }
                override fun onTrackCount(trackCount: Int) {
                    if (trackCount != if (expected.audio == EditAudioProfile.Silent) 1 else 2) fail(EditError.UNSUPPORTED_PROFILE)
                    listener.onTrackCount(trackCount)
                }
                override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean {
                    session.checkpoint()
                    if (inputFormat.drmInitData != null || inputFormat.cryptoType != C.CRYPTO_TYPE_NONE ||
                        supportedOutputTypes and AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED == 0) fail(EditError.UNSUPPORTED_PROFILE)
                    when (inputFormat.sampleMimeType) {
                        MimeTypes.VIDEO_H264 -> if (inputFormat.width != expected.width || inputFormat.height != expected.height ||
                            inputFormat.rotationDegrees != expected.rotationDegrees || inputFormat.pixelWidthHeightRatio != 1f ||
                            ColorInfo.isTransferHdr(inputFormat.colorInfo)) fail(EditError.UNSUPPORTED_PROFILE)
                        MimeTypes.AUDIO_AAC -> {
                            val audio = expected.audio as? EditAudioProfile.Aac ?: fail(EditError.UNSUPPORTED_PROFILE)
                            if (inputFormat.sampleRate != audio.sampleRate || inputFormat.channelCount != audio.channels) fail(EditError.UNSUPPORTED_PROFILE)
                        }
                        else -> fail(EditError.UNSUPPORTED_PROFILE)
                    }
                    if (!listener.onTrackAdded(inputFormat, AssetLoader.SUPPORTED_OUTPUT_TYPE_DECODED)) fail(EditError.UNSUPPORTED_PROFILE)
                    return true
                }
                override fun onOutputFormat(format: Format): SampleConsumer? { session.checkpoint(); return listener.onOutputFormat(format) }
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
        val transformer = Transformer.Builder(app).setLooper(handler.looper).setAssetLoaderFactory(loaders)
            .setEncoderFactory(factories).setVideoFrameProcessorFactory(editorFrameProcessors(session)).setMuxerFactory(muxer)
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setPortraitEncodingEnabled(true).setUsePlatformDiagnostics(false)
            .experimentalSetTrimOptimizationEnabled(false).experimentalSetMp4EditListTrimEnabled(false)
            .experimentalSetMaxFramesInEncoder(5).setMaxDelayBetweenMuxerSamplesMs(EditLimits.STALL_TIMEOUT_MILLIS)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { session.completion.complete(exportResult) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    val unsupported = exportException.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
                        exportException.errorCode == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                    session.completion.completeExceptionally(EditFailure(if (unsupported) EditError.UNSUPPORTED_PROFILE else EditError.EXECUTION_FAILED))
                }
                override fun onFallbackApplied(composition: Composition, originalTransformationRequest: TransformationRequest, fallbackTransformationRequest: TransformationRequest) {
                    session.completion.completeExceptionally(EditFailure(EditError.UNSUPPORTED_PROFILE)); session.abandon()
                }
            }).build()
        session.transformer.set(transformer)
        session.checkpoint()
        val items = plan.segments.mapIndexed { index, planned ->
            val metadata = planned.metadata
            val displayWidth = if (metadata.rotationDegrees % 180 == 0) metadata.width else metadata.height
            val displayHeight = if (metadata.rotationDegrees % 180 == 0) metadata.height else metadata.width
            val crop = planned.displayCrop
            val coordinates = EditorEnginePolicy.ndc(crop.left, crop.top, crop.right, crop.bottom, displayWidth, displayHeight)
            val effects: List<Effect> = listOf(Crop(coordinates[0], coordinates[1], coordinates[2], coordinates[3]),
                Presentation.createForWidthAndHeight(plan.expectation.width, plan.expectation.height, Presentation.LAYOUT_SCALE_TO_FIT))
            val media = MediaItem.Builder().setUri(Uri.parse(EditorCatalogDataSource.address(index))).setMimeType(MimeTypes.VIDEO_MP4)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(planned.executionStartUs)
                    .setEndPositionUs(planned.executionEndUsExclusive).setStartsAtKeyFrame(false).build()).build()
            EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), effects)).build()
        }
        val types = if (plan.expectation.audio == EditAudioProfile.Silent) setOf(C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
        val sequence = EditedMediaItemSequence.Builder(types).addItems(items).build()
        val composition = Composition.Builder(sequence).setTransmuxVideo(false).setTransmuxAudio(false).build()
        transformer.start(composition, session.output!!.file.absolutePath)
    }

    private fun verifyHash(reference: EditInputRef, session: EditorSession) {
        val input = session.openInput(reference)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (count < input.sizeBytes) {
                session.checkpoint()
                val read = input.stream.read(buffer, 0, minOf(buffer.size.toLong(), input.sizeBytes - count).toInt())
                if (read <= 0) fail(EditError.STALE_SOURCE)
                digest.update(buffer, 0, read); count += read
            }
            if (input.stream.read() != -1 || digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) } != input.sha256)
                fail(EditError.STALE_SOURCE)
        } finally { session.closeInput(input) }
    }

    private fun <T> bounded(context: OperationContext, timeoutMillis: Long, output: EditorOwnedOutput?, work: (EditorSession) -> T): T {
        val session = EditorSession(inputs, context, timeoutMillis, handler, output)
        session.checkpoint()
        if (!fence.claim(session)) fail(if (fence.requiresRestart) EditError.RELEASE_UNCONFIRMED else EditError.CAPACITY)
        val result = CompletableFuture<T>()
        try {
            lane.execute {
                var value: T? = null
                var failure: Throwable? = null
                try { value = work(session) } catch (error: Throwable) { failure = if (error is EditFailure) error else EditFailure(EditError.EXECUTION_FAILED) }
                if (session.hasTransformer) EditorSilentDiagnostics.end(session)
                val released = session.finish()
                if (session.hasTransformer) EditorSilentDiagnostics.end(session)
                if (!released || session.closeFailed.get()) { fence.unconfirmed(); failure = EditFailure(EditError.RELEASE_UNCONFIRMED) }
                else fence.release(session)
                if (failure != null) result.completeExceptionally(failure) else { @Suppress("UNCHECKED_CAST") result.complete(value as T) }
            }
        } catch (_: RejectedExecutionException) { fence.release(session); fail(EditError.CAPACITY) }
        try {
            while (true) {
                session.checkpoint()
                try { return result.get(EditorEnginePolicy.POLL_MILLIS, TimeUnit.MILLISECONDS).also { session.checkpoint() } }
                catch (_: TimeoutException) { }
            }
        } catch (error: ExecutionException) { throw (error.cause as? EditFailure ?: EditFailure(EditError.EXECUTION_FAILED)) }
        catch (error: Throwable) {
            session.abandon()
            EditorCleanupWait.awaitCompletion(result, EditLimits.RELEASE_TIMEOUT_MILLIS, error is InterruptedException)
            if (!session.outcomeReleased) { fence.unconfirmed(); fail(EditError.RELEASE_UNCONFIRMED) }
            throw (error as? EditFailure ?: EditFailure(EditError.CANCELLED))
        }
    }
    private companion object {
        val fence = EditorReleaseFence()
        val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { work -> Thread(work, "esv-edit-native").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        val applicationThread by lazy { HandlerThread("esv-edit-control").apply { start() } }
        val handler by lazy { Handler(applicationThread.looper) }
        fun fail(code: EditError): Nothing = throw EditFailure(code)
    }
}
