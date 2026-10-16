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
    val retirement: PrivateInputRetirement? = null,
)

/**
 * The root's owner publishes only after checked private reveal, and revokes BEFORE protection,
 * replacement, focus loss or callbacks. Worker threads see immutable reference snapshots only.
 * No View/Compose/Host read, provider call or external callback runs inside a publication CAS.
 */
internal class PrivateInputLifetime(private val root: Any) {
    private val current = AtomicReference(PrivateInputPublication(null, 0L, false))
    val isAdmitted: Boolean get() = current.get().let { !it.disposed && it.presentation != null && it.retirement?.isSealed != true }

    /** Vault host only: opt in before reveal; ordinary/browser lifetimes keep the default path. */
    fun beginRetirementTracking(): PrivateInputRetirement? {
        while (true) {
            val old = current.get()
            if (old.disposed || old.presentation != null || old.retirement != null || old.generation == Long.MAX_VALUE) return null
            val retirement = PrivateInputRetirement(::revokeRetirementPublication)
            val next = PrivateInputPublication(null, old.generation + 1, false, retirement)
            if (current.compareAndSet(old, next)) return retirement
        }
    }

    /** Explicit host release; stale/unknown receipts cannot reset or replace the tracked fence. */
    fun finishRetirementTracking(retirement: PrivateInputRetirement): Boolean {
        while (true) {
            val old = current.get()
            if (old.retirement !== retirement || !retirement.isRetired || old.presentation != null) return false
            val next = PrivateInputPublication(null, old.generation, old.disposed)
            if (current.compareAndSet(old, next)) return true
        }
    }

    private fun revokeRetirementPublication(retirement: PrivateInputRetirement) {
        while (true) {
            val old = current.get()
            if (old.retirement !== retirement || old.presentation == null) return
            val exhausted = old.generation == Long.MAX_VALUE
            val next = PrivateInputPublication(null, if (exhausted) old.generation else old.generation + 1,
                old.disposed || exhausted, retirement)
            if (current.compareAndSet(old, next)) return
        }
    }

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
                if (disposing || exhausted || old.retirement?.isSealed == true) null else presentation,
                if (exhausted) old.generation else old.generation + 1,
                disposing || exhausted,
                old.retirement,
            )
            if (next.presentation != null && next.retirement != null) {
                // Do not publish across sealing: the short tracker lock spans only this CAS.
                if (next.retirement.publishIfOpen { current.compareAndSet(old, next) }) return true
                if (next.retirement.isSealed) return false
            } else if (current.compareAndSet(old, next)) return !next.disposed && next.presentation != null
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
        !publication.disposed && publication.presentation != null && current.get() === publication &&
            publication.retirement?.isSealed != true

    override fun toString() = "PrivateInputLifetime([redacted])"
}

/** Exact connection/action token. Local close does not revoke other editors under the same root. */
internal class PrivateInputToken internal constructor(
    private val lifetime: PrivateInputLifetime,
    private val publication: PrivateInputPublication,
    val snapshot: PrivateInputSnapshot,
    private val parent: PrivateInputToken? = null,
    private val additionalCurrent: (() -> Boolean)? = null,
    internal val connectionRetirement: PrivateInputConnectionRetirement? = null,
) {
    private val revoked = AtomicBoolean(false)
    internal val hasRetirementTracking: Boolean get() = publication.retirement != null
    fun isCurrent(): Boolean = !revoked.get() && lifetime.isCurrent(publication) &&
        (parent == null || parent.isCurrent()) && (additionalCurrent?.invoke() != false)
    fun revoke() { revoked.set(true) }

    /** A connection gets local close state while remaining subordinate to its captured session. */
    internal fun fork(additionalCurrent: (() -> Boolean)? = null): PrivateInputToken? = if (isCurrent()) {
        PrivateInputToken(lifetime, publication, snapshot, this, additionalCurrent, connectionRetirement).takeIf { it.isCurrent() }
    } else null

    internal fun beginConnectionCreation(): PrivateInputConnectionCreation? =
        publication.retirement?.beginConnection(::isCurrent)

    internal fun forConnection(connection: PrivateInputConnectionRetirement): PrivateInputToken =
        PrivateInputToken(lifetime, publication, snapshot, this, connectionRetirement = connection)

    /** Default behavior is unchanged. Tracked calls hold debt until their actual finally return. */
    internal fun <T> invoke(rejected: T, operation: () -> T): T {
        val retirement = publication.retirement
        if (retirement == null) return if (isCurrent()) operation() else rejected
        val invocation = retirement.beginCall(::isCurrent) ?: return rejected
        try { return operation() } finally { invocation.returned() }
    }

    internal fun mutate(operation: () -> Boolean): Boolean = invoke(false, operation)

    /** Both sides of a possibly blocking read are fenced. No admission lock spans the provider. */
    internal fun <T> read(rejected: T, operation: () -> T): T {
        if (!isCurrent()) return rejected
        val value = invoke(rejected, operation)
        return if (isCurrent()) value else rejected
    }

    override fun toString() = "PrivateInputToken([redacted])"
}
