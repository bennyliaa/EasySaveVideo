package com.easysavevideo.save

import com.easysavevideo.core.model.OperationContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two permanent transport instances, pinned by execution-owner identity for the entire retry loop.
 * This is a binding owner, not an executor: callers transfer through the acquired concrete port.
 * Releasing a binding never replaces a transport or clears an observed uncertain resource release.
 */
class FixedDirectTransferPool(
    private val first: DirectTransferPort,
    private val second: DirectTransferPort,
) : DirectTransferPort {
    private val lock = Any()
    private var firstOwner: Any? = null
    private var secondOwner: Any? = null
    private val recoveryRequired = AtomicBoolean(false)

    init { require(first !== second) { "Distinct transfer instances are required" } }

    /** Existing owners retain their exact binding; a poisoned pool never creates a new binding. */
    fun acquire(owner: Any): DirectTransferPort? {
        // Delegate calls are deliberately outside the binding monitor.
        requiresProcessRecovery()
        return synchronized(lock) {
            when {
                firstOwner === owner -> first
                secondOwner === owner -> second
                recoveryRequired.get() -> null
                firstOwner == null -> first.also { firstOwner = owner }
                secondOwner == null -> second.also { secondOwner = owner }
                else -> null
            }
        }
    }

    /** Removes only this exact owner, at most once. Resource recovery remains independently latched. */
    fun release(owner: Any): Boolean {
        requiresProcessRecovery()
        return synchronized(lock) {
            when {
                firstOwner === owner -> { firstOwner = null; true }
                secondOwner === owner -> { secondOwner = null; true }
                else -> false
            }
        }
    }

    override fun requiresProcessRecovery(): Boolean {
        // Do not short-circuit or consult only occupied slots: idle transports may still be draining.
        observeRecovery(first)
        observeRecovery(second)
        return recoveryRequired.get()
    }

    private fun observeRecovery(port: DirectTransferPort) {
        val unreleased = try { port.requiresProcessRecovery() }
        catch (_: Exception) { true }
        // Latch each observation immediately, before consulting the other port.
        if (unreleased) recoveryRequired.set(true)
    }

    override fun transfer(
        resource: SelectedMediaResource,
        artifact: StagingArtifact,
        checkpoint: DownloadCheckpoint?,
        context: OperationContext,
        limits: SaveLimits,
        progress: TransferProgress,
    ): TransferResult = throw SaveFailure(SaveError.BUSY)
}
