package com.easysavevideo.app.media.transform

import androidx.media3.muxer.SeekableMuxerOutput
import com.easysavevideo.core.model.OperationStopReason

/** Technical mechanism failures. Caller adapters alone translate these into product errors. */
internal enum class TransformerError {
    CAPACITY, INVALID_REQUEST, INVALID_STATE, SOURCE_LIMIT, UNSUPPORTED_PROFILE,
    EXECUTION_FAILED, INSUFFICIENT_SPACE, NOT_ISSUED,
}
internal class TransformerFailure(val code: TransformerError) : RuntimeException(code.name)
internal class TransformerStopped(val reason: OperationStopReason) : RuntimeException(reason.name)
internal enum class TransformerRelease { CONFIRMED, UNCONFIRMED }
internal sealed interface TransformerOutcome<out T> {
    /** No result is returned until the worker and every native/storage release are acknowledged. */
    data class Completed<T>(val value: T) : TransformerOutcome<T>
    /** Cleanup never replaces the original stop/failure or converts it into successful work. */
    data class Failed(val primary: Throwable, val release: TransformerRelease) : TransformerOutcome<Nothing>
}

/** A caller-owned capability; the private address merely routes to this exact capability. */
internal interface TransformerOwnedSource {
    fun retainNativeReader(): AutoCloseable
    fun openReader(): TransformerReadHandle
}
internal interface TransformerReadHandle : AutoCloseable {
    val extent: Long
    val releaseConfirmed: Boolean
    fun verifyIdentity()
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

/** Issued by the storage owner. No shared mechanism accepts or opens a File, URL or pathname. */
internal interface TransformerOwnedTarget {
    val muxerToken: String
    fun verifyIdentity()
    fun sizeBytes(): Long
    fun availableBytes(): Long
    /** Called once by the entered session. Any throw leaves native acquisition unconfirmed. */
    fun retainNativeWriter(): AutoCloseable
    fun openEmptyWriter(): TransformerWriter
}
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal interface TransformerWriter : SeekableMuxerOutput {
    /** Logical writer calls have drained; this alone does not acknowledge the target anchor FD. */
    val releaseConfirmed: Boolean
    /** Idempotent logical release. Native retention may still keep the physical target anchor open. */
    override fun close()
    /** Only bounded final tables/force/close remain allowed; this never revives publication. */
    fun beginFinalization()
    fun force()
}

internal data class TransformerSessionLimits(
    val timeoutMillis: Long,
    val stallTimeoutMillis: Long,
    val releaseTimeoutMillis: Long,
    val maxInputOpens: Int,
    val maxOpenInputs: Int,
    val maxTransferredBytes: Long,
    val maxLoaders: Int,
    val maxCodecs: Int,
    val maxExecutors: Int,
) {
    init {
        require(timeoutMillis in 1..3_600_000 && stallTimeoutMillis in 1..60_000 && releaseTimeoutMillis in 1..60_000)
        require(maxInputOpens in 1..396 && maxOpenInputs in 1..4 && maxTransferredBytes in 1..32L * 1024 * 1024 * 1024)
        require(maxLoaders in 1..33 && maxCodecs in 1..68 && maxExecutors in 1..35)
    }
}
