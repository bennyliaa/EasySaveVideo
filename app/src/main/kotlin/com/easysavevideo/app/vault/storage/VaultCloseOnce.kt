package com.easysavevideo.app.vault.storage

import com.easysavevideo.library.export.ExportRelease

/** Real close is issued once. Later calls only observe the original resource; timeout is no ACK. */
internal class VaultCloseOnce(private val closeResource: () -> Unit, private val isActuallyReleased: () -> Boolean,
    private val allowObservationAfterCloseFailure: Boolean = false) {
    private var started = false
    private var closeFailed = false
    @Synchronized fun closeAndObserve(): ExportRelease {
        if (!started) {
            started = true
            try { closeResource() } catch (_: Exception) { closeFailed = true }
        }
        if (closeFailed && !allowObservationAfterCloseFailure) return ExportRelease.UNCONFIRMED
        return if (try { isActuallyReleased() } catch (_: Exception) { false }) ExportRelease.RELEASED else ExportRelease.UNCONFIRMED
    }
}
