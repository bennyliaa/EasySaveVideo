package com.easysavevideo.app.browser.privacy

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Immutable, process-local input identity. Never persist or log its owners. */
internal class PrivateInputSnapshot internal constructor(
    val root: Any,
    val presentation: Any,
    val owner: Any,
    val generation: Long,
) {
    override fun toString() = "PrivateInputSnapshot([redacted])"
}

internal class PrivateInputPublication(
    val presentation: Any?,
    val generation: Long,
    val disposed: Boolean,
)

/**
 * The root's owner publishes only after checked private reveal, and revokes BEFORE protection,
 * replacement, focus loss or callbacks. Worker threads see immutable reference snapshots only.
 * No View/Compose/Host read, provider call or external callback runs inside a publication CAS.
 */
internal class PrivateInputLifetime(private val root: Any) {
    private val current = AtomicReference(PrivateInputPublication(null, 0L, false))
    val isAdmitted: Boolean get() = current.get().let { !it.disposed && it.presentation != null }

    /** A new publication invalidates every previous token, even for the same presentation (ABA). */
    fun publish(presentation: Any): Boolean = change(presentation, disposing = false)
    fun revoke() { change(null, disposing = false) }
    fun dispose() { change(null, disposing = true) }

    private fun change(presentation: Any?, disposing: Boolean): Boolean {
        while (true) {
            val old = current.get()
            if (old.disposed) return false
            // Never wrap a generation and accidentally re-admit an old connection.
            val exhausted = old.generation == Long.MAX_VALUE
            val next = PrivateInputPublication(
                if (disposing || exhausted) null else presentation,
                if (exhausted) old.generation else old.generation + 1,
                disposing || exhausted,
            )
            if (current.compareAndSet(old, next)) return !next.disposed && next.presentation != null
        }
    }

    /** Called for the exact editor/input owner after the main owner verified its root attachment. */
    fun acquire(owner: Any): PrivateInputToken? {
        val publication = current.get()
        val presentation = publication.presentation ?: return null
        if (publication.disposed) return null
        val snapshot = PrivateInputSnapshot(root, presentation, owner, publication.generation)
        return PrivateInputToken(this, publication, snapshot).takeIf { it.isCurrent() }
    }

    internal fun isCurrent(publication: PrivateInputPublication): Boolean =
        !publication.disposed && publication.presentation != null && current.get() === publication

    override fun toString() = "PrivateInputLifetime([redacted])"
}

/** Exact connection/action token. Local close does not revoke other editors under the same root. */
internal class PrivateInputToken internal constructor(
    private val lifetime: PrivateInputLifetime,
    private val publication: PrivateInputPublication,
    val snapshot: PrivateInputSnapshot,
    private val parent: PrivateInputToken? = null,
    private val additionalCurrent: (() -> Boolean)? = null,
) {
    private val revoked = AtomicBoolean(false)
    fun isCurrent(): Boolean = !revoked.get() && lifetime.isCurrent(publication) &&
        (parent == null || parent.isCurrent()) && (additionalCurrent?.invoke() != false)
    fun revoke() { revoked.set(true) }

    /** A connection gets local close state while remaining subordinate to its captured session. */
    internal fun fork(additionalCurrent: (() -> Boolean)? = null): PrivateInputToken? = if (isCurrent()) {
        PrivateInputToken(lifetime, publication, snapshot, this, additionalCurrent).takeIf { it.isCurrent() }
    } else null

    /** Already-admitted provider work can finish after revoke; this is NOT an input-drain proof. */
    internal fun mutate(operation: () -> Boolean): Boolean = if (isCurrent()) operation() else false

    /** Both sides of a possibly blocking read are fenced. No admission lock spans the provider. */
    internal fun <T> read(rejected: T, operation: () -> T): T {
        if (!isCurrent()) return rejected
        val value = operation()
        return if (isCurrent()) value else rejected
    }

    override fun toString() = "PrivateInputToken([redacted])"
}
