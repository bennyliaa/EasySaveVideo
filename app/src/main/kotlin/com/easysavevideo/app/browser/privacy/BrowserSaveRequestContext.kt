package com.easysavevideo.app.browser.privacy

import java.util.concurrent.atomic.AtomicBoolean

/** Transient request boundary. Never serialize this type or derive a durable source from its fields. */
internal class BrowserSaveRequestContext internal constructor(
    val partition: BrowserSavePartition,
    val cookie: String?,
    val userAgent: String,
    val resumeAuthority: BrowserSaveResumeAuthority? = null,
) {
    override fun toString() = "BrowserSaveRequestContext([redacted])"
}
/** Fixed original source/document authority in RAM; never serialize or reconstruct after restart. */
internal class BrowserSaveResumeAuthority internal constructor(refresh: () -> BrowserSaveRequestContext?) {
    private val refresh = java.util.concurrent.atomic.AtomicReference<(() -> BrowserSaveRequestContext?)?>(refresh)
    /** Explicit user Resume only. The Host-issued callback requires its main thread. No URL input. */
    fun refreshForExplicitResume(): BrowserSaveRequestContext? {
        val action = refresh.get() ?: return null
        val result = try { action() } catch (_: Exception) { null }
        return result.takeIf { refresh.get() === action }
    }
    /** Drops credentials/source-producing closure; does not claim any running network owner drained. */
    fun retire() { refresh.set(null) }
    override fun toString() = "BrowserSaveResumeAuthority([redacted])"
}
internal sealed interface BrowserSavePartition {
    data object Ordinary : BrowserSavePartition
    class Private internal constructor(val lease: PrivateSessionLease) : BrowserSavePartition {
        override fun toString() = "BrowserSavePartition.Private([redacted])"
    }
}
internal enum class PrivateWorkKind { NETWORK_CANCEL, OUTPUT_ONLY }

/**
 * Registration must precede queued/native/network acquisition. complete acknowledges actual release.
 * Pure output is not a source registration and cannot use this acknowledgement as a shortcut.
 */
internal class PrivateWorkRegistration internal constructor(private val start: (() -> Boolean) -> Boolean, private val resume: (() -> Boolean) -> Boolean, private val finish: () -> Unit) {
    /** Register exact queued owner only; callback must not capture/block/reenter. Failure means do not start. */
    fun startQueued(registerOwner: () -> Boolean): Boolean = start(registerOwner)
    /** Same task/source authority only. Real helper must atomically own DORMANT -> one fresh claim. */
    fun continueQueued(registerOwner: () -> Boolean): Boolean = resume(registerOwner)
    fun complete() = finish()
    override fun toString() = "PrivateWorkRegistration([redacted])"
}

/**
 * Process-local monotonically revocable Save authority, tied to the actual browsing session.
 * Its local revocation does not call session.revoke(): runtime.retire owns that transition and must
 * be invoked in the same owner-thread exit operation. onExit must be short, non-blocking stop-request dispatch
 * only. Callbacks run outside the lock on the caller
 * thread; native adapters dispatch to their own thread and acknowledge actual resource release.
 */
internal class PrivateSessionLease internal constructor(private val session: PrivateBrowsingSession) {
    private class Work(val kind: PrivateWorkKind, val onExit: (() -> Unit) -> Unit) { var started = false }
    private val lock = Any()
    private val revoked = AtomicBoolean(false)
    private var dispatching = false
    private val work = LinkedHashSet<Work>()
    private val waiters = mutableListOf<() -> Unit>()
    fun isLive(): Boolean = !revoked.get() && session.isLive()
    fun register(kind: PrivateWorkKind, onExit: (released: () -> Unit) -> Unit): PrivateWorkRegistration? {
        // Pure output is owned outside this source lease after real network/capture drain. An enum
        // cannot prove detachment or convert a live source owner into independent output work.
        if (kind == PrivateWorkKind.OUTPUT_ONLY) return null
        val item = Work(kind, onExit)
        synchronized(lock) {
            if (revoked.get() || !session.isLive() || work.size >= 256) return null
            work += item
        }
        return PrivateWorkRegistration(start = { registerOwner ->
            synchronized(lock) {
                if (revoked.get() || !session.isLive() || item !in work || item.started) false
                else {
                    item.started = true
                    // Partial registration remains in work even on exception; owner must release it.
                    try { registerOwner() } catch (_: Exception) { false }
                }
            }
        }, resume = { registerOwner ->
            synchronized(lock) {
                if (revoked.get() || !session.isLive() || item !in work || !item.started || item.kind != PrivateWorkKind.NETWORK_CANCEL) false
                else try { registerOwner() } catch (_: Exception) { false }
            }
        }, finish = { complete(item) })
    }
    private fun readyLocked(): List<() -> Unit> {
        if (!revoked.get() || dispatching || work.isNotEmpty()) return emptyList()
        return waiters.toList().also { waiters.clear() }
    }
    private fun deliver(callbacks: List<() -> Unit>) {
        callbacks.forEach { try { it() } catch (_: Exception) { /* One owner cannot starve other drain waiters. */ } }
    }
    private fun complete(item: Work) {
        val ready = synchronized(lock) { work.remove(item); readyLocked() }
        deliver(ready)
    }
    fun revokeAndDrain(done: () -> Unit) {
        val pending: List<Work>
        val alreadyRevoked: Boolean
        synchronized(lock) {
            waiters += done
            alreadyRevoked = revoked.get()
            if (!revoked.get()) { revoked.set(true); dispatching = true }
            pending = if (alreadyRevoked) emptyList() else work.toList()
        }
        if (!alreadyRevoked) {
            pending.forEach { item ->
                // Completion racing this snapshot may make this idempotent stop unnecessary; owner
                // callbacks must accept an already-released owner and must never acquire new work.
                try { item.onExit { complete(item) } } catch (_: Exception) { /* No synthetic release. */ }
            }
        }
        val ready = synchronized(lock) {
            if (!alreadyRevoked) dispatching = false
            readyLocked()
        }
        deliver(ready)
    }
    override fun toString() = "PrivateSessionLease([redacted])"
}

/** Installed only by the real Activity/Save/Record composition, never page or persisted state. */
internal enum class PrivateBrowserLifecycleTransition { PAUSE, RESUME }

internal interface PrivateBrowserIntegration {
    /** All three adapters, typed durable privacy and window/record barriers are installed.
     * Current recording eligibility (grant/API/surface) is a separate per-action gate; secure
     * private browsing/network saves do not require a currently available capture exception. */
    fun ready(): Boolean
    /** A live continuous BASIC authorization is compatible with a fresh private session. */
    fun canEnterPrivateMode(): Boolean
    /** Synchronous local protection/rebinding only. A presentation transition never stops submitted BASIC. */
    fun protectForPresentationTransition(): Boolean
    /** A never-submitted permission attempt may be parked; live recording ownership is independent. */
    fun protectForLifecycle(reason: PrivateBrowserLifecycleTransition): Boolean = protectForPresentationTransition()
    /** Exact visible View and current capture/audio ownership have been checked by the composition. */
    fun allowVisibleAudio(view: android.webkit.WebView): Boolean
}

