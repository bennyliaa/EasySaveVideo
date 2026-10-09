package com.easysavevideo.app.browser

/** Conservative one-navigation proof. Addresses are functional memory only, never diagnostics. */
class DiscoveryNavigationFence(private val expectedAddress: String) {
    private var started = false
    private var visited = false
    private var finished = false
    private var valid = true

    fun onStarted(address: String): Boolean {
        valid = valid && !started && address == expectedAddress
        started = true
        return valid
    }

    // pushState/replaceState/history and same-address reloads can bypass onPageStarted.
    // Only the first exact history observation of the original document is admissible.
    fun onVisitedHistory(address: String): Boolean {
        valid = valid && !finished && !visited && address == expectedAddress
        visited = true
        return valid
    }

    /** A first history callback arriving after READY might be a same-document mutation. */
    fun onFinished() { finished = true }

    fun accepts(address: String?): Boolean = valid && started && address == expectedAddress
    override fun toString() = "DiscoveryNavigationFence([redacted])"
}
