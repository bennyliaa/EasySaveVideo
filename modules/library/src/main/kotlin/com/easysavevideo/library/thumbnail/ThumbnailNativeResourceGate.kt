package com.easysavevideo.library.thumbnail

/** An owner survives cancellation/timeout. Failed release permanently fences fresh admission. */
class ThumbnailNativeResourceGate {
    private var owner: Any? = null
    @Volatile private var currentCell: Ownership? = null
    /** A read-only projection of an actual claim, never an independent authorization. */
    class Ownership internal constructor(private val issuer: ThumbnailNativeResourceGate, private val candidate: Any) {
        val isLive: Boolean get() = issuer.currentCell === this && !issuer.requiresRestart
        internal fun belongsTo(value: Any): Boolean = candidate === value
    }
    /** Memory-only: safe while storage/registry monitors are held. */
    fun ownership(candidate: Any): Ownership? = currentCell?.takeIf { it.belongsTo(candidate) && it.isLive }

    @Volatile var requiresRestart: Boolean = false
        private set
    val isOccupied: Boolean @Synchronized get() = owner != null
    @Synchronized fun claim(candidate: Any): Boolean {
        if (requiresRestart || owner != null) return false
        owner = candidate
        currentCell = Ownership(this, candidate)
        return true
    }
    @Synchronized fun released(candidate: Any) {
        if (owner === candidate) { currentCell = null; owner = null }
    }
    @Synchronized fun unconfirmed() { requiresRestart = true }
}
