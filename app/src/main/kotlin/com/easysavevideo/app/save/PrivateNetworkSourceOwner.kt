package com.easysavevideo.app.save

import com.easysavevideo.app.browser.privacy.PrivateSessionLease
import com.easysavevideo.app.browser.privacy.PrivateWorkKind
import com.easysavevideo.save.PrivateNetworkSourceBinding
import com.easysavevideo.save.PrivateNetworkSourceSeed
import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure

/** One source registration survives preparation -> exact task handoff. Never a native release proof. */
internal class PrivateNetworkSourceOwner(
    session: PrivateSessionLease,
    val seed: PrivateNetworkSourceSeed,
    executionClaim: Any,
    private val retireSourceReferences: () -> Unit,
) {
    private enum class State { OPEN, RETIRING, RETIRED, UNCERTAIN }
    private val lock = Any()
    private var state = State.OPEN
    private var executionClaim: Any? = executionClaim
    private var dormant = false
    private var stopDispatching = false
    private var retirementRequested = false
    private var referencesCleared = false
    private var stopped = false
    private var stop: (() -> Unit)? = null
    private var binding: PrivateNetworkSourceBinding? = null
    private var operationId: String? = null
    private val registration = session.register(PrivateWorkKind.NETWORK_CANCEL) { requestExit() }
        ?: throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)

    /** Preparation stop is installed before any source IO; a racing exit is replayed immediately. */
    fun installPreparationStop(action: () -> Unit) {
        val notify = synchronized(lock) {
            check(state == State.OPEN && binding == null && stop == null)
            stop = action
            if (stopped) stopDispatching = true
            stopped
        }
        if (notify) dispatchStop(action)
    }

    /** The same registration is retained; no exit-visible gap between old and new source owners. */
    fun bindTask(taskId: String, operationId: String, stopTask: (PrivateNetworkSourceBinding) -> Unit): PrivateNetworkSourceBinding {
        return synchronized(lock) {
            if (state != State.OPEN || stopped || binding != null || !seed.isLive())
                throw SaveFailure(SaveError.SOURCE_RESELECTION_REQUIRED)
            val exact = seed.bindTask(taskId)
            binding = exact
            this.operationId = operationId
            stop = { stopTask(exact) }
            exact
        }
    }

    /** Existing source registration atomically accepts a fresh explicit Resume claim. */
    fun resumeQueued(freshClaim: Any, operationId: String,
        stopTask: (PrivateNetworkSourceBinding) -> Unit, registerOwner: () -> Boolean): Boolean =
        registration.continueQueued {
            synchronized(lock) {
                val exact = binding
                if (state != State.OPEN || stopped || !dormant || executionClaim != null || exact == null || !exact.isLive()) false
                else {
                    // Retain this new obligation even if queue registration rejects or throws.
                    dormant = false; executionClaim = freshClaim; this.operationId = operationId
                    stop = { stopTask(exact) }
                    registerOwner()
                }
            }
        }

    /** Only short owner queue registration is allowed inside this callback, never IO/native work. */
    fun startQueued(registerOwner: () -> Boolean): Boolean = registration.startQueued {
        synchronized(lock) {
            if (state != State.OPEN || stopped || !seed.isLive()) false else registerOwner()
        }
    }

    /** Explicit task cancellation/expiry uses the same stop-and-real-release barrier as session exit. */
    fun requestSourceRetirement() = requestExit()

    private fun requestExit() {
        val action = synchronized(lock) {
            if (stopped || state == State.RETIRED) return
            stopped = true
            seed.revoke()
            stopDispatching = stop != null
            stop
        }
        // No session/owner lock is held while signalling the actual coordinator/preparation.
        if (action != null) dispatchStop(action)
        else if (synchronized(lock) { dormant && executionClaim == null }) retire()
    }

    private fun dispatchStop(action: () -> Unit) {
        try { action() }
        catch (_: Exception) { synchronized(lock) { stopDispatching = false; state = State.UNCERTAIN }; return }
        val finish = synchronized(lock) {
            stopDispatching = false
            retirementRequested || (dormant && executionClaim == null)
        }
        if (finish) retire()
    }

    /**
     * Core positive exact-binding event after actual transfer finally and raw CAS, not a release command.
     * retireSourceReferences must only revoke credentials and drop already-released exact source refs.
     * It must not wait for IO/native/claim release, cancel-and-await, or infer release from idle flags.
     */
    fun sourceDetached(taskId: String, operationId: String, exact: PrivateNetworkSourceBinding): Boolean {
        val accepted = synchronized(lock) {
            binding === exact && exact.taskId == taskId && this.operationId == operationId
        }
        if (!accepted) return false
        retire()
        // Acceptance is separate from source-registration drain; a stop dispatch may still finish.
        return synchronized(lock) { state != State.UNCERTAIN }
    }

    enum class Retirement { RELEASED_SOURCE, LIVE_RESUMABLE_SOURCE }

    /**
     * Called only by actual SaveExecutionRetirement. Runtime derives disposition from exact core
     * task/source state. An enum is not release evidence. Live WAITING_USER sources remain registered.
     */
    fun executionRetired(exactClaim: Any, disposition: Retirement): Boolean {
        val keep = synchronized(lock) {
            if (executionClaim !== exactClaim) return false
            executionClaim = null
            if (state != State.OPEN) return state == State.RETIRED
            if (disposition == Retirement.LIVE_RESUMABLE_SOURCE && !stopped && binding?.isLive() == true) {
                dormant = true; true
            } else false
        }
        return keep || retire()
    }

    private fun retire(): Boolean {
        val clear = synchronized(lock) {
            retirementRequested = true
            if (state == State.RETIRED) return true
            if (state == State.UNCERTAIN || stopDispatching) return false
            when (state) {
                State.OPEN -> { state = State.RETIRING; true }
                State.RETIRING -> {
                    if (!referencesCleared) return false
                    state = State.RETIRED; stop = null; false
                }
                else -> return false
            }
        }
        if (clear) {
            try { retireSourceReferences() }
            catch (_: Exception) { synchronized(lock) { state = State.UNCERTAIN }; return false }
            synchronized(lock) { referencesCleared = true }
            return retire()
        }
        registration.complete()
        return true
    }
    override fun toString() = "PrivateNetworkSourceOwner([redacted])"
}
