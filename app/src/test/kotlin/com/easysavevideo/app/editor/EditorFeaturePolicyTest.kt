package com.easysavevideo.app.editor

import com.easysavevideo.core.model.Feature
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.*
import org.junit.Assert.*
import org.junit.Test

class EditorFeaturePolicyTest {
    private val source = MediaReadRef("source", 1, 1)
    private val full = EditSegment(source, 0, 1_000_000)
    private val durations = mapOf(source to 1_000_000L)
    @Test fun noOpRequiresNoPaidOperation() = assertEquals(emptySet<Feature>(), editorFeatures(EditRequest("id", listOf(full)), durations))
    @Test fun compressOnlyRequiresCompress() = assertEquals(setOf(Feature.VIDEO_COMPRESS), editorFeatures(EditRequest("id", listOf(full), EditCompression.SAVE_SPACE), durations))
    @Test fun trimUsesExistingCropCapability() = assertEquals(setOf(Feature.VIDEO_CROP), editorFeatures(EditRequest("id", listOf(full.copy(startUs = 250000))), durations))
    @Test fun orderedFullMergeOnlyRequiresMerge() = assertEquals(setOf(Feature.VIDEO_MERGE), editorFeatures(EditRequest("id", listOf(full, full)), durations))
    @Test fun mixedOperationsRequireOnlyTheirExistingCapabilities() = assertEquals(setOf(Feature.VIDEO_CROP, Feature.VIDEO_MERGE, Feature.VIDEO_COMPRESS),
        editorFeatures(EditRequest("id", listOf(full.copy(crop = NormalizedCrop(0.1, 0.0, 0.9, 1.0)), full), EditCompression.SAVE_SPACE), durations))
}
