package com.easysavevideo.app.save.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import java.io.IOException

/** Install through HlsMediaSource.Factory.setExtractorFactory for actual export.
 * Factory construction URI is NEVER used to decide whether a later chunk is final.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalHlsExtractorFactory(private val session: LocalAssetReadSession) : HlsExtractorFactory {
    private var created = false
    override fun createExtractor(uri: Uri, format: Format, muxedCaptionFormats: List<Format>?,
        timestampAdjuster: TimestampAdjuster, responseHeaders: Map<String, List<String>>,
        sniffingExtractorInput: ExtractorInput, playerId: PlayerId): HlsMediaChunkExtractor {
        session.check()
        if (created || session.activeIndex() != 0 || !muxedCaptionFormats.isNullOrEmpty() || format.drmInitData != null ||
            format.roleFlags and C.ROLE_FLAG_TRICK_PLAY != 0) reject()
        created = true
        // Pass the HLS-owned timestamp adjuster. It applies one shared offset; it must not be
        // replaced with a per-chunk clock or an EXTINF-derived sample timeline.
        return LocalHlsChunkExtractor(LocalAvcExtractor(session, timestampAdjuster), session)
    }
    private fun reject(): Nothing { session.revoke(); throw IOException("LOCAL_EXTRACTOR_RECREATE_REJECTED") }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalHlsChunkExtractor(
    internal val extractor: LocalAvcExtractor,
    private val session: LocalAssetReadSession,
) : HlsMediaChunkExtractor {
    private val holder = PositionHolder()
    override fun init(output: ExtractorOutput) = extractor.init(output)
    override fun read(input: ExtractorInput): Boolean = when (extractor.read(input, holder)) {
        Extractor.RESULT_CONTINUE -> true
        Extractor.RESULT_END_OF_INPUT -> false // The final sample has already been committed.
        else -> reject()
    }
    override fun isPackedAudioExtractor() = false
    override fun isReusable() = true
    override fun recreate(): HlsMediaChunkExtractor = reject()
    override fun onTruncatedSegmentParsed(): Unit = reject()
    private fun reject(): Nothing { session.revoke(); throw IOException("LOCAL_EXTRACTOR_RECREATE_REJECTED") }
}
