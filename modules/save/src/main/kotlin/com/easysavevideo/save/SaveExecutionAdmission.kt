package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import java.util.IdentityHashMap

/**
 * Shared admission for reserved, accepted and executing work. There is no waiting queue.
 * Admission never nests its monitor with Coordinator/storage work. Preparing/executing markers
 * protect exact claims across two-phase core mutations. Coordinator code may read only the
 * lock-free memory-only recovery view while holding its monitor. The free-space probe runs outside
 * this monitor; any intervening owner/configuration change rejects its result.
 * Hooks are memory-only exact-owner native fences, never Coordinator/storage/network callbacks.
 */
class SaveExecutionAdmission(
    maxDirectTasks: Int = 1,
    private val maxArtifactBytes: Long,
    private val availableBytes: () -> Long? = { null },
    private val resourcesUnreleased: () -> Boolean = { false },
    private val onFirstDirect: () -> Boolean = { true },
    private val onLastDirectReleased: () -> Unit = {},
    private val movCapable: Boolean = false,
) {
    class Claim internal constructor(internal val direct: Boolean) {
        internal var taskId: String? = null
        internal var operationId: String? = null
        internal var runtime: Any? = null
        @Volatile internal var released = false
        internal var preparing = false
        internal var executing = false
        @Volatile internal var entered: EnteredClaim? = null
    }
    /** A private-enter-issued memory projection, never an independent claim or authorization. */
    internal class EnteredClaim private constructor(
        private val admission: SaveExecutionAdmission,
        private val claim: Claim,
        private val runtime: Any,
        val taskId: String,
        val operationId: String,
    ) {
        private val live = java.util.concurrent.atomic.AtomicBoolean(true)
        fun requireLive() {
            if (!live.get() || claim.entered !== this || claim.released)
                throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
        /** Identity only, for already-recorded historical facts; never execution authorization. */
        fun assertIssuer(expected: SaveExecutionAdmission) {
            if (admission !== expected) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        }
        fun assertAdmission(expected: SaveExecutionAdmission) {
            assertIssuer(expected); requireLive()
        }
        fun assertRuntime(expected: Any) {
            if (runtime !== expected) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
            requireLive()
        }
        fun revoke() { live.set(false) }
        companion object {
            fun issue(admission: SaveExecutionAdmission, claim: Claim, runtime: Any,
                taskId: String, operationId: String) = EnteredClaim(admission, claim, runtime, taskId, operationId)
        }
    }
    /** Must be called outside Coordinator/storage monitors, after enter's action accepted work. */
    internal fun captureEntered(claim: Claim, runtime: Any): EnteredClaim? = synchronized(lock) {
        if (!claims.containsKey(claim) || !claim.executing || claim.runtime !== runtime) null
        else claim.entered?.also { it.assertRuntime(runtime) }
    }
    data class Snapshot(val maxDirectTasks: Int, val directCount: Int, val exclusive: Boolean, val recoveryRequired: Boolean) {
        val totalCount: Int get() = directCount + if (exclusive) 1 else 0
        val busy: Boolean get() = totalCount != 0 || recoveryRequired
    }
    private val lock = Any()
    private val claims = IdentityHashMap<Claim, Unit>()
    private var maximum = checkedMaximum(maxDirectTasks)
    private var version = 0L
    @Volatile private var poisoned = false
    /**
     * Lock-free publication fence. The supplied recovery callback MUST be memory-only and must
     * not call admission, Coordinator, storage or IO. A live native drain is not permanent poison.
     */
    val recoveryRequired: Boolean get() = unreleased()
    init { require(maxArtifactBytes > 0) }

    fun updateMaxDirectTasks(value: Int) = synchronized(lock) {
        val checked = checkedMaximum(value)
        if (maximum != checked) { maximum = checked; version++ }
    }
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(maximum, directCount(), claims.keys.any { !it.direct }, unreleased())
    }
    /** Outer adapters use this only for actual uncertain release, never ordinary occupied work. */
    fun markRecoveryRequired() = synchronized(lock) { poisoned = true }
    fun owns(claim: Claim): Boolean = synchronized(lock) { claims.containsKey(claim) }
    fun isDirect(claim: Claim): Boolean = claim.direct

    fun tryAcquireDirect(): Claim? {
        val observed = synchronized(lock) {
            val count = directCount()
            if (unreleased() || claims.keys.any { !it.direct } || count >= maximum) return null
            if (count == 0 && !movCapable) return acquireFirstDirect()
            version to count
        }
        // MOV has simultaneous raw + canonical extents for every live Direct claim. This is a
        // conservative free-space threshold, not a filesystem quota or a subtraction of media.
        val multiplier = if (movCapable) 2L * (observed.second + 1L) else 2L
        if (maxArtifactBytes > Long.MAX_VALUE / multiplier) return null
        val free = try { availableBytes() } catch (_: Exception) { null }
        if (free == null || free < 0 || free < maxArtifactBytes * multiplier) return null
        return synchronized(lock) {
            if (version != observed.first || unreleased() || claims.keys.any { !it.direct } ||
                directCount() != observed.second || directCount() >= maximum) return@synchronized null
            if (observed.second == 0) acquireFirstDirect() else addClaim(true)
        }
    }
    fun tryAcquireExclusive(): Claim? = synchronized(lock) {
        if (unreleased() || claims.isNotEmpty()) null else addClaim(false)
    }

    /** A released/stale token never affects another claim. Poison retains the entire batch. */
    fun release(claim: Claim): Boolean = synchronized(lock) {
        if (!claims.containsKey(claim) || claim.preparing || claim.executing || unreleased()) return@synchronized false
        if (claim.direct && directCount() == 1) {
            try { onLastDirectReleased() } catch (_: Exception) { poisoned = true; return@synchronized false }
        }
        claims.remove(claim); claim.released = true; version++; true
    }

    /** Protect the reservation while the Core action runs WITHOUT the admission monitor. */
    internal fun <T> prepare(
        claim: Claim,
        method: TaskMethod,
        taskId: String,
        operationId: String,
        action: () -> Pair<T, Any?>,
    ): T? {
        synchronized(lock) {
            if (!claims.containsKey(claim) || claim.preparing || claim.executing ||
                claim.runtime != null || claim.taskId != null || claim.direct != (method == TaskMethod.DIRECT)) return null
            claim.preparing = true
        }
        var attachedRuntime: Any? = null
        try {
            val (result, runtime) = action()
            attachedRuntime = runtime
            return result
        } finally {
            synchronized(lock) {
                // release cannot remove a preparing claim; no rebind is permitted during the action.
                if (attachedRuntime != null) {
                    claim.taskId = taskId; claim.operationId = operationId; claim.runtime = attachedRuntime
                }
                claim.preparing = false
            }
        }
    }

    /** Admission remains valid after lowering the limit; validity is exact ownership, not count. */
    internal fun <T> enter(
        claim: Claim, taskId: String, operationId: String, runtime: Any,
        legacyAttach: Boolean = false, action: () -> T,
    ): T? {
        synchronized(lock) {
            if (!claims.containsKey(claim) || claim.preparing || claim.executing) return null
            if (legacyAttach && claim.runtime == null && claim.taskId == null) {
                claim.taskId = taskId; claim.operationId = operationId; claim.runtime = runtime
            }
            if (claim.runtime !== runtime || claim.taskId != taskId || claim.operationId != operationId) return null
            claim.executing = true
            claim.entered = EnteredClaim.issue(this, claim, runtime, taskId, operationId)
        }
        try { return action() }
        catch (error: Throwable) {
            synchronized(lock) { claim.entered?.revoke(); claim.entered = null; claim.executing = false }
            throw error
        }
    }
    internal fun exit(claim: Claim, runtime: Any) = synchronized(lock) {
        if (claims.containsKey(claim) && claim.runtime === runtime) {
            claim.entered?.revoke(); claim.entered = null; claim.executing = false
        }
    }
    private fun directCount() = claims.keys.count { it.direct }
    private fun addClaim(direct: Boolean): Claim = Claim(direct).also { claims[it] = Unit; version++ }
    private fun acquireFirstDirect(): Claim? {
        if (!try { onFirstDirect() } catch (_: Exception) { poisoned = true; false }) return null
        return addClaim(true)
    }
    private fun unreleased(): Boolean {
        if (poisoned) return true
        // Ordinary native draining may clear only after actual release. It blocks and retains
        // current claims without becoming permanent process poison. Explicit restart gates
        // and unknown probe failures are permanent; transport pools latch their own poison.
        return try { resourcesUnreleased() } catch (_: Exception) { poisoned = true; true }
    }
    private fun checkedMaximum(value: Int): Int { require(value == 1 || value == 2); return value }
}
