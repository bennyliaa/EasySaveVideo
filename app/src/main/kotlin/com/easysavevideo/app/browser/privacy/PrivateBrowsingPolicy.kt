package com.easysavevideo.app.browser.privacy

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Opaque ownership names only. No browsing-derived input is accepted. */
internal class PrivateProfileName private constructor(internal val value: String) {
    override fun equals(other: Any?) = other is PrivateProfileName && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "PrivateProfileName([redacted])"
    companion object {
        private val pattern = Regex("esv_private_v1_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun random() = parse("esv_private_v1_${UUID.randomUUID()}")!!
        fun parse(value: String): PrivateProfileName? = value.takeIf { pattern.matches(it) }?.let(::PrivateProfileName)
    }
}
internal enum class PrivateMarkerPhase { ACTIVE, DATA_CLEARED }
internal data class PrivateProfileMarker(val name: PrivateProfileName, val phase: PrivateMarkerPhase)
internal data class PrivateProfileCapabilities(val multiProfile: Boolean, val deleteData: Boolean, val muteAudio: Boolean) {
    val cleanupEligible: Boolean get() = multiProfile && deleteData
    val eligible: Boolean get() = cleanupEligible && muteAudio
}
internal enum class PrivateBrowsingPhase { COLD, RECOVERING, READY, RETIREMENT_REQUIRED, OPENING, ACTIVE, DRAINING, CLEARING, QUARANTINED, UNAVAILABLE }
internal data class PrivateBrowsingStatus(val phase: PrivateBrowsingPhase, val pendingProfiles: Int = 0)

/** Runtime-owned authority; no durable serialization or URL-bearing display. */
internal class PrivateBrowsingSession internal constructor(internal val name: PrivateProfileName) {
    private val live = AtomicBoolean(true)
    fun isLive(): Boolean = live.get()
    internal fun revoke() { live.set(false) }
    override fun toString() = "PrivateBrowsingSession([redacted])"
}

/** Callbacks return on the policy's owner thread. IO must be serialized and off-main. */
internal interface PrivateProfileJournal {
    fun read(done: (List<PrivateProfileMarker>?) -> Unit)
    fun write(markers: List<PrivateProfileMarker>, done: (Boolean) -> Unit)
}
internal fun interface PrivatePolicyScheduler { fun after(delayMillis: Long, action: () -> Unit): () -> Unit }

/**
 * Single-owner state machine. All calls/callbacks run on main in the Android adapter.
 * A timeout is only a visible quarantine; it never fakes release or invalidates a real late callback.
 */
internal class PrivateBrowsingPolicy(
    private val backend: PrivateProfileBackend,
    private val journal: PrivateProfileJournal,
    private val scheduler: PrivatePolicyScheduler,
    private val newName: () -> PrivateProfileName = PrivateProfileName::random,
    private val changed: (PrivateBrowsingStatus) -> Unit = {},
) {
    companion object { const val MAX_MARKERS = 64; const val WAIT_MILLIS = 15_000L }
    var status = PrivateBrowsingStatus(PrivateBrowsingPhase.COLD); private set
    var session: PrivateBrowsingSession? = null; private set
    private var markers = emptyList<PrivateProfileMarker>()
    private var operation = 0L
    private var cancelTimeout: (() -> Unit)? = null
    private var openCallback: ((PrivateBrowsingSession?) -> Unit)? = null
    private fun finishOpen(value: PrivateBrowsingSession?) { val callback = openCallback; openCallback = null; callback?.invoke(value) }
    private fun ready() {
        val eligible = try { backend.capabilities().eligible } catch (_: Exception) { false }
        publish(when {
            !eligible -> PrivateBrowsingPhase.UNAVAILABLE
            markers.size >= MAX_MARKERS -> PrivateBrowsingPhase.RETIREMENT_REQUIRED
            else -> PrivateBrowsingPhase.READY
        })
    }
    private fun publish(phase: PrivateBrowsingPhase) {
        status = PrivateBrowsingStatus(phase, markers.size); changed(status)
    }
    private fun begin(rejectTimeout: Boolean = false, timedOut: () -> Unit = {}): Long {
        cancelTimeout?.invoke(); check(operation < Long.MAX_VALUE); val token = ++operation
        cancelTimeout = scheduler.after(WAIT_MILLIS) {
            if (operation == token) {
                if (rejectTimeout) { check(operation < Long.MAX_VALUE); operation++ }
                publish(PrivateBrowsingPhase.QUARANTINED); timedOut()
            }
        }
        return token
    }
    private fun accept(token: Long): Boolean {
        if (operation != token) return false
        cancelTimeout?.invoke(); cancelTimeout = null; check(operation < Long.MAX_VALUE); operation++
        return true
    }
    private fun fail(token: Long) { if (accept(token)) publish(PrivateBrowsingPhase.QUARANTINED) }
    private fun persist(next: List<PrivateProfileMarker>, rejectTimeout: Boolean = false, failed: () -> Unit = {}, success: () -> Unit) {
        val token = begin(rejectTimeout, if (rejectTimeout) failed else ({}))
        try { journal.write(next.toList()) { ok ->
            if (accept(token)) {
                if (ok) { markers = next.toList(); success() }
                else { publish(PrivateBrowsingPhase.QUARANTINED); failed() }
            }
        } } catch (_: Exception) { if (operation == token) { fail(token); failed() } }
    }
    fun recover() {
        if (status.phase != PrivateBrowsingPhase.COLD) return
        val eligible = try { backend.capabilities().cleanupEligible } catch (_: Exception) { false }
        if (!eligible) { publish(PrivateBrowsingPhase.UNAVAILABLE); return }
        publish(PrivateBrowsingPhase.RECOVERING)
        val token = begin()
        try { journal.read { loaded ->
            if (!accept(token)) return@read
            if (loaded == null || loaded.size > MAX_MARKERS || loaded.map { it.name }.toSet().size != loaded.size) {
                publish(PrivateBrowsingPhase.QUARANTINED); return@read
            }
            markers = loaded.toList()
            recoverNext(loaded.toList())
        } } catch (_: Exception) { fail(token) }
    }
    private fun recoverNext(remaining: List<PrivateProfileMarker>) {
        val marker = remaining.firstOrNull() ?: run { ready(); return }
        publish(PrivateBrowsingPhase.RECOVERING)
        if (marker.phase == PrivateMarkerPhase.DATA_CLEARED) {
            try { backend.retireUnloadedMetadata(marker.name) }
            catch (_: Exception) { publish(PrivateBrowsingPhase.QUARANTINED); return }
            persist(markers.filterNot { it.name == marker.name }) { recoverNext(remaining.drop(1)) }
        } else {
            try { backend.create(marker.name); backend.fence(marker.name) }
            catch (_: Exception) { publish(PrivateBrowsingPhase.QUARANTINED); return }
            clear(marker.name) { recoverNext(remaining.drop(1)) }
        }
    }
    /** No profile exists until its opaque ACTIVE marker is durably acknowledged. */
    fun open(done: (PrivateBrowsingSession?) -> Unit): Boolean {
        if (status.phase != PrivateBrowsingPhase.READY || markers.size >= MAX_MARKERS || session != null) return false
        val capabilities = try { backend.capabilities() } catch (_: Exception) { null }
        if (capabilities?.eligible != true) { publish(PrivateBrowsingPhase.UNAVAILABLE); return false }
        val name = try { newName() } catch (_: Exception) { publish(PrivateBrowsingPhase.QUARANTINED); return false }
        if (markers.any { it.name == name }) { publish(PrivateBrowsingPhase.QUARANTINED); return false }
        openCallback = done; publish(PrivateBrowsingPhase.OPENING)
        persist(markers + PrivateProfileMarker(name, PrivateMarkerPhase.ACTIVE), rejectTimeout = true, failed = { finishOpen(null) }) {
            try { backend.create(name) }
            catch (_: Exception) { publish(PrivateBrowsingPhase.QUARANTINED); finishOpen(null); return@persist }
            val accepted = PrivateBrowsingSession(name)
            session = accepted; publish(PrivateBrowsingPhase.ACTIVE); finishOpen(accepted)
        }
        return true
    }
    /** Cancels a queued opening without cancelling or pretending to roll back an in-flight disk write. */
    fun cancelOpening() {
        if (openCallback == null) return
        cancelTimeout?.invoke(); cancelTimeout = null; check(operation < Long.MAX_VALUE); operation++
        publish(PrivateBrowsingPhase.QUARANTINED); finishOpen(null)
    }
    /**
     * Revocation and both stop requests are immediate. Neither stop request waits for the other.
     * done callbacks mean actual owner release, not merely cancellation requested.
     */
    fun retire(expected: PrivateBrowsingSession,
        retirePages: (done: () -> Unit) -> Unit,
        drainSave: (done: () -> Unit) -> Unit,
    ): Boolean {
        if (session !== expected || !expected.isLive()) return false
        expected.revoke(); publish(PrivateBrowsingPhase.DRAINING)
        val token = begin()
        var starting = true; var pagesReleased = false; var savesReleased = false; var stopFailed = false
        fun settle() {
            if (starting || !pagesReleased || !savesReleased || stopFailed || operation != token) return
            if (accept(token)) clear(expected.name) { session = null; ready() }
        }
        try { backend.fence(expected.name) } catch (_: Exception) { stopFailed = true }
        try { retirePages { pagesReleased = true; settle() } } catch (_: Exception) { stopFailed = true }
        try { drainSave { savesReleased = true; settle() } } catch (_: Exception) { stopFailed = true }
        starting = false
        if (stopFailed) fail(token) else settle()
        return true
    }
    private fun clear(name: PrivateProfileName, complete: () -> Unit) {
        publish(PrivateBrowsingPhase.CLEARING)
        val token = begin()
        try { backend.clearData(name) {
            if (!accept(token)) return@clearData
            persist(markers.map { if (it.name == name) it.copy(phase = PrivateMarkerPhase.DATA_CLEARED) else it }) { complete() }
        } } catch (_: Exception) { fail(token) }
    }
    fun owns(expected: PrivateBrowsingSession): Boolean = session === expected && expected.isLive() && status.phase == PrivateBrowsingPhase.ACTIVE
}

/** The caller receives ownership only after binding succeeds; failure never leaks an unpublished resource. */
internal fun <T> createPrivateOwnedResource(create: () -> T, bind: (T) -> Unit, release: (T) -> Unit): T {
    val resource = create()
    try { bind(resource); return resource }
    catch (failure: Exception) {
        try { release(resource) } catch (_: Exception) { /* Preserve the original technical failure. */ }
        throw failure
    }
}
