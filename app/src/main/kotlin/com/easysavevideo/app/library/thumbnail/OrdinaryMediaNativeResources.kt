package com.easysavevideo.app.library.thumbnail

import com.easysavevideo.library.thumbnail.ThumbnailNativeResourceGate

/**
 * Process-wide admission shared by thumbnail decode and ordinary published-media preview.
 * Recording checks this gate before accepting its own resources. Only actual native release may
 * clear ownership. Timeout/release uncertainty is sticky until a real process restart.
 */
object OrdinaryMediaNativeResources {
    private val gate = ThumbnailNativeResourceGate()
    val requiresRestart: Boolean get() = gate.requiresRestart
    val isOccupied: Boolean get() = gate.isOccupied
    fun claim(candidate: Any): Boolean = gate.claim(candidate)
    fun ownership(candidate: Any): ThumbnailNativeResourceGate.Ownership? = gate.ownership(candidate)
    fun released(candidate: Any) = gate.released(candidate)
    fun unconfirmed() = gate.unconfirmed()
}
