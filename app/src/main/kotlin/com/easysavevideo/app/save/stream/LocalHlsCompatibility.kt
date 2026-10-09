package com.easysavevideo.app.save.stream

import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource

/** Wiring entry point. Use only after raw validation of these exact immutable resources.
 * Each pass has independent receipts/reader state. The parser, profile and EOF logic are shared.
 * Existing engine DRM, no-codec, budgets, sample proofs and release guards remain mandatory.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalHlsCompatibility(
    resources: List<AssetResource>,
    private val evidence: CompatTsProfileEvidence,
    private val checkpoint: () -> Unit,
) {
    private val resources = resources.toList()

    fun inspection(localOnly: DataSource.Factory): InspectionPass {
        val session = LocalAssetReadSession(resources, evidence, checkpoint)
        return InspectionPass(PermitDataSourceFactory(localOnly, session, resources.size),
            LocalAvcExtractor(session, TimestampAdjuster(TimestampAdjuster.MODE_NO_OFFSET)), session)
    }

    fun export(localOnly: DataSource.Factory): ExportPass {
        val session = LocalAssetReadSession(resources, evidence, checkpoint)
        val sources = PermitDataSourceFactory(localOnly, session, resources.size)
        val extractors = LocalHlsExtractorFactory(session)
        return ExportPass(sources, extractors, session)
    }

    class InspectionPass internal constructor(
        val sources: DataSource.Factory,
        val extractor: LocalAvcExtractor,
        private val session: LocalAssetReadSession,
    ) {
        fun verifyComplete() = session.verifyComplete()
        fun revoke() = session.revoke()
    }

    class ExportPass internal constructor(
        val sources: DataSource.Factory,
        val extractors: LocalHlsExtractorFactory,
        private val session: LocalAssetReadSession,
    ) {
        fun mediaSourceFactory(): HlsMediaSource.Factory = HlsMediaSource.Factory(sources)
            .setExtractorFactory(extractors).setAllowChunklessPreparation(false)
        fun verifyComplete() = session.verifyComplete()
        fun revoke() = session.revoke()
    }
}
