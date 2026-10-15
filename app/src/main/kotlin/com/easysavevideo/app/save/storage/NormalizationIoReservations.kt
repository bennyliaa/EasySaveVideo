package com.easysavevideo.app.save.storage

import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * One storage-owned exclusion table, including the interval before a workspace row exists.
 * This is resource ownership, not execution authorization. [Token.check] must be memory-only
 * and capture the exact accepted invocation/local scope. Durable checks belong to the caller's
 * short owner-lock sections. No callback or filesystem call is made while publishing snapshots.
 */
internal class NormalizationIoReservations(internal val ownerLock: Any) {
    enum class Stage { RAW_PROBE, PREPARE, REMUX, TRANSCODE, FINISH, PROOF_HANDOFF, INSPECT, ADOPT, VERIFY, COMMIT, PUBLICATION, CLEANUP }

    /** A cell is kept only in the registry and the issued token; callers cannot replace it. */
    internal class State(stage: Stage, key: NormalizationKey?) {
        @Volatile var key: NormalizationKey? = key
        @Volatile var stage: Stage = stage
        @Volatile var registered = true
        @Volatile var revoked = false
        @Volatile var uncertain = false
        val calls = AtomicInteger()
        val obligations = AtomicInteger()
        val issuedObligations = java.util.Collections.newSetFromMap(IdentityHashMap<Obligation, Boolean>())
        val retained = ConcurrentLinkedQueue<Any>()
    }
    class Token private constructor(
        internal val registry: NormalizationIoReservations,
        val owner: NormalizationOwner,
        internal val scope: AndroidDirectExecutionBridge.Scope?,
        private val check: () -> Unit,
        private val state: State,
    ) {
        val key: NormalizationKey? get() = state.key
        val stage: Stage get() = state.stage
        internal val registered: Boolean get() = state.registered
        /** Reads only captured memory. Does not enter a storage/admission/native monitor. */
        fun requireLive() {
            if (!state.registered || state.revoked || state.uncertain) throw NormalizationRecovery()
            check()
        }
        internal fun requestRevoke() { state.revoked = true }
        internal fun stopped(): Boolean = state.revoked || !state.registered || try { check(); false } catch (_: Exception) { true }
        companion object {
            internal fun issue(registry: NormalizationIoReservations, owner: NormalizationOwner,
                check: () -> Unit, state: State, scope: AndroidDirectExecutionBridge.Scope?) = Token(registry, owner, scope, check, state)
        }
    }
    class Obligation private constructor(internal val token: Token) {
        internal var released = false
        companion object { internal fun issue(token: Token) = Obligation(token) }
    }

    private val entries = IdentityHashMap<Token, State>()
    @Volatile private var snapshot: List<Token> = emptyList()
    @Volatile private var poisoned = false

    fun reserve(owner: NormalizationOwner, stage: Stage, check: () -> Unit,
        key: NormalizationKey? = null, scope: AndroidDirectExecutionBridge.Scope? = null): Token = synchronized(ownerLock) {
        if (poisoned) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        check()
        if (key != null && key.owner != owner) fail()
        requireLegacyAvailable(owner.taskId, owner.artifactId, owner.commitId)
        val state = State(stage, key)
        Token.issue(this, owner, check, state, scope).also { entries[it] = state; publish() }
    }
    fun requireExact(token: Token, owner: NormalizationOwner = token.owner,
        key: NormalizationKey? = token.key, stage: Stage = token.stage) = synchronized(ownerLock) {
        if (token.registry !== this || !entries.containsKey(token) || token.owner != owner ||
            token.key != key || token.stage != stage) fail()
        token.requireLive()
    }
    fun bindGeneration(token: Token, key: NormalizationKey) = synchronized(ownerLock) {
        requireExact(token)
        if (token.owner != key.owner || (token.key != null && token.key != key)) fail()
        state(token).key = key
    }
    fun advance(token: Token, expectedStage: Stage, nextStage: Stage) = synchronized(ownerLock) {
        requireExact(token, stage = expectedStage)
        if (!allowed(expectedStage, nextStage) || state(token).calls.get() != 0 || state(token).obligations.get() != 0) fail()
        state(token).stage = nextStage
    }
    fun <T> withIo(token: Token, block: () -> T): T {
        synchronized(ownerLock) { requireExact(token); state(token).calls.incrementAndGet() }
        try {
            token.requireLive()
            val result = block()
            token.requireLive() // A late return never becomes a successful handoff.
            return result
        } finally { synchronized(ownerLock) { state(token).calls.decrementAndGet() } }
    }
    /** Only the exact retained OUTPUT may spend its bounded cleanup reserve after revocation. */
    internal fun <T> withTranscodeFinalizationIo(anchor: AndroidNormalizationFiles.AsyncOutputDescriptor,
        obligation: Obligation, block: () -> T): T {
        // Never call the descriptor/attempt/session while holding the reservation monitor.
        anchor.requireFinalizationObligation(obligation)
        val token = obligation.token
        fun exact() {
            if (poisoned || token.registry !== this) fail()
            val current = state(token)
            if (!current.registered || current.uncertain || current.stage != Stage.TRANSCODE ||
                obligation.released || obligation !in current.issuedObligations) fail()
        }
        synchronized(ownerLock) { exact(); state(token).calls.incrementAndGet() }
        try {
            anchor.requireFinalizationObligation(obligation)
            val result = block()
            anchor.requireFinalizationObligation(obligation)
            synchronized(ownerLock) { exact() }
            return result
        } finally { synchronized(ownerLock) { check(state(token).calls.decrementAndGet() >= 0) } }
    }
    /** Register BEFORE entering a constructor/open. An exceptional unknown result is retained. */
    fun retain(token: Token): Obligation = synchronized(ownerLock) {
        requireExact(token)
        state(token).obligations.incrementAndGet()
        Obligation.issue(token).also { state(token).issuedObligations.add(it) }
    }
    /** Only the resource-owning worker calls this after an actual positive release. */
    fun release(obligation: Obligation) = synchronized(ownerLock) {
        val token = obligation.token
        if (token.registry !== this || !entries.containsKey(token) || obligation.released || !state(token).issuedObligations.remove(obligation)) fail()
        obligation.released = true
        check(state(token).obligations.decrementAndGet() >= 0)
    }
    fun markUncertain(token: Token, retained: Any) = synchronized(ownerLock) {
        if (token.registry !== this || !entries.containsKey(token)) fail()
        state(token).retained.add(retained)
        state(token).uncertain = true; token.requestRevoke(); poisoned = true
        failedRegistries.add(this)
    }
    /** Revocation is non-blocking even while another worker owns the storage monitor. */
    fun revoke(token: Token) {
        if (token.registry !== this || !token.registered || snapshot.none { it === token }) fail()
        token.requestRevoke()
    }
    /** Revokes immediately; retirement never guesses release from Future/interrupt/FD validity. */
    fun retire(token: Token): Boolean = synchronized(ownerLock) {
        if (token.registry !== this || !entries.containsKey(token)) return@synchronized false
        token.requestRevoke()
        if (state(token).uncertain || state(token).calls.get() != 0 || state(token).obligations.get() != 0) return@synchronized false
        state(token).registered = false; entries.remove(token); publish(); true
    }
    fun requireLegacyAvailable(taskId: String? = null, artifactId: String? = null, commitId: String? = null) {
        if (poisoned) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        if (taskId == null && artifactId == null && commitId == null) fail()
        if (snapshot.any { (taskId != null && it.owner.taskId == taskId) ||
                (artifactId != null && it.owner.artifactId == artifactId) || (commitId != null && it.owner.commitId == commitId) }) fail()
    }
    fun requireNoReservations() {
        if (poisoned) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        if (snapshot.isNotEmpty()) throw SaveFailure(SaveError.BUSY)
    }
    fun hasAnyReservations(): Boolean = snapshot.isNotEmpty()
    fun hasReservation(artifactId: String): Boolean = snapshot.any { it.owner.artifactId == artifactId }
    /** Healthy ACTIVE work does not block itself; stopped/exited owners continue to drain. */
    fun resourcesUnreleased(): Boolean = poisoned || snapshot.any { it.stopped() }
    fun requiresProcessRecovery(): Boolean = poisoned
    private fun state(token: Token): State = entries[token] ?: fail()
    private fun publish() { snapshot = entries.keys.toList() }
    private fun allowed(from: Stage, to: Stage): Boolean = when (from) {
        Stage.RAW_PROBE -> to == Stage.PREPARE
        Stage.PREPARE -> to == Stage.REMUX || to == Stage.TRANSCODE
        Stage.REMUX, Stage.TRANSCODE -> to == Stage.FINISH
        Stage.FINISH -> to == Stage.PROOF_HANDOFF
        Stage.PROOF_HANDOFF -> to == Stage.ADOPT
        Stage.COMMIT -> to == Stage.PUBLICATION || to == Stage.CLEANUP
        Stage.PUBLICATION -> to == Stage.CLEANUP
        else -> false
    }
    private fun fail(): Nothing = throw NormalizationRecovery()
    private companion object { val failedRegistries = ConcurrentLinkedQueue<NormalizationIoReservations>() }
}
