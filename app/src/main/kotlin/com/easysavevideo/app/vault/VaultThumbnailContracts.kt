package com.easysavevideo.app.vault

import com.easysavevideo.library.thumbnail.ThumbnailDecodeResult
import com.easysavevideo.library.thumbnail.ThumbnailFrame

internal object VaultThumbnailLimits {
    const val LIFETIME_MILLIS = 3_000L
    const val MAX_BYTES = 8L * 1024L * 1024L
    const val MAX_CALLBACKS = 512
    const val MAX_IDENTITIES = 297
    const val MAX_FRAMES = 16
    const val MAX_FRAME_BYTES = 147_456L
}
/** Host creates one token per protected reveal; the runtime additionally captures its read lease. */
internal typealias VaultThumbnailHostToken = VaultPlaybackHostToken
internal class VaultThumbnailTicket internal constructor(val host: VaultThumbnailHostToken,
    internal val ref: VaultMediaReadRef) {
    override fun toString() = "VaultThumbnailTicket(REDACTED)"
}
/** Separate from the read operation: successful reader retirement does not revoke a display. */
internal class VaultThumbnailDisplayToken internal constructor(private val current: () -> Boolean) {
    private val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
    fun isCurrent(): Boolean = !revoked.get() && current()
    internal fun revoke() { revoked.set(true) }
    override fun toString() = "VaultThumbnailDisplayToken(REDACTED)"
}
/** Runtime-registered opaque frame. Pixel access is confined to the shared Android drawing adapter. */
internal interface VaultThumbnailFrame : ThumbnailFrame {
    val display: VaultThumbnailDisplayToken
    fun drawIfCurrent(draw: (ThumbnailFrame) -> Unit)
}
/** Exact completion object; duplicate delivery of the adopted frame must never close it again. */
internal class VaultThumbnailResult(val frame: ThumbnailFrame?, val nativeReleased: Boolean)
internal interface VaultPreparedThumbnail {
    /** Runtime registers the owner before this begins any native acquisition. */
    fun start()
    fun revoke()
    /** Actual native release observation, independent of the controlled reader/file facts. */
    fun observeDrain(): VaultPlaybackDrain
    /** Positive actual native return, distinct from aggregate read/Main settlement. */
    fun nativeReleaseObserved(): Boolean
    fun readOwnerRetired()
    /** Once-only receipt from the actual Main consumer, after frame adoption/discard. */
    fun resultHandoffSettled()
}
internal fun interface VaultThumbnailPlatformFactory {
    /** Register the returned owner before async native acquisition. nativeStarting is called
     * immediately before actual MMR construction/setup; false means acquire nothing. */
    fun create(ticket: VaultThumbnailTicket, read: VaultThumbnailReadPort, isCurrent: () -> Boolean,
        nativeStarting: () -> Boolean, completed: (VaultThumbnailResult) -> Unit,
        progressed: () -> Unit): VaultPreparedThumbnail
}
internal interface VaultThumbnailReadCallback : AutoCloseable {
    val remainingBytes: Long
    override fun close()
}
internal interface VaultThumbnailReadPort : VaultPlaybackReadPort {
    /** Includes getSize, zero and EOF callbacks. Byte reads are serial; close is actual return. */
    fun beginCallback(byteRead: Boolean): VaultThumbnailReadCallback
}
/** Consumer calls settled once after exact Main adoption/discard, before pumping another job. */
internal typealias VaultThumbnailCompletion = (ThumbnailDecodeResult<VaultThumbnailFrame>, settled: () -> Unit) -> Unit
