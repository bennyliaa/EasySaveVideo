package com.easysavevideo.app.save.conversion

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import java.util.concurrent.atomic.AtomicBoolean

/** Public pinned Media3 bridge, driving the bounded extractor's own complete guard. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class OwnedWebmProgressiveExtractor internal constructor(
    private val pair: OwnedWebmInputBinding,
    private val binding: WebmInputBinding,
) : ProgressiveMediaExtractor {
    private var parser: BoundedWebmExtractor? = null
    private var input: WebmGuardedInput? = null
    private var initialized = false
    private var seekIssued = false
    private var started = false
    @Volatile private var eof = false
    private val released = AtomicBoolean()
    override fun init(dataReader: DataReader, uri: Uri, responseHeaders: Map<String, List<String>>,
        position: Long, length: Long, output: ExtractorOutput) = pair.boundary {
        pair.checkpoint()
        requireWebm(!initialized && !released.get(), WebmProbeFailure.INCOMPLETE)
        initialized = true
        val owned = pair.sequentialInput(this, dataReader, uri, responseHeaders, position, length)
        val current = WebmExtractorFactory(binding).create().also { parser = it }
        parserBoundary {
            val guarded = current.newInput(owned).also { input = it }
            requireWebm(current.sniff(guarded), WebmProbeFailure.TRACK_PROFILE)
            guarded.resetPeekPosition()
            current.init(output)
        }
        pair.checkpoint()
    }
    override fun read(positionHolder: PositionHolder): Int = pair.boundary {
        pair.checkpoint()
        requireWebm(initialized && !released.get() && !eof, WebmProbeFailure.INCOMPLETE)
        started = true
        val current = requireNotNull(parser)
        val result = parserBoundary { current.read(requireNotNull(input), positionHolder) }
        requireWebm(!released.get(), WebmProbeFailure.INCOMPLETE)
        requireWebm(result != Extractor.RESULT_SEEK, WebmProbeFailure.SEEK_UNSUPPORTED)
        if (result == Extractor.RESULT_END_OF_INPUT) {
            val facts = parserBoundary { current.finish() }
            pair.completed(this, facts); eof = true
        }
        pair.checkpoint(); result
    }
    override fun getCurrentInputPosition(): Long = input?.position ?: C.INDEX_UNSET.toLong()
    override fun seek(position: Long, timeUs: Long) = pair.boundary {
        pair.checkpoint()
        requireWebm(initialized && !released.get() && !started && !seekIssued && position == 0L && timeUs == 0L,
            WebmProbeFailure.SEEK_UNSUPPORTED)
        seekIssued = true
        parserBoundary { requireNotNull(parser).seek(position, timeUs) }
    }
    override fun disableSeekingOnMp3Streams() = pair.boundary<Unit> {
        // Empty response headers make the pinned IcyDataSource path unreachable.
        throw WebmProbeException(WebmProbeFailure.TRACK_PROFILE)
    }
    override fun release() {
        if (released.compareAndSet(false, true)) {
            if (!eof) pair.failClosed()
            try { parser?.release() } catch (failure: Throwable) { pair.failClosed(); throw failure }
        }
    }
}
