package com.easysavevideo.app.save

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.theme.*
import com.easysavevideo.save.SaveRecord

@Composable
internal fun SaveAuthenticationGuidance(record: SaveRecord, runtime: AndroidSaveRuntime,
    onBrowser: (() -> Unit)?, canOpenBrowser: () -> Boolean) {
    // Freeze the owning Tasks publication ahead of the latest business availability callbacks.
    val admission = LocalPrivateInputAdmission.current
    val identity = AuthenticationTaskIdentity.from(record)
    if (!canReturnForAuthentication(identity)) return
    val generation = runtime.authenticationNavigationGeneration
    val command = remember(identity, generation) { AuthenticationBrowserCommand(identity, generation) }
    DisposableEffect(command) { onDispose { command.retire() } }
    val currentCanOpen by rememberUpdatedState(canOpenBrowser)
    val currentOpen by rememberUpdatedState(onBrowser)
    var result by remember(command) { mutableStateOf<AuthenticationNavigationResult?>(null) }
    val mediaIdle = !runtime.mediaTaskActivity.active
    val v = LocalEsvVisuals.current
    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(R.string.save_auth_browser_boundary), style = v.captionTextStyle, color = v.colors.muted)
        if (currentOpen != null) EsvButton(stringResource(R.string.save_auth_browser_open), onClick = {
            if (!admission()) return@EsvButton
            val open = currentOpen
            result = if (open == null || !currentCanOpen()) AuthenticationNavigationResult.UNAVAILABLE
                else runtime.returnToBrowserForAuthentication(command)
            if (result == AuthenticationNavigationResult.ACCEPTED) open?.invoke()
        }, enabled = result != AuthenticationNavigationResult.ACCEPTED && result != AuthenticationNavigationResult.ALREADY_USED &&
            mediaIdle && currentCanOpen() && runtime.authenticationBrowserAvailable())
        else EsvText(stringResource(R.string.save_auth_browser_unavailable), style = v.captionTextStyle)
        result?.takeIf { it != AuthenticationNavigationResult.ACCEPTED }?.let {
            EsvText(stringResource(authenticationNavigationText(it)), style = v.captionTextStyle, color = v.colors.error)
        }
    }
}

internal fun authenticationNavigationText(result: AuthenticationNavigationResult): Int = when (result) {
    AuthenticationNavigationResult.ACCEPTED -> R.string.save_auth_browser_boundary
    AuthenticationNavigationResult.ALREADY_USED -> R.string.save_auth_browser_used
    AuthenticationNavigationResult.STALE -> R.string.save_auth_browser_stale
    AuthenticationNavigationResult.NOT_ALLOWED -> R.string.save_auth_browser_not_allowed
    AuthenticationNavigationResult.UNAVAILABLE -> R.string.save_auth_browser_unavailable
}
