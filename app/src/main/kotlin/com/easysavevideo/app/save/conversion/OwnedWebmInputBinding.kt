package com.easysavevideo.app.save.conversion

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.easysavevideo.app.media.transform.TransformerOwnedSource
import com.easysavevideo.app.media.transform.TransformerReadHandle
import com.easysavevideo.app.media.transform.TransformerSession
import com.easysavevideo.app.save.storage.OwnedDirectTranscodeAttempt
import java.io.IOException
import java.util.UUID

/** Registered input only. No independently supplied source, target, factory, timeline or facts. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class OwnedWebmInputBinding private constructor(private val attempt: OwnedDirectTranscodeAttempt) {
    private val lock = Any()
    private val route = "esv-webm-owned:" + UUID.randomUUID().toString()
    private var failed = false
    private var firstBoundaryFailure: Throwable? = null
    private var probeStarted = false
    private var sourceIssued = false
    private var dataSourceIssued = false
    private var extractorIssued = false
    private var expected: WebmTransportFacts? = null
    private var completed: WebmTransportFacts? = null
    private var shared: WebmInputBinding? = null
    private var session: TransformerSession? = null
    private var source: TransformerOwnedSource? = null
    private var dataSource: PairedDataSource? = null
    private var extractor: OwnedWebmProgressiveExtractor? = null
    private var sequential: SequentialRead? = null
    private var pending: Receipt? = null

    companion object {
        /** Inert; only the owner may register this exact object before any operation can succeed. */
        internal fun createUnregistered(attempt: OwnedDirectTranscodeAttempt) = OwnedWebmInputBinding(attempt)
    }
    private fun healthy() { synchronized(lock) { requireWebm(!failed, WebmProbeFailure.INCOMPLETE) } }
    private fun registered() { attempt.requireRegisteredBinding(this); healthy() }
    private fun inputCheckpoint() {
        if (synchronized(lock) { session != null }) checkpoint() else registered()
    }
    internal fun checkpoint() {
        registered()
        val current = synchronized(lock) { session } ?: throw WebmProbeException(WebmProbeFailure.INCOMPLETE)
        attempt.requireRegisteredSession(this, current)
        current.requireEntered()
        healthy()
    }
    internal fun failClosed() { synchronized(lock) { failed = true; completed = null } }
    private fun originalBoundaryFailure(failure: Throwable): Throwable {
        val original = if (failure is WebmCallerFailure) failure.original else failure
        synchronized(lock) { if (firstBoundaryFailure == null) firstBoundaryFailure = original }
        // Pull-based, exact registered pair provenance; no caller can submit a Throwable to the owner.
        attempt.captureInputBoundaryFailure(this)
        failClosed()
        return original
    }
    internal fun boundaryFailureFor(candidate: OwnedDirectTranscodeAttempt): Throwable? {
        check(candidate === attempt)
        return synchronized(lock) { firstBoundaryFailure }
    }
    internal fun <T> boundary(action: () -> T): T = try { action() }
    catch (failure: Throwable) { throw originalBoundaryFailure(failure) }

    fun probe(): WebmTransportFacts = boundary {
        registered()
        synchronized(lock) { requireWebm(!probeStarted && !sourceIssued, WebmProbeFailure.INCOMPLETE); probeStarted = true }
        val binding = WebmInputBinding(attempt.borrowedWebmInput(this), attempt.webmLimits(this), attempt.webmBudget(this), ::inputCheckpoint)
        synchronized(lock) { shared = binding }
        val parser = WebmExtractorFactory(binding).create()
        try {
            parserBoundary {
                val input = parser.newInput()
                requireWebm(parser.sniff(input), WebmProbeFailure.TRACK_PROFILE)
                input.resetPeekPosition(); parser.init(DiscardOutput)
                val holder = PositionHolder()
                while (parser.read(input, holder) != Extractor.RESULT_END_OF_INPUT) binding.check()
                val facts = parser.finish()
                requireWebm(webmCaller { attempt.expectedAudio(this) } != true || facts.tracks.any { it.trackType == C.TRACK_TYPE_AUDIO }, WebmProbeFailure.TRACK_INVENTORY)
                webmCaller { registered() }
                synchronized(lock) { healthy(); expected = facts }
                facts
            }
        } finally { parser.release() }
    }

    /** One fixed MediaItem and both private factories; assembly itself never retains or opens IO. */
    fun createMediaSource(session: TransformerSession): MediaSource = boundary {
        registered(); session.requireEntered()
        synchronized(lock) {
            requireWebm(!sourceIssued && expected != null, WebmProbeFailure.INCOMPLETE)
            sourceIssued = true
        }
        attempt.bindSession(this, session)
        synchronized(lock) { this.session = session }
        checkpoint()
        val owned = attempt.ownedSource(this)
        synchronized(lock) { source = owned }
        val sourceFactory = DataSource.Factory { newDataSource() }
        val extractorFactory = ProgressiveMediaExtractor.Factory { newExtractor() }
        ProgressiveMediaSource.Factory(sourceFactory, extractorFactory)
            .setDrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }
            .setLoadErrorHandlingPolicy(NoRetry)
            .setDownloadExecutor { session.newDownloadExecutor() }
            .createMediaSource(MediaItem.Builder().setUri(route).setMimeType("video/webm").build())
    }

    private fun newDataSource(): DataSource = boundary {
        checkpoint()
        synchronized(lock) {
            requireWebm(!dataSourceIssued, WebmProbeFailure.INCOMPLETE)
            dataSourceIssued = true
            PairedDataSource().also { dataSource = it }
        }
    }
    private fun newExtractor(): ProgressiveMediaExtractor = boundary {
        checkpoint()
        synchronized(lock) {
            requireWebm(!extractorIssued, WebmProbeFailure.INCOMPLETE)
            extractorIssued = true
            OwnedWebmProgressiveExtractor(this, requireNotNull(shared)).also { extractor = it }
        }
    }
    internal fun sequentialInput(adapter: OwnedWebmProgressiveExtractor, reader: DataReader, uri: Uri,
        headers: Map<String, List<String>>, position: Long, length: Long): WebmSequentialInput = boundary {
        checkpoint()
        synchronized(lock) {
            requireWebm(adapter === extractor && sequential == null && uri.toString() == route &&
                headers.isEmpty() && position == 0L && length == shared?.extent && dataSource?.opened == true,
                WebmProbeFailure.FOREIGN_INPUT)
            SequentialRead(reader, requireNotNull(dataSource)).also { sequential = it }
        }
    }
    internal fun completed(adapter: OwnedWebmProgressiveExtractor, facts: WebmTransportFacts) = boundary {
        checkpoint()
        synchronized(lock) {
            requireWebm(adapter === extractor && completed == null && pending == null, WebmProbeFailure.INCOMPLETE)
            requireWebm(sameFacts(requireNotNull(expected), facts), WebmProbeFailure.IDENTITY)
            completed = facts
        }
        checkpoint()
    }
    fun completedTransportFacts(): WebmTransportFacts = boundary {
        checkpoint()
        synchronized(lock) {
            requireWebm(pending == null && dataSource?.releaseConfirmed == true, WebmProbeFailure.INCOMPLETE)
            completed ?: throw WebmProbeException(WebmProbeFailure.INCOMPLETE)
        }
    }

    private class Receipt(val source: PairedDataSource, val position: Long, val buffer: ByteArray,
        val offset: Int, val requested: Int, val thread: Thread) {
        var consumed = false
        var returned: Int? = null
    }
    private inner class SequentialRead(private val framework: DataReader, private val routeSource: PairedDataSource) : WebmSequentialInput(), DataReader {
        private var claimed = false
        private var position = 0L
        override fun claim(binding: WebmInputBinding): DataReader = webmCaller {
            checkpoint()
            synchronized(lock) {
                requireWebm(sequential === this && shared === binding && !claimed, WebmProbeFailure.FOREIGN_INPUT)
                claimed = true; this
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try { webmCaller {
            checkpoint()
            val binding = requireNotNull(shared)
            requireWebm(offset >= 0 && length >= 0 && offset <= buffer.size && length <= buffer.size - offset &&
                length <= binding.limits.maxSampleBytes, WebmProbeFailure.READ_REQUEST)
            val receipt = synchronized(lock) {
                requireWebm(claimed && sequential === this && pending == null && routeSource.opened &&
                    routeSource.position == position, WebmProbeFailure.FOREIGN_INPUT)
                Receipt(routeSource, position, buffer, offset, length, Thread.currentThread()).also { pending = it }
            }
            try {
                // The pinned StatsDataSource must actually forward this exact call to our source.
                val count = webmCaller { framework.read(buffer, offset, length) }
                checkpoint()
                synchronized(lock) {
                    requireWebm(pending === receipt && receipt.consumed && receipt.returned == count,
                        WebmProbeFailure.FOREIGN_INPUT)
                    requireWebm(count > 0 || length == 0 || (count == C.RESULT_END_OF_INPUT && position == binding.extent),
                        if (count == 0) WebmProbeFailure.ZERO_PROGRESS else WebmProbeFailure.TRUNCATED)
                    requireWebm(count <= length, WebmProbeFailure.READ_REQUEST)
                    if (count > 0) position += count
                }
                count
            } finally { synchronized(lock) { if (pending === receipt) pending = null } }
        } } catch (failure: Throwable) {
            originalBoundaryFailure(failure) // Remember the original, but keep parser transport tagged.
            throw failure // parserBoundary alone unwraps WebmCallerFailure after Matroska returns.
        }
    }
    private inner class PairedDataSource : DataSource {
        var opened = false; private set
        var releaseConfirmed = false; private set
        var position = 0L; private set
        private var tried = false
        private var input: TransformerReadHandle? = null
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun getUri(): Uri? = synchronized(lock) { if (opened) Uri.parse(route) else null }
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun open(dataSpec: DataSpec): Long = boundary {
            checkpoint()
            requireWebm(dataSpec.uri.toString() == route && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
                dataSpec.httpBody == null && dataSpec.position == 0L && dataSpec.uriPositionOffset == 0L &&
                dataSpec.length == C.LENGTH_UNSET.toLong() && dataSpec.key == null && dataSpec.customData == null &&
                dataSpec.flags == 6 && dataSpec.httpRequestHeaders == mapOf("Icy-MetaData" to "1"), WebmProbeFailure.FOREIGN_INPUT)
            synchronized(lock) { requireWebm(dataSource === this && !tried, WebmProbeFailure.INCOMPLETE); tried = true }
            val current = requireNotNull(session); val owned = requireNotNull(source)
            current.retain(owned)
            val openedInput = current.openInput(owned)
            synchronized(lock) { input = openedInput }
            try {
                openedInput.verifyIdentity(); checkpoint()
                requireWebm(openedInput.extent == shared?.extent, WebmProbeFailure.IDENTITY)
                synchronized(lock) { opened = true }
                openedInput.extent
            } catch (failure: Throwable) {
                val original = originalBoundaryFailure(failure)
                try { close() } catch (_: Throwable) { failClosed() }
                throw original
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = boundary {
            checkpoint()
            val receipt: Receipt
            val handle: TransformerReadHandle
            synchronized(lock) {
                receipt = pending ?: throw WebmProbeException(WebmProbeFailure.FOREIGN_INPUT)
                requireWebm(opened && receipt.source === this && receipt.position == position && receipt.buffer === buffer &&
                    receipt.offset == offset && receipt.requested == length && receipt.thread === Thread.currentThread() && !receipt.consumed,
                    WebmProbeFailure.FOREIGN_INPUT)
                receipt.consumed = true
                handle = input ?: throw WebmProbeException(WebmProbeFailure.INCOMPLETE)
            }
            handle.verifyIdentity()
            val remaining = handle.extent - receipt.position
            val requested = minOf(length.toLong(), remaining, 64 * 1024L).toInt()
            val count = when {
                length == 0 -> 0
                remaining == 0L -> C.RESULT_END_OF_INPUT
                else -> {
                    requireWebm(requested > 0, WebmProbeFailure.READ_REQUEST)
                    // Physical reservations belong to the same attempt as suffix and preflight IO.
                    val result = handle.readAt(receipt.position, buffer, offset, requested)
                    requireWebm(result in 1..requested, if (result == 0) WebmProbeFailure.ZERO_PROGRESS else WebmProbeFailure.TRUNCATED)
                    requireNotNull(session).transferred(result)
                    result
                }
            }
            checkpoint()
            synchronized(lock) {
                requireWebm(opened && pending === receipt && receipt.returned == null, WebmProbeFailure.FOREIGN_INPUT)
                if (count > 0) position += count
                receipt.returned = count
            }
            count
        }
        override fun close() {
            // Framework closeQuietly cannot erase sticky release uncertainty. No live check here.
            val handle = synchronized(lock) { opened = false; input }
            if (handle == null) return
            try {
                requireNotNull(session).closeInput(handle)
                requireWebm(handle.releaseConfirmed, WebmProbeFailure.INCOMPLETE)
                synchronized(lock) { input = null; releaseConfirmed = true }
            } catch (failure: Throwable) { throw originalBoundaryFailure(failure) }
        }
    }
    private object NoRetry : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
        override fun getFallbackSelectionFor(fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? = null
    }
    private fun sameFacts(a: WebmTransportFacts, b: WebmTransportFacts): Boolean =
        a.inputExtent == b.inputExtent && a.declaredContainerDurationUs == b.declaredContainerDurationUs &&
            a.demuxerDurationUs == b.demuxerDurationUs && a.timecodeScaleNs == b.timecodeScaleNs &&
            a.tracks.size == b.tracks.size && a.tracks.zip(b.tracks).all { (x, y) ->
                x.trackNumber == y.trackNumber && x.trackType == y.trackType && x.mimeType == y.mimeType &&
                    x.width == y.width && x.height == y.height && x.sampleRate == y.sampleRate && x.channelCount == y.channelCount &&
                    x.initializationData.size == y.initializationData.size && x.initializationData.zip(y.initializationData).all { (u, v) -> u.contentEquals(v) } &&
                    x.sourceSampleCallbacks == y.sourceSampleCallbacks && x.firstSourceTimestampUs == y.firstSourceTimestampUs &&
                    x.lastSourceTimestampUs == y.lastSourceTimestampUs && x.supplementalBytes == y.supplementalBytes &&
                    x.declaredDefaultDurationNs == y.declaredDefaultDurationNs
            }
    private object DiscardOutput : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            private val scratch = ByteArray(4096)
            override fun format(format: Format) = Unit
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                input.read(scratch, 0, minOf(length, scratch.size))
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
}

/** Normalize only trusted-parser errors. Caller stop/storage exceptions retain their identity. */
internal inline fun <T> parserBoundary(action: () -> T): T = try { action() }
catch (failure: WebmCallerFailure) { throw failure.original }
catch (failure: WebmProbeException) { throw failure }
catch (_: IOException) { throw WebmProbeException(WebmProbeFailure.MALFORMED) }
catch (_: RuntimeException) { throw WebmProbeException(WebmProbeFailure.MALFORMED) }
