package com.easysavevideo.library.thumbnail

/** An owner survives cancellation/timeout. Failed release permanently fences fresh admission. */
class ThumbnailNativeResourceGate {
    private var owner: Any? = null
    @Volatile var requiresRestart: Boolean = false
        private set
    val isOccupied: Boolean @Synchronized get() = owner != null
    @Synchronized fun claim(candidate: Any): Boolean {
        if (requiresRestart || owner != null) return false
        owner = candidate
        return true
    }
    @Synchronized fun released(candidate: Any) {
        if (owner === candidate) owner = null
    }
    @Synchronized fun unconfirmed() { requiresRestart = true }
}
