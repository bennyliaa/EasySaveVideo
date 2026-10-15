package com.easysavevideo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.browser.BrowserCookieClearResult
import com.easysavevideo.app.browser.BrowserNotice
import com.easysavevideo.app.browser.BrowserPermissionKind

/** Resolve at the display boundary, including the API31/32 Compose locale override. */
@Composable
internal fun browserNoticeText(notice: BrowserNotice): String = when (notice) {
    is BrowserNotice.Text -> notice.value
    is BrowserNotice.CookieClear -> stringResource(browserCookieClearResource(notice.result))
    is BrowserNotice.PermissionDenied -> stringResource(browserPermissionDeniedResource(notice.kind),
        notice.origin ?: stringResource(R.string.browser_permission_unknown_origin))
}

internal fun browserPermissionDeniedResource(kind: BrowserPermissionKind): Int = when (kind) {
    BrowserPermissionKind.CAMERA_OR_MICROPHONE -> R.string.browser_permission_camera_microphone_denied
    BrowserPermissionKind.LOCATION -> R.string.browser_permission_location_denied
}

internal fun browserCookieClearResource(result: BrowserCookieClearResult): Int = when (result) {
    BrowserCookieClearResult.REJECTED -> R.string.browser_cookie_clear_rejected
    BrowserCookieClearResult.NOT_STARTED -> R.string.browser_cookie_clear_not_started
    BrowserCookieClearResult.REMOVED -> R.string.browser_cookie_clear_removed
    BrowserCookieClearResult.NONE_REMOVED -> R.string.browser_cookie_clear_none_removed
    BrowserCookieClearResult.UNCONFIRMED -> R.string.browser_cookie_clear_unconfirmed
}
