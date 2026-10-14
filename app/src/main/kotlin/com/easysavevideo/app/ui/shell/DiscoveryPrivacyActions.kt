package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.discovery.DiscoveryControlRequest
import com.easysavevideo.app.discovery.DiscoveryControlTarget
import com.easysavevideo.app.discovery.DiscoveryLocalAction
import com.easysavevideo.app.discovery.DiscoveryPresentationPolicy

/** Live controller facts; neither this snapshot nor a prompt owns an accepted operation. */
internal data class DiscoveryPrivacyAvailability(
    val ready: Boolean,
    val storageUnavailable: Boolean,
    val target: DiscoveryControlTarget?,
    val providerIds: Set<String>,
    val busy: Boolean,
)

/** Identity is the confirmation authority, even when two requests have equal values. */
internal class DiscoveryPrivacyPrompt(val request: DiscoveryControlRequest)

internal data class DiscoveryPrivacyState(
    val prompt: DiscoveryPrivacyPrompt? = null,
    val stale: Boolean = false,
)

/**
 * View-lifetime authority for local Discovery controls. No IO, scheduling, or completion state
 * lives here: the existing controller owns submitted work and its busy/result state.
 */
internal class DiscoveryPrivacyActions(
    private val canAct: () -> Boolean,
    private val availability: () -> DiscoveryPrivacyAvailability,
    private val submit: (DiscoveryControlRequest) -> Boolean,
    private val refreshStatistics: (DiscoveryControlTarget) -> Unit,
    private val changed: (DiscoveryPrivacyState) -> Unit,
) {
    var state = DiscoveryPrivacyState(); private set
    private var closed = false
    private var dispatching = false

    private fun active(): Boolean = !closed && canAct()
    private fun usable(value: DiscoveryPrivacyAvailability): Boolean =
        value.ready && !value.storageUnavailable && !value.busy && value.target != null

    fun begin(action: DiscoveryLocalAction, providerId: String? = null) {
        if (!active() || dispatching || state.prompt != null) return
        // A malformed invocation must not silently change an action's scope.
        if ((action == DiscoveryLocalAction.RESET_PROVIDER_PROGRESS) != (providerId != null)) return
        val current = availability()
        if (!usable(current) || !active()) return
        val request = DiscoveryControlRequest(requireNotNull(current.target), action, providerId)
        if (!DiscoveryPresentationPolicy.validControl(request, current.target, current.providerIds)) return
        publish(DiscoveryPrivacyState(prompt = DiscoveryPrivacyPrompt(request)))
    }

    fun canConfirm(expected: DiscoveryPrivacyPrompt): Boolean =
        !dispatching && active() && state.prompt === expected && validNow(expected.request) &&
            state.prompt === expected

    fun confirm(expected: DiscoveryPrivacyPrompt) {
        // A late callback must not retire a newer, value-equal confirmation.
        if (closed || dispatching || state.prompt !== expected) return
        if (!canConfirm(expected)) { invalidate(expected); return }
        dispatching = true
        try {
            // Consume before publishing or calling a port. Reentrant clicks cannot resubmit it.
            publish(DiscoveryPrivacyState())
            // The publication callback may have navigated, disposed, or changed the target.
            if (!validNow(expected.request)) {
                if (active()) publish(DiscoveryPrivacyState(stale = true))
                return
            }
            if (!submit(expected.request) && active()) publish(DiscoveryPrivacyState(stale = true))
        } finally {
            dispatching = false
        }
    }

    fun cancel(expected: DiscoveryPrivacyPrompt) {
        if (closed || state.prompt !== expected) return
        publish(state.copy(prompt = null))
    }

    fun reconcile() {
        if (closed || dispatching) return
        val prompt = state.prompt ?: return
        if (!canConfirm(prompt)) invalidate(prompt)
    }

    /** This port can only read local record counts; it cannot demand or materialize a page. */
    fun refresh() {
        if (!active() || dispatching) return
        val current = availability()
        if (!usable(current) || !active()) return
        dispatching = true
        try {
            refreshStatistics(requireNotNull(current.target))
        } finally {
            dispatching = false
        }
    }

    fun dismissStale() { if (active() && state.stale) publish(state.copy(stale = false)) }

    fun close() {
        if (closed) return
        closed = true
        // Do not publish into a disposed view or cancel work the controller already accepted.
        state = DiscoveryPrivacyState()
    }

    private fun validNow(request: DiscoveryControlRequest): Boolean {
        if (!active()) return false
        val current = availability()
        return usable(current) &&
            DiscoveryPresentationPolicy.validControl(request, current.target, current.providerIds) && active()
    }

    private fun invalidate(expected: DiscoveryPrivacyPrompt) {
        if (closed || state.prompt !== expected) return
        publish(DiscoveryPrivacyState(stale = active()))
    }

    private fun publish(value: DiscoveryPrivacyState) {
        if (closed) return
        state = value
        changed(value)
    }
}
