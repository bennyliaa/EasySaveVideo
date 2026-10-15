package com.easysavevideo.app.browser

/** Ephemeral display data only. No permission request, callback or persistence is retained. */
internal sealed interface BrowserNotice {
    data class Text(val value: String) : BrowserNotice {
        override fun toString(): String = "BrowserNotice.Text"
    }
    data class CookieClear(val result: BrowserCookieClearResult) : BrowserNotice
    data class PermissionDenied(val kind: BrowserPermissionKind, val origin: String?) : BrowserNotice {
        override fun toString(): String = "BrowserNotice.PermissionDenied($kind)"
    }
}

internal enum class BrowserPermissionKind { CAMERA_OR_MICROPHONE, LOCATION }
