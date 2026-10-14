package com.easysavevideo.app.browser

/** WebView has no completion/verification callback for clearCache; never call REQUESTED verified. */
internal enum class BrowserCacheClearResult { REQUESTED, FAILED }
internal fun requestBrowserCacheClear(action: () -> Unit): BrowserCacheClearResult = try {
    action()
    BrowserCacheClearResult.REQUESTED
} catch (_: RuntimeException) {
    BrowserCacheClearResult.FAILED
} catch (_: LinkageError) {
    BrowserCacheClearResult.FAILED
}
