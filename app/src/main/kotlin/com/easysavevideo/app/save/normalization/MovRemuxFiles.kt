package com.easysavevideo.app.save.normalization

import androidx.media3.muxer.SeekableMuxerOutput

/** Exact workspace-owned capability. Opening failure must close every not-returned handle itself. */
internal interface MovRemuxFiles {
    fun openSource(): MovRemuxInput
    /** Must create/open only the previously journal-owned EMPTY output generation. Never truncate. */
    fun openEmptyOutput(): MovRemuxOutput
    /** Only after the mux sink has positively closed; never exposes an arbitrary URI/path. */
    fun openFinalizedOutput(): MovRemuxInput
    /** Current available space already excludes the storage owner's journal reserve. */
    fun availableBytes(): Long
}

internal interface MovRemuxInput : MovProbeInput, AutoCloseable {
    /** Recheck captured ownership, file key and exact extent; failure is never a shorter EOF. */
    fun validateIdentity()
    /** True only after its underlying descriptor has actually and successfully closed. */
    val releaseConfirmed: Boolean
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal interface MovRemuxOutput : SeekableMuxerOutput {
    /** Calling close or finishing a Future is not, by itself, release evidence. */
    val releaseConfirmed: Boolean
}
