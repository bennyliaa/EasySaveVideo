package com.easysavevideo.app.ui

/** Request/action/input revocation is terminal; native resource release may need a later callback. */
internal class PrivateDialogLifetime {
    var isClosed: Boolean = false
        private set
    fun beginClose(): Boolean {
        if (isClosed) return false
        isClosed = true
        return true
    }
}

/**
 * Main-owner teardown ports, exercised by JVM tests without simulating Android WindowManager.
 * Failure or delayed detach keeps the exact registration quarantined. No timeout is evidence of
 * release. A reentrant/duplicate event can never repeat revocation or release a replacement owner.
 */
internal class PrivateDialogTeardown(
    private val lifetime: PrivateDialogLifetime,
    private val revoke: () -> Unit,
    private val protect: () -> Unit,
    private val dismiss: () -> Unit,
    private val disposeComposition: () -> Unit,
    private val removeContentChildren: () -> Unit,
    private val removeRootChildren: () -> Unit,
    private val detachedAndEmpty: () -> Boolean,
    private val release: () -> Boolean,
) {
    var isComplete: Boolean = false
        private set
    private var running = false
    private var requesting = false
    private var nativeDismissDepth = 0

    private fun attempt(action: () -> Unit) { try { action() } catch (_: Exception) { /* Retain ownership. */ } }
    private fun checked(check: () -> Boolean) = try { check() } catch (_: Exception) { false }

    /** Used before native dismiss; never removes a registration before native dismiss is attempted. */
    private fun requestClose() {
        if (requesting) return
        requesting = true
        try {
            if (lifetime.beginClose()) attempt(revoke)
            if (!isComplete) attempt(protect)
        } finally { requesting = false }
    }

    fun beforeNativeDismiss() {
        nativeDismissDepth++
        requestClose()
    }

    fun afterNativeDismiss() {
        check(nativeDismissDepth > 0)
        nativeDismissDepth--
        close(dismissWindow = false)
    }

    fun close(dismissWindow: Boolean = true) {
        if (isComplete || running || nativeDismissDepth > 0) return
        running = true
        try {
            requestClose()
            if (dismissWindow) attempt(dismiss)
            attempt(disposeComposition)
            // Independent attempts: a failed provider removal must not skip root removal.
            attempt(removeContentChildren)
            attempt(removeRootChildren)
            if (checked(detachedAndEmpty) && checked(release)) isComplete = true
        } finally { running = false }
    }
}
