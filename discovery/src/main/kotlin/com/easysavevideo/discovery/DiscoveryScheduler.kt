package com.easysavevideo.discovery

import com.easysavevideo.core.model.DiscoveryMode

enum class DiscoveryState { NO_SOURCES, SUSPENDED, IDLE, FILLING, BUFFERED, BACKOFF, EXHAUSTED, CAPACITY_REACHED, UNSUPPORTED_FILTER, FAILED }
data class DiscoveryStatus(
    val state: DiscoveryState,
    val availableCount: Int,
    val materializedPages: Int,
    val requestsThisRound: Int,
    val inFlightCount: Int,
    val health: Map<String, ProviderHealth>,
    val lastError: DiscoveryError? = null,
    val providers: List<ProviderDiscoveryProgress> = emptyList(),
    val powerPolicy: DiscoveryPowerPolicyStatus = DiscoveryPowerPolicy.evaluate(DiscoveryPowerSignals(), DiscoveryMode.BALANCED),
    val roundActive: Boolean = false,
    val powerPolicyUpdatePending: Boolean = false,
)

/** Pure request accounting. Completing a round never silently starts another one. */
class DiscoveryScheduler(private var limits: DiscoveryLimits = DiscoveryLimits()) {
    var foreground: Boolean = false
        private set
    var discoveryVisible: Boolean = false
        private set
    var saveActive: Boolean = false
        private set
    var recordingActive: Boolean = false
        private set
    var mode: DiscoveryMode = DiscoveryMode.BALANCED
        private set
    var roundActive: Boolean = false
        private set
    var requestsThisRound: Int = 0
        private set
    var powerSignals: DiscoveryPowerSignals = DiscoveryPowerSignals()
        private set
    var powerPolicy: DiscoveryPowerPolicyStatus = DiscoveryPowerPolicy.evaluate(powerSignals, mode, limits)
        private set
    private val inFlight = mutableSetOf<String>()
    val gateOpen: Boolean get() = foreground && discoveryVisible && !saveActive && !recordingActive && powerPolicy.allowsRequests
    val inFlightCount: Int get() = inFlight.size
    fun visibility(foreground: Boolean, discoveryVisible: Boolean) { this.foreground = foreground; this.discoveryVisible = discoveryVisible }
    fun priority(saveActive: Boolean, recordingActive: Boolean) { this.saveActive = saveActive; this.recordingActive = recordingActive }
    fun mode(mode: DiscoveryMode) { this.mode = mode; updatePolicy() }
    fun powerSignals(signals: DiscoveryPowerSignals) {
        if (powerSignals == signals) return
        powerSignals = signals; updatePolicy(); finishRound()
    }
    fun limits(limits: DiscoveryLimits) { this.limits = limits; updatePolicy() }
    private fun updatePolicy() { powerPolicy = DiscoveryPowerPolicy.evaluate(powerSignals, mode, limits) }
    fun beginRound(availableCount: Int): Boolean {
        // An explicit request made before foreground/visibility may wait for that gate. A policy
        // transition itself is never demand, and enabled/unknown cannot enqueue future work.
        if (!powerPolicy.allowsRequests || roundActive || availableCount >= powerPolicy.lowWatermark) return false
        requestsThisRound = 0; roundActive = true; return true
    }
    fun canLaunch(providerId: String, availableCount: Int): Boolean = gateOpen && roundActive &&
        requestsThisRound < powerPolicy.maxRequestsPerRound && availableCount < powerPolicy.highWatermark &&
        inFlight.size < powerPolicy.concurrency && providerId !in inFlight
    fun reserve(providerId: String, availableCount: Int): Boolean {
        if (!canLaunch(providerId, availableCount)) return false
        inFlight += providerId; requestsThisRound++; return true
    }
    fun release(providerId: String) { inFlight -= providerId }
    fun finishRound() { roundActive = false }
    fun pause() { inFlight.clear() }
    fun suspend() { finishRound(); pause() }
}
