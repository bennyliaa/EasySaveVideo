package com.easysavevideo.app.vault

/** Process-local identities only. Fields never reconstruct authority. */
internal class VaultPlaybackHostToken internal constructor(val generation: Long, current: () -> Boolean) {
    private val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
    val isCurrent: () -> Boolean = { !revoked.get() && current() }
    /** Exact-token revocation is irreversible and never affects a replacement open. */
    fun revoke() { revoked.set(true) }
}
internal class VaultPlaybackTicket internal constructor(val host: VaultPlaybackHostToken) {
    override fun toString() = "VaultPlaybackTicket(REDACTED)"
}
internal enum class VaultPlaybackDrain { LIVE, RETIRING, RELEASED, UNCONFIRMED }
internal enum class VaultPlaybackStatus { IDLE, PREPARING, READY, PLAYING, PAUSED, ENDED, RETIRING, UNAVAILABLE }
internal data class VaultPlaybackView(val ticket: VaultPlaybackTicket? = null,
    val status: VaultPlaybackStatus = VaultPlaybackStatus.IDLE, val positionMillis: Long = 0,
    val durationMillis: Long? = null, val seekable: Boolean = false) {
    val occupied: Boolean get() = ticket != null
}
internal object VaultPlaybackLimits {
    const val LIFETIME_MILLIS = 24L * 60L * 60L * 1000L
    const val PREPARE_MILLIS = VaultMediaLimits.OPERATION_MILLIS
    const val READ_MILLIS = 30_000L
    const val RETIREMENT_MILLIS = 5_000L
    const val POSITION_UPDATE_MILLIS = 250L
    const val SEEK_STEP_MILLIS = 15_000L
}
/** Created only under VaultRuntime's gate from its current revealed catalog/read lease. */
internal class VaultPlaybackSession(val root: VaultRootCapability, val snapshot: VaultStoredSnapshot,
    val ref: VaultMediaReadRef, val isCurrent: () -> Boolean) {
    internal var revealIdentity: Any = root
}
/** A single anchored read owner. Transport close is deliberately not reader close. */
internal interface VaultPlaybackReadPort {
    val authenticatedSize: Long
    fun requireCurrent()
    fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int): Int
    fun transportOpened()
    fun transportClosed()
}
/** Exact Main-owned Surface identity, implemented by the Android adapter; never Compose state. */
internal interface VaultPlaybackSurface
/** This owner must retain every pre-registered acquisition until actual completion. */
internal interface VaultPreparedPlayback {
    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun attachSurface(surface: VaultPlaybackSurface)
    /** Callback means actual off-Main detach result, not enqueue or timeout success. */
    fun detachSurface(surface: VaultPlaybackSurface, detached: (Boolean) -> Unit)
    fun revoke()
    /** Pure native renderer/audio/loader observation; UNKNOWN does not block safe read cleanup. */
    fun readyForReadRetirement(): Boolean
    /** Called only after the runtime has actually observed both reader and file released. */
    fun readOwnerRetired()
    fun observeDrain(): VaultPlaybackDrain
}
internal fun interface VaultPlaybackPlatformFactory {
    /** Returning an owner does not mean acquisition/preparation or retirement has completed. */
    fun create(ticket: VaultPlaybackTicket, read: VaultPlaybackReadPort, isCurrent: () -> Boolean,
        changed: (VaultPlaybackView) -> Unit, progressed: () -> Unit): VaultPreparedPlayback
}
/** Prepared capability stays between internal owners; it never enters rendered/saved UI state. */
internal interface VaultPlaybackHostPort {
    fun offerPrepared(ticket: VaultPlaybackTicket, prepared: VaultPreparedPlayback)
    fun revokePresentation(ticket: VaultPlaybackTicket)
    fun observeRetirement(ticket: VaultPlaybackTicket): VaultPlaybackDrain
}

/** Display-only identity check. It does not confer reader, surface or authentication authority. */
internal fun vaultPlaybackViewMatchesReservation(ticket: VaultPlaybackTicket, rendered: VaultPlaybackTicket?,
    reserved: VaultPlaybackHostToken?, coverGeneration: Long, status: VaultPlaybackStatus): Boolean {
    if (ticket.host !== reserved || rendered?.let { it.host === reserved && it !== ticket } == true) return false
    return if (status in setOf(VaultPlaybackStatus.RETIRING, VaultPlaybackStatus.UNAVAILABLE)) true
        else ticket.host.isCurrent() && ticket.host.generation == coverGeneration
}
