package com.easysavevideo.app.recording

import com.easysavevideo.app.browser.privacy.PrivateCaptureAudio
import com.easysavevideo.app.browser.privacy.PrivateCaptureDisposition
import com.easysavevideo.app.browser.privacy.PrivateCaptureMode
import com.easysavevideo.app.browser.privacy.PrivateCaptureStartPermit
import com.easysavevideo.recording.CaptureDescriptor
import com.easysavevideo.recording.RecordingAudioChoice
import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure

/**
 * One transient BASIC owner, frozen before consent. No Activity, View, grant or second native lane.
 * Queue registration follows permit -> this owner -> native lease; no browser-source lease. No native API or
 * observer runs under those locks. Only the application runtime installs native observers; attached
 * Activity controls observe the runtime through a removable subscription.
 */
internal class PrivateRecordingNativeBinding(
    val descriptor: CaptureDescriptor,
    val ownerIdentity: Any,
    onNativeStarted: () -> Unit,
    onActualDrain: (Boolean) -> Unit,
) {
    private enum class State { RESERVED, REGISTERING, QUEUED, RUNNING, DRAINED, UNCERTAIN }
    private val lock = Any()
    private var state = State.RESERVED
    private var permit: PrivateCaptureStartPermit? = null
    private var nativeLease: RecordingNativeLease? = null
    private var disposition: PrivateCaptureDisposition? = null
    private var observedCapture = false
    private var startedObserver: (() -> Unit)? = onNativeStarted
    private var drainObserver: ((Boolean) -> Unit)? = onActualDrain
    private val drainWaiters = mutableListOf<() -> Unit>()

    init { require(descriptor.regionCaptureRecipe == null) { "Private BASIC only" } }

    val isStopRequested: Boolean get() = synchronized(lock) { disposition != null }
    /**
     * Source ownership only, including proven never-queued closure: true does not prove start or
     * submission. A submitted Save may still be verifying/publishing ordinary output.
     */
    val isCaptureDrained: Boolean get() = synchronized(lock) { state == State.DRAINED }
    internal val isReservationOpen: Boolean get() = synchronized(lock) {
        state == State.RESERVED && disposition == null
    }
    val isArmed: Boolean get() = synchronized(lock) {
        state == State.RESERVED && permit != null && disposition == null
    }
    internal val hasUndrainedOwner: Boolean get() = synchronized(lock) {
        state in setOf(State.REGISTERING, State.QUEUED, State.RUNNING, State.UNCERTAIN)
    }
    val discardRequested: Boolean get() = synchronized(lock) {
        disposition == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT
    }

    /** Stores only. Consumption belongs to the actual native queue boundary, never service-ready. */
    fun arm(value: PrivateCaptureStartPermit): Boolean = synchronized(lock) {
        val audio = if (descriptor.audioChoice == RecordingAudioChoice.NONE)
            PrivateCaptureAudio.SILENT else PrivateCaptureAudio.DEVICE_AUDIO
        if (state != State.RESERVED || disposition != null || permit != null ||
            value.descriptor.mode != PrivateCaptureMode.BASIC || value.descriptor.audio != audio) false
        else { permit = value; true }
    }

    /** Monotonic memory-only stop. KEEP must not cancel the Save operation/output owner. */
    fun requestStop(value: PrivateCaptureDisposition = PrivateCaptureDisposition.KEEP_VALID_OUTPUT) {
        synchronized(lock) {
            if (disposition == null || value == PrivateCaptureDisposition.DISCARD_UNPUBLISHED_OUTPUT)
                disposition = value
        }
    }

    /**
     * Atomic proof that no queue attempt has begun. A concurrent registration/queued/running owner
     * is retained until its real rejection/cleanup. A stopped reservation can never arm later.
     */
    fun closeNeverQueued(): Boolean {
        val delivery = synchronized(lock) {
            when (state) {
                State.DRAINED -> return true
                State.RESERVED -> {
                    if (disposition == null) disposition = PrivateCaptureDisposition.KEEP_VALID_OUTPUT
                    finishLocked(true)
                }
                else -> return false
            }
        }
        delivery()
        return true
    }

    /** Exact owner queue admission; it acquires the existing native lease and nothing else. */
    internal fun registerQueued(lease: RecordingNativeLease): Boolean {
        val authority = synchronized(lock) {
            if (state != State.RESERVED || disposition != null) return false
            val currentPermit = permit ?: return false
            state = State.REGISTERING
            currentPermit
        }
        val result = authority.consumeDeferred(ownerIdentity) {
            synchronized(lock) {
                check(state == State.REGISTERING && disposition == null)
                check(lease.tryAcquire())
                // Record acquisition before any subsequent step could fail.
                nativeLease = lease
                state = State.QUEUED
            }
        }
        // Result dispatch occurs outside both permit and owner locks. A failed registration that
        // acquired a lease is not a never-queued/drained proof; retain the actual native owner.
        if (result != PrivateCaptureStartPermit.Registration.REGISTERED) {
            val delivery = synchronized(lock) {
                if (result == PrivateCaptureStartPermit.Registration.FAILED && disposition == null)
                    disposition = PrivateCaptureDisposition.KEEP_VALID_OUTPUT
                if (state == State.REGISTERING && nativeLease == null) finishLocked(true)
                else if (result == PrivateCaptureStartPermit.Registration.FAILED && nativeLease != null) {
                    // Partial acquisition is an actual owned resource, not proof of no queue. An
                    // uncertain registration cannot enter the normal executor or free admission.
                    nativeLease?.abandon(); nativeLease?.keepAlive(this)
                    finishLocked(false)
                } else null
            }
            delivery?.invoke()
        }
        return result == PrivateCaptureStartPermit.Registration.REGISTERED
    }

    internal fun ownerStarted() = synchronized(lock) {
        check(state == State.QUEUED)
        state = State.RUNNING
    }

    /**
     * Check after slow preparation and immediately before each acquisition. This does not hold a
     * lock across a native call; an already-in-flight call remains owned until real cleanup.
     */
    internal fun requireStartAllowed(actualDescriptor: CaptureDescriptor) {
        val currentPermit = synchronized(lock) {
            if (actualDescriptor != descriptor || state != State.RUNNING || disposition != null) null else permit
        }
        // Never take the permit lock while holding this owner's lock: registration takes the reverse order.
        val permitted = currentPermit?.startedAllowed() == true
        val allowed = synchronized(lock) {
            permitted && actualDescriptor == descriptor && state == State.RUNNING && disposition == null && permit === currentPermit
        }
        if (!allowed) {
            requestStop()
            throw RecordingFailure(if (discardRequested) RecordingError.CANCELLED else RecordingError.CAPTURE_EMPTY)
        }
    }

    /** Post-acquisition observation, not permission or proof of playable output. At most once. */
    internal fun nativeCaptureAcquired() {
        val callback = synchronized(lock) {
            if (state != State.RUNNING || disposition != null || observedCapture) null
            else { observedCapture = true; startedObserver.also { startedObserver = null } }
        }
        try { callback?.invoke() } catch (_: Exception) { requestStop() }
    }

    fun isNativeEventCurrent(): Boolean = synchronized(lock) {
        state == State.RUNNING && disposition == null && observedCapture
    }

    /** Only the rejecting submitter (never a cancelled Future) may use this proof. */
    internal fun queueRejected() {
        val lease = synchronized(lock) { check(state == State.QUEUED); requireNotNull(nativeLease) }
        lease.rejectQueued()
        ownerReleased(true)
    }

    /** Called by the actual callable after session cleanup, or confirmed pre-session projection stop. */
    internal fun ownerReleased(confirmed: Boolean) {
        val delivery = synchronized(lock) {
            if (state == State.DRAINED || state == State.UNCERTAIN) return
            check(state == State.QUEUED || state == State.RUNNING)
            // Positive evidence is additionally tied to this exact acquired lease being free.
            val released = confirmed && nativeLease?.occupied == false
            finishLocked(released)
        }
        delivery()
    }

    /** A stop waiter may arrive after actual drain. Uncertainty never invokes a positive waiter. */
    fun whenDrained(done: () -> Unit) {
        val ready = synchronized(lock) {
            if (state == State.DRAINED) true else { drainWaiters += done; false }
        }
        if (ready) try { done() } catch (_: Exception) { }
    }

    /** Construct delivery under lock, invoke only after leaving every owner/permit lock. */
    private fun finishLocked(confirmed: Boolean): () -> Unit {
        state = if (confirmed) State.DRAINED else State.UNCERTAIN
        val observer = drainObserver
        drainObserver = null
        startedObserver = null
        if (!confirmed) return { try { observer?.invoke(false) } catch (_: Exception) { } }
        val waiters = drainWaiters.toList()
        permit = null; nativeLease = null; drainWaiters.clear()
        return {
            try { observer?.invoke(true) } catch (_: Exception) { }
            waiters.forEach { callback -> try { callback() } catch (_: Exception) { } }
        }
    }

    override fun toString() = "PrivateRecordingNativeBinding([redacted])"
}
