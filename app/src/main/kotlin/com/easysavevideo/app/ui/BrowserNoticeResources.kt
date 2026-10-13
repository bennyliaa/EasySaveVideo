package com.easysavevideo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.browser.BrowserNotice
import com.easysavevideo.app.browser.BrowserPermissionKind

/** Resolve at the display boundary, including the API31/32 Compose locale override. */
@Composable
internal fun browserNoticeText(notice: BrowserNotice): String = when (notice) {
    is BrowserNotice.Text -> notice.value
    is BrowserNotice.PermissionDenied -> stringResource(browserPermissionDeniedResource(notice.kind),
        notice.origin ?: stringResource(R.string.browser_permission_unknown_origin))
}

internal fun browserPermissionDeniedResource(kind: BrowserPermissionKind): Int = when (kind) {
    BrowserPermissionKind.CAMERA_OR_MICROPHONE -> R.string.browser_permission_camera_microphone_denied
    BrowserPermissionKind.LOCATION -> R.string.browser_permission_location_denied
}
