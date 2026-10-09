package com.easysavevideo.app.browser

/** URL is functional in-memory state only. A new admission belongs to one WebView generation. */
class NavigationAdmission(expectedAddress: String?) {
    private val allowedStarts = mutableSetOf<String>().apply { expectedAddress?.let(::add) }
    private var awaitingHistoryStart = expectedAddress == null
    private var started: String? = null
    private var stopped = false
    fun allowRedirect(address: String) { if (!stopped) allowedStarts += address }
    fun onStarted(address: String): Boolean {
        if (stopped || !awaitingHistoryStart && address !in allowedStarts) return false
        awaitingHistoryStart = false; started = address; allowedStarts += address; return true
    }
    fun expects(address: String): Boolean = !stopped && address in allowedStarts
    fun accepts(address: String): Boolean = !stopped && started == address
    fun hasStarted(): Boolean = !stopped && started != null
    fun cancel() { stopped = true; started = null; allowedStarts.clear() }
}
