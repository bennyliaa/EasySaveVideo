package com.easysavevideo.app.save

/** Short-lived generated numbering, never a source title, filename, URL or persisted wire value. */
data class PageSaveCandidateIndices(val videoNumber: Int, val resourceNumber: Int)

/** The detector already bounds its source array to 32; preserve its original numbering exactly. */
internal fun pageSaveCandidateIndices(videoNumber: Int, zeroBasedResourceIndex: Int) =
    PageSaveCandidateIndices(videoNumber.coerceIn(1, 32), zeroBasedResourceIndex + 1)
