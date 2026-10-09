package com.easysavevideo.save.network

import com.easysavevideo.save.StagingArtifact
import java.io.OutputStream

/** Adapter only: core artifacts carry neither File nor a caller-selected filesystem path. */
interface NetworkStagingAccess {
    fun length(artifact: StagingArtifact): Long
    fun availableBytes(artifact: StagingArtifact): Long
    /** Discard only bytes beyond the last fsynced journal checkpoint; never extend or touch committed data. */
    fun rewind(artifact: StagingArtifact, length: Long)
    /** append=false must replace the owned staging with a fresh inode, never append to stale bytes. */
    fun open(artifact: StagingArtifact, append: Boolean): OutputStream
    /** Called after OutputStream.flush, before exposing a durable checkpoint. Must actually fsync. */
    fun sync(artifact: StagingArtifact)
}
