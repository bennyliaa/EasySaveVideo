package com.easysavevideo.app.media.transform

import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EditedMediaItem
import java.util.Collections

internal data class TransformerAudioFormat(val sampleRate: Int, val channels: Int) {
    init { require(sampleRate in setOf(32_000, 44_100, 48_000) && channels in 1..2) }
}
internal data class TransformerInputProfile(
    val width: Int, val height: Int, val rotationDegrees: Int, val audio: TransformerAudioFormat?,
)
internal data class TransformerOutputSpec(
    val width: Int, val height: Int, val frameRate: Int, val durationUs: Long,
    val durationToleranceUs: Long, val audioPrimingToleranceUs: Long,
    val expectedVideoFrames: Long, val audio: TransformerAudioFormat?,
    val maxBytes: Long, val requiredFreeBytes: Long, val videoBitrate: Int, val audioBitrate: Int,
) {
    init {
        require(width in 2..1920 && height in 2..1920 && width.toLong() * height <= 1920L * 1080)
        require(width % 2 == 0 && height % 2 == 0)
        require(frameRate in 1..60 && durationUs in 1..1_800_000_000L && expectedVideoFrames in 1..108_000)
        require(durationToleranceUs in 1..8_000_000 && audioPrimingToleranceUs in 0..2_112_000)
        require(maxBytes in 1..2L * 1024 * 1024 * 1024 && requiredFreeBytes >= 0)
        require(videoBitrate in 256_000..20_000_000 && audioBitrate in 0..192_000)
    }
}
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal data class TransformerEditorItem(
    val source: TransformerOwnedSource,
    val address: String,
    val profile: TransformerInputProfile,
    val mediaItem: EditedMediaItem,
)

/** Freely constructed Direct data is always unissued. The real owner-only run takes its exact
 * registered storage attempt instead; it never upgrades this data shape into a capability. */
internal sealed class TransformerDirectLoaderBinding private constructor() {
    /** Explicit absence of issuance. Contains no source/factory/IO and grants no authority. */
    data object Unissued : TransformerDirectLoaderBinding()
}
internal sealed interface TransformerRunSpec {
    val output: TransformerOutputSpec
    class EditorAvcAac(items: List<TransformerEditorItem>, override val output: TransformerOutputSpec) : TransformerRunSpec {
        val items: List<TransformerEditorItem> = Collections.unmodifiableList(ArrayList(items))
    }
    class DirectVp8Opus(val binding: TransformerDirectLoaderBinding, override val output: TransformerOutputSpec) : TransformerRunSpec
}

/** The unissued Direct branch is rejected before any capability method or native creation. */
internal fun requireIssuedTransformerRun(spec: TransformerRunSpec): TransformerRunSpec.EditorAvcAac = when (spec) {
    is TransformerRunSpec.EditorAvcAac -> spec
    is TransformerRunSpec.DirectVp8Opus -> throw TransformerFailure(TransformerError.NOT_ISSUED)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun validateTransformerEditorItems(spec: TransformerRunSpec.EditorAvcAac) {
    if (spec.items.size !in 1..33 || spec.items.map { it.address }.distinct().size != spec.items.size)
        throw TransformerFailure(TransformerError.INVALID_REQUEST)
    spec.items.forEachIndexed { index, item ->
        val local = item.mediaItem.mediaItem.localConfiguration ?: throw TransformerFailure(TransformerError.INVALID_REQUEST)
        if (item.address != "esv-edit:///source-$index.mp4" || local.uri.toString() != item.address || local.mimeType != MimeTypes.VIDEO_MP4 ||
            local.drmConfiguration != null || local.adsConfiguration != null || local.subtitleConfigurations.isNotEmpty() ||
            local.streamKeys.isNotEmpty() || local.customCacheKey != null || local.tag != null)
            throw TransformerFailure(TransformerError.INVALID_REQUEST)
    }
}
internal data class TransformerEngineFacts(val bytesWritten: Long, val videoSamples: Long, val audioSamples: Long,
    val sourceObservation: TransformerSourceObservation? = null)
