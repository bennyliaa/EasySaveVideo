package com.easysavevideo.app.browser.privacy

import java.lang.ref.WeakReference

/**
 * Process-local identity ownership for providers that reuse the same InputConnection object.
 * The short registry lock never spans provider work. A cleanup claim excludes publication of a
 * replacement wrapper until cleanup returns; a newer published lease rejects old queued cleanup.
 * Weak identity keys do not retain the provider, View, token, presentation or Activity.
 */
internal class PrivateInputDelegateLeases {
    private val entries = mutableListOf<PrivateInputDelegateLease>()

    @Synchronized
    fun acquire(delegate: Any, admission: PrivateInputToken): PrivateInputDelegateLease? {
        // Check inside the same registry transition that replaces ownership. A create that was
        // blocked in the provider must not retire a newer lease after its session became stale.
        // Admission checks are in-memory only; no provider operation is invoked under this lock.
        if (!admission.isCurrent()) return null
        entries.removeAll { it.reference.get() == null }
        val previous = entries.firstOrNull { it.reference.get() === delegate }
        if (previous?.cleaning == true) return null
        if (previous != null) entries.remove(previous)
        return PrivateInputDelegateLease(this, WeakReference(delegate)).also { entries.add(it) }
    }

    @Synchronized
    internal fun isCurrent(lease: PrivateInputDelegateLease): Boolean =
        lease in entries && !lease.cleaning && lease.reference.get() != null

    @Synchronized
    internal fun beginCleanup(lease: PrivateInputDelegateLease): Boolean {
        if (!isCurrent(lease)) return false
        lease.cleaning = true
        return true
    }

    @Synchronized
    internal fun finishCleanup(lease: PrivateInputDelegateLease) {
        if (lease.cleaning) entries.remove(lease)
    }

    @Synchronized
    internal fun abandon(lease: PrivateInputDelegateLease) {
        // Construction failure/reentrancy cannot release an already claimed cleanup. Only the
        // cleanup's finally block may end that exclusion, after BOTH provider operations return.
        if (!lease.cleaning) entries.remove(lease)
    }
}

internal class PrivateInputDelegateLease internal constructor(
    private val registry: PrivateInputDelegateLeases,
    internal val reference: WeakReference<Any>,
) {
    internal var cleaning = false
    fun isCurrent(): Boolean = registry.isCurrent(this)
    fun cleanupIfOwned(cleanup: () -> Unit): Boolean {
        if (!registry.beginCleanup(this)) return false
        try { cleanup() } finally { registry.finishCleanup(this) }
        return true
    }
    fun abandon() = registry.abandon(this)
}

internal val privateInputDelegateLeases = PrivateInputDelegateLeases()
