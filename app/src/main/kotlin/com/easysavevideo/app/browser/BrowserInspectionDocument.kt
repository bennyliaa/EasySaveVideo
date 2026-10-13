package com.easysavevideo.app.browser

/** Host-owned, ephemeral document address. Never a navigation, credential or task authority. */
internal class BrowserInspectionDocument {
    var address: String? = null; private set
    var revision: Long = 0; private set
    private var origin: String? = null

    fun started() { revision++; address = null; origin = null }

    /** Called only after the host accepts the current request's page-finished event. */
    fun finished(url: String, viewUrl: String?) {
        revision++
        address = accepted(url, viewUrl)
        origin = BrowserInputPolicy.origin(address)
    }

    /** Every history observation retires earlier scans, even same-address replaceState/ABA. */
    fun history(url: String?, viewUrl: String?, reload: Boolean, current: Boolean, ready: Boolean) {
        revision++
        address = accepted(url, viewUrl)?.takeIf {
            !reload && current && ready && origin != null && BrowserInputPolicy.origin(it) == origin
        }
    }

    private fun accepted(url: String?, viewUrl: String?): String? = url?.takeIf {
        it == viewUrl && BrowserInputPolicy.address(it)?.value == it
    }
    override fun toString() = "BrowserInspectionDocument([redacted])"
}
