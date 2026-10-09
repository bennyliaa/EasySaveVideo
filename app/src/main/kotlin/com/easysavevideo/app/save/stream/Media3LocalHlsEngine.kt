package com.easysavevideo.app.save.stream

import android.content.Context
import android.media.metrics.LogSessionId
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.container.NalUnitUtil
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import com.easysavevideo.save.SaveLimits
import com.easysavevideo.save.StreamConservationContract
import com.easysavevideo.save.StreamEndpoint
import com.easysavevideo.save.StreamSampleAccumulator
import com.easysavevideo.save.StreamSampleKind
import com.easysavevideo.save.AvcSampleFraming
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Memory-only paths; the caller owns this private immutable workspace until actual release. */
data class LocalHlsSegment(val id: String, val file: File, val bytes: Long, val sha256: String,
    val durationUs: Long, val startUs: Long) {
    override fun toString() = "LocalHlsSegment([private])"
}
/** expectedAudio is a lower bound: true requires audio; false still permits observed AAC audio. */
class LocalHlsInput(segments: List<LocalHlsSegment>, val totalDurationUs: Long, val expectedAudio: Boolean) {
    val segments: List<LocalHlsSegment> = segments.toList()
    override fun toString() = "LocalHlsInput(segments=${segments.size})"
}
data class LocalHlsEvidence(val bytesOnDisk: Long, val durationUs: Long, val videoSamples: Long,
    val audioSamples: Long, val videoTrackCount: Int, val audioTrackCount: Int, val width: Int, val height: Int,
    val conservation: StreamConservationContract)

/**
 * No network source, decoder or encoder is reachable. A single process-wide occupied lane includes
 * preflight, Transformer, all HLS loader executors and their actual release. Caller timeout merely
 * fences its result. It does not free the lane or permit deletion of the still-owned workspace.
 * Final independent Android MP4 verification remains mandatory at the composition root.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Media3LocalHlsEngine(context: Context) {
    private val app = context.applicationContext
    fun requiresProcessRecovery(): Boolean = isDraining

    fun remux(input: LocalHlsInput, output: File, context: OperationContext, limits: SaveLimits): LocalHlsEvidence {
        active(context)
        if (requiresRestart || !occupied.compareAndSet(false, true)) fail(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        val session = Session(context, limits, output)
        val result = CompletableFuture<LocalHlsEvidence>()
        try {
            lane.execute {
                var evidence: LocalHlsEvidence? = null
                var failure: Throwable? = null
                try { evidence = run(input, session) }
                catch (error: Throwable) { failure = if (error is SaveFailure) error else SaveFailure(SaveError.INVALID_MEDIA) }
                // Publish only after updating lane ownership. A caller may inspect draining as soon
                // as it receives this future; there must be no success/release race.
                if (session.releaseConfirmed()) occupied.set(false)
                else { releaseUnconfirmed.set(true); failure = SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                if (failure != null) result.completeExceptionally(failure) else result.complete(evidence!!)
            }
        } catch (_: RuntimeException) { occupied.set(false); fail(SaveError.BUSY) }
        try {
            while (true) {
                session.checkpoint()
                try {
                    val evidence = result.get(50, TimeUnit.MILLISECONDS)
                    session.checkpoint()
                    return evidence
                } catch (_: TimeoutException) { /* Bounded, interruptible wait. */ }
            }
        } catch (error: ExecutionException) {
            throw (error.cause as? SaveFailure ?: SaveFailure(SaveError.INVALID_MEDIA))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); fail(SaveError.CANCELLED)
        } finally {
            if (!result.isDone) session.abandon()
        }
    }

    private fun run(input: LocalHlsInput, session: Session): LocalHlsEvidence {
        var dataSource: LocalOnlyDataSourceFactory? = null
        try {
            session.checkpoint()
            Log.setLogLevel(Log.LOG_LEVEL_OFF)
            val files = validateInput(input, session)
            files.forEach { it.verifyHash(session::checkpoint) }
            // Raw PSI/PES/NAL/ADTS validation catches things TsExtractor deliberately tolerates or
            // suppresses, including loss, unsupported programs, late SPS/PPS and ADTS changes.
            val raw = try {
                CompatTsProfileProbe.inspectSources(files.map { approved ->
                    CompatTsInput(approved.size) { Channels.newInputStream(approved.open()) }
                }, session::checkpoint)
            }
            catch (failure: CompatTsProfileException) {
                if (failure.reason == CompatTsProfileFailure.RELEASE_UNCONFIRMED) { session.closeFailed.set(true); fail(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                fail(if (failure.reason == CompatTsProfileFailure.PROTECTED) SaveError.PROTECTED_MEDIA else SaveError.UNSUPPORTED_MEDIA)
            }
            if (input.expectedAudio && raw.audioPid == null) fail(SaveError.INVALID_MEDIA)
            dataSource = LocalOnlyDataSourceFactory(localPlaylist(input), files, session::checkpoint)
            session.sources = dataSource
            val compatibility = LocalHlsCompatibility(files.map { AssetResource(it.size, it.sha256, it::verifyIdentity) },
                raw, session::checkpoint)
            val inspection = compatibility.inspection(dataSource)
            session.inspection = inspection
            val observed = inspectSamples(inspection, input, session)
            validateRawTimeline(input, raw, observed.getValue(C.TRACK_TYPE_VIDEO))
            if (observed.getValue(C.TRACK_TYPE_VIDEO).samples != raw.videoAccessUnitCount ||
                (observed[C.TRACK_TYPE_AUDIO]?.samples ?: 0L) != raw.audioFrameCount) fail(SaveError.INVALID_MEDIA)
            session.checkpoint()
            val export = compatibility.export(dataSource)
            session.export = export
            val muxer = CheckedMuxerFactory(observed, session)
            session.muxer = muxer
            val result = startTransformer(input, session, export, observed, muxer)
            session.checkpoint()
            if (result.exportException != null || result.videoEncoderName != null || result.audioEncoderName != null ||
                result.videoConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSMUXED ||
                (observed.containsKey(C.TRACK_TYPE_AUDIO) && result.audioConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSMUXED))
                fail(SaveError.UNSUPPORTED_MEDIA)
            export.verifyComplete()
            muxer.verifyComplete()
            dataSource.verifyFiles()
            // Full rehash prevents a same-size/in-place modification from bypassing the inode guard.
            files.forEach { it.verifyHash(session::checkpoint) }
            if (!session.awaitRelease()) fail(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
            session.checkpoint()
            session.verifyOutputIdentity()
            val bytes = session.output.length()
            if (bytes !in 1..session.limits.maxArtifactBytes) fail(SaveError.BYTE_LIMIT_EXCEEDED)
            val video = observed.getValue(C.TRACK_TYPE_VIDEO)
            val audio = observed[C.TRACK_TYPE_AUDIO]
            return LocalHlsEvidence(bytes, video.presentationEndpointUs, video.samples, audio?.samples ?: 0,
                1, if (audio != null) 1 else 0, video.format!!.width, video.format!!.height, muxer.conservation())
        } finally {
            dataSource?.revoke()
            session.cancelAndRelease()
        }
    }

    private fun validateInput(input: LocalHlsInput, session: Session): List<LocalHlsFile> {
        if (input.segments.size !in 1..20_000 || input.totalDurationUs !in 1..MAX_DURATION_US) fail(SaveError.UNSUPPORTED_MEDIA)
        var totalBytes = 0L
        var expectedStart = 0L
        val ids = HashSet<String>()
        val paths = HashSet<String>()
        val root = app.noBackupFilesDir
        val files = input.segments.map {
            session.checkpoint()
            if (it.id.isBlank() || !ids.add(it.id) || !paths.add(it.file.canonicalPath) || it.bytes <= 0 ||
                it.bytes % TsExtractor.TS_PACKET_SIZE != 0L || it.durationUs !in 1..60_000_000L ||
                it.startUs != expectedStart || it.bytes > session.limits.maxArtifactBytes - totalBytes)
                fail(SaveError.UNSUPPORTED_MEDIA)
            expectedStart += it.durationUs; totalBytes += it.bytes
            LocalHlsFile(it.file, root, it.bytes, it.sha256) { session.closeFailed.set(true) }
        }
        if (expectedStart != input.totalDurationUs) fail(SaveError.INVALID_MEDIA)
        val originalOutput = session.output.toPath().toAbsolutePath()
        val output = originalOutput.normalize()
        if (originalOutput != output) fail(SaveError.POLICY_BLOCKED)
        val attr = Files.readAttributes(output, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!output.startsWith(root.toPath().toRealPath()) || output.toRealPath() != output ||
            !attr.isRegularFile || attr.isSymbolicLink || attr.size() != 0L || paths.contains(output.toString()))
            fail(SaveError.POLICY_BLOCKED)
        session.outputKey = attr.fileKey() ?: fail(SaveError.POLICY_BLOCKED)
        return files
    }

    private fun inspectSamples(pass: LocalHlsCompatibility.InspectionPass, input: LocalHlsInput,
        session: Session): Map<Int, SampleEvidence> {
        val streams = LinkedHashMap<Int, SampleEvidence>()
        // Inspection and HLS export share the same wrapped readers and verified terminal gate.
        val extractor = pass.extractor
        val tracks = HashMap<Int, Int>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) fail(SaveError.UNSUPPORTED_MEDIA)
                val old = tracks[id]
                if (old != null) { if (old != type) fail(SaveError.INVALID_MEDIA); return streams.getValue(type) }
                if (streams.containsKey(type)) fail(SaveError.UNSUPPORTED_MEDIA)
                tracks[id] = type
                return SampleEvidence(type, session::checkpoint).also { streams[type] = it }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        try {
            val holder = PositionHolder()
            for (index in input.segments.indices) {
                session.checkpoint()
                val source = pass.sources.createDataSource()
                try {
                    val length = source.open(DataSpec(Uri.parse(LocalHlsAddress.segment(index))))
                    val extractorInput = DefaultExtractorInput(source, 0, length)
                    while (true) {
                        session.checkpoint()
                        when (extractor.read(extractorInput, holder)) {
                            Extractor.RESULT_END_OF_INPUT -> break
                            Extractor.RESULT_SEEK -> fail(SaveError.UNSUPPORTED_MEDIA)
                        }
                    }
                } finally { source.close() }
            }
            pass.verifyComplete()
        } finally {
            pass.revoke()
            extractor.release()
        }
        if (!streams.containsKey(C.TRACK_TYPE_VIDEO) || (input.expectedAudio && !streams.containsKey(C.TRACK_TYPE_AUDIO)))
            fail(SaveError.INVALID_MEDIA)
        streams.values.forEach { it.finish(input.totalDurationUs) }
        val video = streams.getValue(C.TRACK_TYPE_VIDEO)
        streams[C.TRACK_TYPE_AUDIO]?.let {
            val offset = it.firstUs - video.firstUs
            val audioEnd = it.endpoint()
            val videoEnd = video.endpoint()
            if (abs(offset) > 100_000 ||
                audioEnd.minimumUs + offset > videoEnd.maximumUs + 150_000 ||
                audioEnd.maximumUs + offset < videoEnd.minimumUs - 150_000) fail(SaveError.INVALID_MEDIA)
        }
        return streams
    }

    private fun validateRawTimeline(input: LocalHlsInput, raw: CompatTsProfileEvidence, video: SampleEvidence) {
        // Filled from raw PES timestamps, never from fabricated segment-normalized sample clocks.
        val span = (raw.lastVideoPts90Khz - raw.firstVideoPts90Khz) * 1_000_000L / 90_000L
        // PTS span deliberately excludes the final display interval. The independently observed
        // sample cadence supplies a bounded endpoint, while raw timestamps bind the two scans.
        if (span <= 0 || abs(span - (video.lastUs - video.firstUs)) > 2 ||
            abs(raw.firstVideoPts90Khz * 1_000_000L / 90_000L - video.firstUs) > 1 ||
            !video.endpoint().contains(input.totalDurationUs, 350_000) || raw.segments.size != input.segments.size)
            fail(SaveError.INVALID_MEDIA)
        raw.segments.forEachIndexed { index, segment ->
            val start = (segment.firstVideoPts90Khz - raw.firstVideoPts90Khz) * 1_000_000L / 90_000L
            if (abs(start - input.segments[index].startUs) > 100_000) fail(SaveError.INVALID_MEDIA)
            if (index + 1 < raw.segments.size) {
                val actualDuration = (raw.segments[index + 1].firstVideoPts90Khz - segment.firstVideoPts90Khz) * 1_000_000L / 90_000L
                if (abs(actualDuration - input.segments[index].durationUs) > 100_000) fail(SaveError.INVALID_MEDIA)
            }
        }
    }

    private fun startTransformer(input: LocalHlsInput, session: Session, export: LocalHlsCompatibility.ExportPass,
        observed: Map<Int, SampleEvidence>, muxer: CheckedMuxerFactory): ExportResult {
        val completion = CompletableFuture<ExportResult>()
        session.transformCompletion = completion
        if (!handler.post {
            try {
                session.checkpoint()
                // Media3 logs can include parser paths and exception text. Disable globally before
                // library instantiation; no URL, media title, file name or exception is logged here.
                Log.setLogLevel(Log.LOG_LEVEL_OFF)
                val hls = export.mediaSourceFactory()
                    .setDrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                    .setDownloadExecutor { session.newLoaderExecutor() }
                val mediaSources = object : MediaSource.Factory by hls {
                    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                        val source = hls.createMediaSource(mediaItem)
                        return object : MediaSource by source {
                            override fun prepareSource(caller: MediaSource.MediaSourceCaller, playerId: PlayerId,
                                bandwidthMeter: BandwidthMeter) {
                                session.threads.add(Thread.currentThread())
                                session.playbackThreads.add(Thread.currentThread())
                                source.prepareSource(caller, playerId, bandwidthMeter)
                            }
                        }
                    }
                }
                val load = DefaultLoadControl.Builder().setTargetBufferBytes(16 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false).setBufferDurationsMs(1000, 5000, 100, 100).build()
                val base = ExoPlayerAssetLoader.Factory(app, RejectCodecs, Clock.DEFAULT, mediaSources, null, null, load)
                val loaders = AssetLoader.Factory { item, looper, listener, settings ->
                    session.threads.add(looper.thread)
                    val wrapped = object : AssetLoader.Listener {
                        override fun onDurationUs(durationUs: Long) { session.checkpoint(); listener.onDurationUs(durationUs) }
                        override fun onTrackCount(trackCount: Int) {
                            if (trackCount != observed.size) fail(SaveError.INVALID_MEDIA)
                            listener.onTrackCount(trackCount)
                        }
                        override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean {
                            checkObservedFormat(inputFormat, observed)
                            if (supportedOutputTypes and AssetLoader.SUPPORTED_OUTPUT_TYPE_ENCODED == 0) fail(SaveError.UNSUPPORTED_MEDIA)
                            if (listener.onTrackAdded(inputFormat, AssetLoader.SUPPORTED_OUTPUT_TYPE_ENCODED)) fail(SaveError.UNSUPPORTED_MEDIA)
                            return false
                        }
                        override fun onOutputFormat(format: Format): androidx.media3.transformer.SampleConsumer? {
                            checkObservedFormat(format, observed); return listener.onOutputFormat(format)
                        }
                        override fun onError(exportException: ExportException) { listener.onError(exportException) }
                    }
                    session.loadersCreated.incrementAndGet()
                    try { base.createAssetLoader(item, looper, wrapped, settings) }
                    catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
                }
                val transformer = Transformer.Builder(app).setLooper(handler.looper).setAssetLoaderFactory(loaders)
                    .setEncoderFactory(RejectCodecs).setMuxerFactory(muxer).setUsePlatformDiagnostics(false)
                    .setMaxDelayBetweenMuxerSamplesMs(session.limits.noProgressTimeoutMillis.toLong())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { completion.complete(exportResult) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            completion.completeExceptionally(SaveFailure(SaveError.INVALID_MEDIA))
                        }
                    }).build()
                session.transformer.set(transformer)
                session.checkpoint()
                val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.parse(LocalHlsAddress.PLAYLIST))
                    .setMimeType(MimeTypes.APPLICATION_M3U8).build()).build()
                val types = observed.keys
                val composition = Composition.Builder(EditedMediaItemSequence.Builder(types).addItem(item).build())
                    .setTransmuxVideo(true).setTransmuxAudio(true).build()
                transformer.start(composition, session.output.absolutePath)
            } catch (_: Throwable) { completion.completeExceptionally(SaveFailure(SaveError.UNSUPPORTED_MEDIA)) }
        }) fail(SaveError.VERIFICATION_UNAVAILABLE)
        while (true) {
            session.checkpoint()
            try { return completion.get(50, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) {}
            catch (error: ExecutionException) { throw (error.cause as? SaveFailure ?: SaveFailure(SaveError.INVALID_MEDIA)) }
        }
    }

    private class Session(val context: OperationContext, val limits: SaveLimits, val output: File) {
        val abandoned = AtomicBoolean(false)
        val closeFailed = AtomicBoolean(false)
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        val playbackThreads = ConcurrentHashMap.newKeySet<Thread>()
        val loadersCreated = AtomicInteger(0)
        val executors = java.util.Collections.synchronizedList(ArrayList<ThreadPoolExecutor>())
        val transformer = AtomicReference<Transformer?>()
        var transformCompletion: CompletableFuture<ExportResult>? = null
        var sources: LocalOnlyDataSourceFactory? = null
        @Volatile var inspection: LocalHlsCompatibility.InspectionPass? = null
        @Volatile var export: LocalHlsCompatibility.ExportPass? = null
        var muxer: CheckedMuxerFactory? = null
        var outputKey: Any? = null
        fun verifyOutputIdentity() {
            val original = output.toPath().toAbsolutePath()
            val path = original.normalize()
            if (original != path) fail(SaveError.POLICY_BLOCKED)
            val attr = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (outputKey == null || attr.fileKey() != outputKey || !attr.isRegularFile || attr.isSymbolicLink ||
                path.toRealPath() != path) fail(SaveError.RESOURCE_CHANGED)
        }
        private val cancelPosted = AtomicBoolean(false)
        private val cancelReturned = CompletableFuture<Boolean>()
        private val stopNanos = System.nanoTime() + minOf(limits.verificationTimeoutMillis,
            (context.deadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(1)) * 1_000_000L
        fun checkpoint() {
            if (abandoned.get()) fail(SaveError.CANCELLED)
            active(context)
            if (System.nanoTime() >= stopNanos) fail(SaveError.DEADLINE_EXCEEDED)
        }
        fun abandon() { abandoned.set(true); inspection?.revoke(); export?.revoke(); sources?.revoke(); postCancel() }
        fun newLoaderExecutor(): ReleasableExecutor {
            checkpoint()
            synchronized(executors) {
                if (executors.size >= 4) fail(SaveError.UNSUPPORTED_MEDIA)
                val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
                    { job -> Thread(job, "esv-hls-owned-loader").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
                executors.add(executor)
                return ReleasableExecutor.from(executor) { it.shutdown() }
            }
        }
        fun postCancel() {
            if (cancelPosted.compareAndSet(false, true)) {
                if (!handler.post {
                    try { transformer.getAndSet(null)?.cancel(); cancelReturned.complete(true) }
                    catch (_: Throwable) { closeFailed.set(true); cancelReturned.complete(false) }
                }) { closeFailed.set(true); cancelReturned.complete(false) }
            }
        }
        fun cancelAndRelease() {
            inspection?.revoke(); export?.revoke(); sources?.revoke(); postCancel()
            if (!awaitRelease()) releaseUnconfirmed.set(true)
        }
        fun awaitRelease(): Boolean {
            // Release has a separate short grace window; cancellation cannot erase ownership.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < until) {
                if (releaseConfirmed()) return true
                try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return releaseConfirmed()
        }
        fun releaseConfirmed(): Boolean {
            if (closeFailed.get() || sources?.closeUnconfirmed?.get() == true || (sources?.openHandles?.get() ?: 0) != 0)
                return false
            if (transformer.get() != null && transformCompletion?.isDone != true) return false
            if (cancelPosted.get() && (!cancelReturned.isDone || cancelReturned.getNow(false) != true)) return false
            if (muxer?.isReleased() == false) return false
            // ExoPlayer's release timeout is suppressed by ExoPlayerAssetLoader. A loader whose
            // playback thread was never observed (e.g. early cancellation) cannot prove release.
            if (playbackThreads.size < loadersCreated.get() || threads.any { it.isAlive }) return false
            return synchronized(executors) { executors.all { it.isTerminated } }
        }
    }

    private class CheckedMuxerFactory(private val expected: Map<Int, SampleEvidence>, private val session: Session) : Muxer.Factory {
        private val delegate = DefaultMuxer.Factory()
        private val released = AtomicBoolean(false)
        private val created = AtomicBoolean(false)
        private val tracks = LinkedHashMap<Int, SampleEvidence>()
        private var bytes = 0L
        override fun getSupportedSampleMimeTypes(trackType: Int) = delegate.getSupportedSampleMimeTypes(trackType)
        override fun supportsWritingNegativeTimestampsInEditList() = false
        override fun create(path: String): Muxer {
            session.checkpoint()
            if (path != session.output.absolutePath || !created.compareAndSet(false, true)) fail(SaveError.POLICY_BLOCKED)
            session.verifyOutputIdentity()
            val boundedOutput = BoundedMuxerOutput(session)
            val muxer = try {
                Mp4Muxer.Builder(boundedOutput).setSampleBatchingEnabled(false)
                    .setAttemptStreamableOutputEnabled(false).build()
            } catch (failure: Throwable) { boundedOutput.close(); throw failure }
            return object : Muxer {
                override fun addTrack(format: Format): Int {
                    session.checkpoint(); checkObservedFormat(format, expected)
                    val type = trackType(format)
                    if (tracks.values.any { it.type == type }) fail(SaveError.INVALID_MEDIA)
                    val id = muxer.addTrack(format)
                    tracks[id] = SampleEvidence(type, session::checkpoint).also { it.format(format) }
                    return id
                }
                override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
                    session.checkpoint()
                    val track = tracks[trackId] ?: fail(SaveError.INVALID_MEDIA)
                    if (bufferInfo.size != byteBuffer.remaining() || bufferInfo.size !in 1..MAX_SAMPLE_BYTES ||
                        bufferInfo.size > session.limits.maxArtifactBytes - bytes || session.output.length() > session.limits.maxArtifactBytes)
                        fail(SaveError.BYTE_LIMIT_EXCEEDED)
                    bytes += bufferInfo.size
                    track.record(bufferInfo.presentationTimeUs, bufferInfo.flags, byteBuffer.duplicate())
                    if (track.samples > expected.getValue(track.type).samples) fail(SaveError.INVALID_MEDIA)
                    muxer.writeSampleData(trackId, byteBuffer, bufferInfo)
                }
                override fun addMetadataEntry(metadataEntry: Metadata.Entry) { /* No source metadata propagation. */ }
                override fun close() {
                    try { muxer.close(); session.verifyOutputIdentity() }
                    finally {
                        // Mp4Muxer catches IOExceptions, but cancellation/other unchecked errors
                        // can otherwise bypass its internal close. Always close our owned output.
                        try { boundedOutput.close(); released.set(true) }
                        catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
                    }
                }
            }
        }
        fun isReleased() = !created.get() || released.get()
        fun conservation(): StreamConservationContract {
            if (!released.get()) fail(SaveError.INVALID_MEDIA)
            val video = tracks.values.single { it.type == C.TRACK_TYPE_VIDEO }
            val audio = tracks.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
            // Durable expected ticks originate at the ACTUAL mux input, after the existing exact
            // preflight/mux byte and relative timestamp proof. No clock is rewritten here.
            return StreamConservationContract(video.proof.finish(), audio?.proof?.finish(),
                audio?.let { it.firstUs - video.firstUs }, video.proof.videoTail(), video.endpoint(), audio?.format?.channelCount)
        }
        fun verifyComplete() {
            if (!released.get() || tracks.values.map { it.type }.toSet() != expected.keys) fail(SaveError.INVALID_MEDIA)
            for (track in tracks.values) {
                val original = expected.getValue(track.type)
                if (track.samples != original.samples || track.payloadBytes != original.payloadBytes ||
                    abs((track.lastUs - track.firstUs) - (original.lastUs - original.firstUs)) > 2 ||
                    !track.digest.digest().contentEquals(original.digest.digest())) fail(SaveError.INVALID_MEDIA)
            }
            val video = tracks.values.single { it.type == C.TRACK_TYPE_VIDEO }
            tracks.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.let { audio ->
                val originalOffset = expected.getValue(C.TRACK_TYPE_AUDIO).firstUs - expected.getValue(C.TRACK_TYPE_VIDEO).firstUs
                if (abs((audio.firstUs - video.firstUs) - originalOffset) > 2) fail(SaveError.INVALID_MEDIA)
            }
        }
    }

    /** Enforces the output byte budget on headers, sample bytes, padding and final tables alike. */
    private class BoundedMuxerOutput(private val session: Session) : SeekableMuxerOutput {
        private val channel = FileChannel.open(session.output.toPath().toAbsolutePath().normalize(),
            setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
        private val closed = AtomicBoolean(false)
        init {
            try { session.verifyOutputIdentity(); if (channel.size() != 0L) fail(SaveError.RESOURCE_CHANGED) }
            catch (failure: Throwable) { close(); throw failure }
        }
        private fun check() {
            try { session.checkpoint() } catch (_: SaveFailure) { throw IOException("LOCAL_EXPORT_STOPPED") }
        }
        override fun write(source: ByteBuffer): Int {
            check()
            if (!outputRangeAllowed(channel.position(), source.remaining().toLong(), session.limits.maxArtifactBytes))
                throw IOException("LOCAL_OUTPUT_LIMIT")
            val count = minOf(source.remaining(), 64 * 1024)
            val chunk = source.duplicate().apply { limit(position() + count) }
            val actual = channel.write(chunk)
            source.position(source.position() + actual)
            return actual
        }
        override fun getPosition(): Long { check(); return channel.position() }
        override fun setPosition(position: Long) {
            check()
            if (!outputRangeAllowed(position, 0, session.limits.maxArtifactBytes)) throw IOException("LOCAL_OUTPUT_LIMIT")
            channel.position(position)
        }
        override fun getSize(): Long { check(); return channel.size() }
        override fun truncate(size: Long) {
            check()
            if (!outputRangeAllowed(size, 0, session.limits.maxArtifactBytes)) throw IOException("LOCAL_OUTPUT_LIMIT")
            channel.truncate(size)
        }
        override fun isOpen() = channel.isOpen
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try { channel.force(true) }
                finally {
                    try { channel.close() }
                    catch (failure: Throwable) { session.closeFailed.set(true); throw failure }
                }
            }
        }
    }

    private class SampleEvidence(val type: Int, private val checkpoint: () -> Unit) : TrackOutput {
        var format: Format? = null; private set
        var samples = 0L; private set
        var payloadBytes = 0L; private set
        var firstUs = C.TIME_UNSET; private set
        var lastUs = C.TIME_UNSET; private set
        private var lastDeltaUs = 0L
        lateinit var proof: StreamSampleAccumulator; private set
        // Pinned Mp4Muxer duplicates the previous display interval for >=3 samples. VFR remains
        // an interval in the durable evidence; this representative duration is only a metadata hint.
        val presentationEndpointUs: Long get() = lastUs - firstUs +
            if (type == C.TRACK_TYPE_AUDIO) 1_024_000_000L / format!!.sampleRate else lastDeltaUs
        fun endpoint(): StreamEndpoint = proof.endpoint()
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        private var pending = ByteArray(64 * 1024)
        private var pendingSize = 0
        override fun format(format: Format) {
            validateFormat(format)
            if (trackType(format) != type || (this.format != null && !sameFormat(this.format!!, format))) fail(SaveError.UNSUPPORTED_MEDIA)
            if (this.format == null) proof = StreamSampleAccumulator(
                if (type == C.TRACK_TYPE_VIDEO) StreamSampleKind.AVC else StreamSampleKind.AAC,
                if (type == C.TRACK_TYPE_VIDEO) 90_000 else format.sampleRate)
            this.format = format
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            checkpoint(); checkPart(sampleDataPart); reserve(minOf(length, 64 * 1024))
            val count = input.read(pending, pendingSize, minOf(length, 64 * 1024))
            if (count < 0 && !allowEndOfInput) fail(SaveError.INCOMPLETE_TRANSFER)
            if (count > 0) pendingSize += count
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            checkpoint(); checkPart(sampleDataPart); reserve(length)
            data.readBytes(pending, pendingSize, length); pendingSize += length
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            checkpoint()
            if (cryptoData != null || size !in 1..MAX_SAMPLE_BYTES || offset < 0 || size > pendingSize - offset)
                fail(SaveError.INVALID_MEDIA)
            val start = pendingSize - offset - size
            record(timeUs, flags, ByteBuffer.wrap(pending, start, size))
            pending.copyInto(pending, 0, pendingSize - offset, pendingSize); pendingSize = offset
        }
        fun record(timeUs: Long, flags: Int, bytes: ByteBuffer) {
            checkpoint()
            if (format == null || timeUs < 0 || flags and C.BUFFER_FLAG_ENCRYPTED != 0 ||
                (samples > 0 && (timeUs <= lastUs || timeUs - lastUs > 250_000)) || ++samples > 250_000)
                fail(SaveError.INVALID_MEDIA)
            if (samples == 1L) {
                firstUs = timeUs
                if (type == C.TRACK_TYPE_VIDEO && flags and C.BUFFER_FLAG_KEY_FRAME == 0) fail(SaveError.INVALID_MEDIA)
            }
            if (samples > 1) lastDeltaUs = timeUs - lastUs
            lastUs = timeUs; payloadBytes += bytes.remaining()
            proof.add(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME, bytes.duplicate(), AvcSampleFraming.ANNEX_B)
            // Bind EVERY original relative PTS and key-frame flag as well as payload boundaries.
            // This only normalizes evidence against one fixed track origin; it never rewrites a
            // sample timestamp. AV origin offset is checked separately by the muxer verifier.
            digest.update(sampleProofHeader(bytes.remaining(), timeUs - firstUs, flags)); digest.update(bytes)
        }
        fun finish(durationUs: Long) {
            if (format == null || samples < 3 || lastUs <= firstUs ||
                !endpoint().contains(durationUs, 350_000)) fail(SaveError.INVALID_MEDIA)
        }
        private fun checkPart(part: Int) { if (part != TrackOutput.SAMPLE_DATA_PART_MAIN) fail(SaveError.PROTECTED_MEDIA) }
        private fun reserve(extra: Int) {
            if (extra < 0 || extra > MAX_SAMPLE_BYTES * 2 - pendingSize) fail(SaveError.UNSUPPORTED_MEDIA)
            if (pending.size < pendingSize + extra) pending = pending.copyOf(minOf(MAX_SAMPLE_BYTES * 2, maxOf(pending.size * 2, pendingSize + extra)))
        }
    }

    private object RejectCodecs : Codec.EncoderFactory, Codec.DecoderFactory {
        private fun reject(): Nothing = throw ExportException.createForUnexpected(IllegalStateException("TRANSMUX_ONLY"))
        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec = reject()
        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec = reject()
        override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec = reject()
        override fun createForVideoDecoding(format: Format, outputSurface: Surface, requestSdrToneMapping: Boolean,
            logSessionId: LogSessionId?): Codec = reject()
    }

    companion object {
        private const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
        private const val MAX_DURATION_US = 24L * 60 * 60 * 1_000_000
        private val occupied = AtomicBoolean(false)
        private val releaseUnconfirmed = AtomicBoolean(false)
        val requiresRestart: Boolean get() = releaseUnconfirmed.get()
        val isDraining: Boolean get() = occupied.get() || requiresRestart
        private val lane = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { work -> Thread(work, "esv-local-hls-engine").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private val applicationThread by lazy { HandlerThread("esv-local-hls-control").apply { start() } }
        private val handler by lazy { Handler(applicationThread.looper) }
        private fun active(context: OperationContext) {
            if (context.cancelToken.isCancelled || Thread.currentThread().isInterrupted) fail(SaveError.CANCELLED)
            if (System.currentTimeMillis() >= context.deadlineEpochMillis) fail(SaveError.DEADLINE_EXCEEDED)
        }
        private fun fail(code: SaveError): Nothing = throw SaveFailure(code)
        private fun trackType(format: Format): Int = when (format.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> C.TRACK_TYPE_VIDEO
            MimeTypes.AUDIO_AAC -> C.TRACK_TYPE_AUDIO
            else -> fail(SaveError.UNSUPPORTED_MEDIA)
        }
        private fun validateFormat(format: Format) {
            if (format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE) fail(SaveError.PROTECTED_MEDIA)
            if (format.initializationData.isEmpty() || format.initializationData.sumOf { it.size } > 64 * 1024)
                fail(SaveError.UNSUPPORTED_MEDIA)
            if (trackType(format) == C.TRACK_TYPE_VIDEO) {
                if (format.width !in 1..4096 || format.height !in 1..4096 || format.width.toLong() * format.height > 8_847_360 ||
                    format.rotationDegrees != 0 || format.pixelWidthHeightRatio != 1f) fail(SaveError.UNSUPPORTED_MEDIA)
                val sps = format.initializationData.firstOrNull { avcSpsOffset(it) != null }
                    ?: fail(SaveError.UNSUPPORTED_MEDIA)
                // Media3's TsExtractor/NalUnitTargetBuffer produces a three-byte start code;
                // accept the equally valid four-byte form without shifting the SPS header.
                val parsed = NalUnitUtil.parseSpsNalUnit(sps, avcSpsOffset(sps)!!, sps.size)
                if (parsed.profileIdc != 66 || !parsed.frameMbsOnlyFlag || parsed.width != format.width || parsed.height != format.height)
                    fail(SaveError.UNSUPPORTED_MEDIA)
            } else {
                if (format.initializationData.size != 1 || format.codecs != "mp4a.40.2" ||
                    format.channelCount !in 1..2 || format.sampleRate !in 8_000..96_000) fail(SaveError.UNSUPPORTED_MEDIA)
                val config = AacUtil.parseAudioSpecificConfig(format.initializationData[0])
                if (config.codecs != "mp4a.40.2" || config.channelCount != format.channelCount || config.sampleRateHz != format.sampleRate)
                    fail(SaveError.UNSUPPORTED_MEDIA)
            }
        }
        internal fun sampleProofHeader(size: Int, relativeTimeUs: Long, flags: Int): ByteArray =
            ByteBuffer.allocate(16).putInt(size).putLong(relativeTimeUs).putInt(flags and C.BUFFER_FLAG_KEY_FRAME).array()
        internal fun outputRangeAllowed(position: Long, bytes: Long, limit: Long): Boolean =
            position >= 0 && bytes >= 0 && limit > 0 && position <= limit && bytes <= limit - position
        internal fun avcSpsOffset(bytes: ByteArray): Int? {
            val offset = when {
                bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> 3
                bytes.size > 5 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte() -> 4
                else -> return null
            }
            return offset.takeIf { bytes[it].toInt() and 0x9f == 7 }
        }
        private fun sameFormat(a: Format, b: Format): Boolean = a.sampleMimeType == b.sampleMimeType && a.codecs == b.codecs &&
            a.width == b.width && a.height == b.height && a.rotationDegrees == b.rotationDegrees &&
            a.pixelWidthHeightRatio == b.pixelWidthHeightRatio && a.channelCount == b.channelCount && a.sampleRate == b.sampleRate &&
            a.colorInfo == b.colorInfo && a.initializationData.size == b.initializationData.size &&
            a.initializationData.zip(b.initializationData).all { (x, y) -> x.contentEquals(y) }
        private fun checkObservedFormat(format: Format, observed: Map<Int, SampleEvidence>) {
            validateFormat(format)
            val original = observed[trackType(format)]?.format ?: fail(SaveError.INVALID_MEDIA)
            if (!sameFormat(original, format)) fail(SaveError.UNSUPPORTED_MEDIA)
        }
        internal fun localPlaylist(input: LocalHlsInput): ByteArray = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n")
            val target = (input.segments.maxOf { it.durationUs } + 999_999) / 1_000_000
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            input.segments.forEachIndexed { index, segment ->
                append("#EXTINF:").append(segment.durationUs / 1_000_000).append('.')
                    .append((segment.durationUs % 1_000_000).toString().padStart(6, '0')).append(",\n")
                append(LocalHlsAddress.segment(index)).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }.toByteArray(Charsets.US_ASCII)
    }
}
