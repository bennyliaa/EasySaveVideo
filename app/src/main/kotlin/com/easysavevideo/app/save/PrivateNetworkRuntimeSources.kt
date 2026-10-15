package com.easysavevideo.app.save

import com.easysavevideo.app.browser.privacy.BrowserSavePartition
import com.easysavevideo.app.browser.privacy.BrowserSaveRequestContext
import com.easysavevideo.app.browser.privacy.BrowserSaveResumeAuthority
import com.easysavevideo.app.browser.privacy.PrivateSessionLease
import com.easysavevideo.save.*
import com.easysavevideo.save.network.RequestContextLeaseStore
import com.easysavevideo.save.network.RequestContextLiveAuthority
import java.util.concurrent.ConcurrentHashMap

/**
 * Actual Runtime source references, indexed by exact claim and task. The owner supplies the single
 * lifecycle state machine. A map, lease expiry, or callback completion is never release evidence.
 * P is the exact memory-only preparation, not its URL/hash or a reconstructed durable value.
 */
internal class PrivateNetworkRuntimeSources<P : Any>(
    private val leases: RequestContextLeaseStore,
    private val clearReleasedPreparation: (P) -> Boolean,
    private val signalSourceExit: (String, PrivateNetworkSourceBinding) -> Unit,
) {
    private val claims = ConcurrentHashMap<Any, Entry>()
    private val tasks = ConcurrentHashMap<String, Entry>()

    inner class Entry internal constructor(
        val session: PrivateSessionLease,
        source: SourceUrl,
        resumeAuthority: BrowserSaveResumeAuthority,
        claim: Any,
    ) {
        val seed = PrivateNetworkSourceSeed.create()
        private val references = Any()
        private var source: SourceUrl? = source
        private var resumeAuthority: BrowserSaveResumeAuthority? = resumeAuthority
        private val credentials = mutableMapOf<Any, MutableSet<RequestContextRef>>()
        private var prepared: P? = null
        private var referencesRetired = false
        @Volatile var binding: PrivateNetworkSourceBinding? = null; private set
        val owner = PrivateNetworkSourceOwner(session, seed, claim, ::retireSourceReferences)

        fun bindTask(taskId: String, operationId: String): PrivateNetworkSourceBinding {
            val exact = owner.bindTask(taskId, operationId) { signalSourceExit(taskId, it) }
            binding = exact
            if (tasks.putIfAbsent(taskId, this) != null) throw SaveFailure(SaveError.INVALID_REQUEST)
            return exact
        }

        /** Main-thread caller only: the host capability fixes the original source and document. */
        fun refreshForExplicitResume(): BrowserSaveRequestContext? =
            synchronized(references) { if (referencesRetired) null else resumeAuthority }
                ?.refreshForExplicitResume()

        /** Registry admission must precede credential capture, even when there is no Cookie header. */
        fun issueLease(claim: Any, context: BrowserSaveRequestContext): RequestContextRef {
            val url = synchronized(references) {
                if (referencesRetired || claims[claim] !== this ||
                    (context.partition as? BrowserSavePartition.Private)?.lease !== session ||
                    context.resumeAuthority !== resumeAuthority)
                    throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                source ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            }
            val ref = leases.put(url, cookie = context.cookie, userAgent = context.userAgent,
                ttlMillis = 60_000, liveAuthority = RequestContextLiveAuthority { session.isLive() && seed.isLive() })
            synchronized(references) {
                if (referencesRetired || claims[claim] !== this || !session.isLive() || !seed.isLive()) {
                    leases.revoke(ref)
                    throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
                }
                credentials.getOrPut(claim) { linkedSetOf() }.add(ref)
            }
            return ref
        }

        fun attachPreparation(value: P) = synchronized(references) {
            if (referencesRetired || prepared != null) throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            prepared = value
        }
        fun ownsPreparation(value: P): Boolean = synchronized(references) { !referencesRetired && prepared === value }

        internal fun revokeClaimCredentials(claim: Any) = synchronized(references) {
            credentials.remove(claim)?.forEach(leases::revoke)
        }

        /** Called ONLY by the owner after positive Core detach or actual exact-claim retirement. */
        private fun retireSourceReferences() {
            synchronized(references) {
                seed.revoke()
                credentials.values.forEach { refs -> refs.forEach(leases::revoke) }
                credentials.clear()
                resumeAuthority?.retire(); resumeAuthority = null
                // No cancel, IO, native close, or await is permitted here. A failed consistency
                // check leaves the owner registered/quarantined and retains the exact preparation.
                prepared?.let { if (!clearReleasedPreparation(it)) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) }
                prepared = null; source = null; referencesRetired = true
            }
            binding?.let { tasks.remove(it.taskId, this) }
            claims.entries.removeIf { it.value === this }
        }
        override fun toString() = "PrivateNetworkRuntimeSource([redacted])"
    }

    /** Install the exact owner before the caller may queue source work. Rejection still needs retirement. */
    fun register(context: BrowserSaveRequestContext, source: SourceUrl, claim: Any,
        stopPreparation: () -> Unit): Entry {
        val session = (context.partition as? BrowserSavePartition.Private)?.lease
            ?: throw SaveFailure(SaveError.INVALID_REQUEST)
        val capability = context.resumeAuthority ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        val entry = try { Entry(session, source, capability, claim) }
        catch (failure: Exception) { capability.retire(); throw failure }
        entry.owner.installPreparationStop(stopPreparation)
        if (!entry.owner.startQueued { claims.putIfAbsent(claim, entry) == null }) {
            // No work may start, but the real reserved claim still supplies its release callback.
            claims.putIfAbsent(claim, entry)
            throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
        }
        return entry
    }

    fun forClaim(claim: Any): Entry? = claims[claim]
    fun forTask(taskId: String): Entry? = tasks[taskId]

    /** Same source registration; fresh exact claim is installed before new credentials/control work. */
    fun resumeQueued(entry: Entry, claim: Any, operationId: String): Boolean {
        val binding = entry.binding ?: return false
        if (tasks[binding.taskId] !== entry) return false
        return entry.owner.resumeQueued(claim, operationId, { signalSourceExit(binding.taskId, it) }) {
            claims.putIfAbsent(claim, entry) == null
        }
    }

    /** Only the injected Core observer may call this after actual transfer release and raw CAS. */
    fun sourceDetached(taskId: String, operationId: String, binding: PrivateNetworkSourceBinding) {
        val entry = tasks[taskId] ?: throw SaveFailure(SaveError.RECOVERY_REQUIRED)
        if (!entry.owner.sourceDetached(taskId, operationId, binding)) throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    }

    /** Caller is SaveExecutionRetirement.retired, never a Future completion or a task-state observer. */
    fun executionRetired(claim: Any, retainsLiveSource: (Entry) -> Boolean) {
        val entry = claims[claim] ?: return
        entry.revokeClaimCredentials(claim)
        val dormant = entry.session.isLive() && entry.seed.isLive() && retainsLiveSource(entry)
        if (!dormant) entry.owner.requestSourceRetirement()
        entry.owner.executionRetired(claim, if (dormant) PrivateNetworkSourceOwner.Retirement.LIVE_RESUMABLE_SOURCE
            else PrivateNetworkSourceOwner.Retirement.RELEASED_SOURCE)
        claims.remove(claim, entry)
    }

    /** Cancelling/expiring a dormant task uses its already-proven release; active work still waits. */
    fun retireTask(taskId: String) { tasks[taskId]?.owner?.requestSourceRetirement() }
    fun retireUnavailable(unavailable: (String, PrivateNetworkSourceBinding) -> Boolean) {
        tasks.forEach { (id, entry) -> entry.binding?.let { if (unavailable(id, it)) entry.owner.requestSourceRetirement() } }
    }
}
