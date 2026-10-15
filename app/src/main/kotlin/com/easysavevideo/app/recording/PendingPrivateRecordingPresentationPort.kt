package com.easysavevideo.app.recording

import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.browser.privacy.PrivateCaptureScope
import com.easysavevideo.app.browser.privacy.PrivateCaptureSurfaceEvidence
import com.easysavevideo.app.browser.privacy.PrivateCaptureTarget

/**
 * Real Activity composition adapter with an explicitly unfinished capture-window exception.
 * The bridge and inline UI are connected, but this adapter never issues evidence or removes secure.
 * Secure API31+ browsing uses a separate owner; this pending capability does not gate that owner.
 */
internal class PendingPrivateRecordingPresentationPort(
    private val reserveProtection: () -> Boolean,
    private val browser: SystemBrowserHost,
    private val browserRouteCurrent: () -> Boolean,
    private val presentationEnded: () -> Unit,
) : PrivateRecordingPresentationPort, AutoCloseable {
    private var presentationId: Long? = null
    private var target: PrivateCaptureTarget? = null
    private var closed = false
    // Positive by construction: no method in this adapter can open a capture exception.
    val captureExceptionInactive: Boolean get() = true
    override fun capabilityAvailable(): Boolean = false
    override fun begin(target: PrivateCaptureTarget, presentationId: Long): Boolean {
        if (closed || this.presentationId != null || !browserRouteCurrent() ||
            !browser.isPrivateCaptureTargetCurrent(target)) return false
        if (!installProtectionBarrier()) return false
        this.target = target; this.presentationId = presentationId
        return true
    }
    override fun requestEvidence(target: PrivateCaptureTarget, owner: Any, scope: PrivateCaptureScope,
        completed: (PrivateCaptureSurfaceEvidence?) -> Unit): Boolean = false
    override fun isCurrent(evidence: PrivateCaptureSurfaceEvidence): Boolean = false
    override fun openException(evidence: PrivateCaptureSurfaceEvidence, stillAllowed: () -> Boolean): Boolean {
        installProtectionBarrier()
        return false
    }
    override fun installProtectionBarrier(): Boolean = !closed && reserveProtection()
    override fun restoreProtection(target: PrivateCaptureTarget, protected: () -> Unit) {
        // A stale capture target still needs synchronous protection, but never receives frame evidence.
        if (installProtectionBarrier()) protected()
    }
    override fun end(presentationId: Long) {
        if (closed || this.presentationId != presentationId) return
        installProtectionBarrier()
        this.presentationId = null; target = null
        presentationEnded()
    }
    override fun close() {
        if (closed) return
        installProtectionBarrier()
        closed = true; presentationId = null; target = null
    }
}
