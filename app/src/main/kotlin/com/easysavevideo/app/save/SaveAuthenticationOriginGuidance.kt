package com.easysavevideo.app.save

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.browser.SystemBrowserHost
import com.easysavevideo.app.theme.*
import com.easysavevideo.save.SaveRecord

/** Separate explicit origin selection; the ordinary Browser fallback remains available above. */
@Composable
internal fun SaveAuthenticationOriginGuidance(record: SaveRecord, runtime: AndroidSaveRuntime,
    browser: SystemBrowserHost, onBrowser: (() -> Unit)?, canOpenBrowser: () -> Boolean) {
    // Freeze the owning Tasks publication ahead of the latest business availability callbacks.
    val admission = LocalPrivateInputAdmission.current
    val identity = AuthenticationTaskIdentity.from(record)
    if (!canReturnForAuthentication(identity)) return
    val generation = runtime.authenticationNavigationGeneration
    val browserRevision = browser.saveInspectionRevision
    val command = remember(identity, generation, browserRevision) { AuthenticationBrowserCommand(identity, generation) }
    DisposableEffect(command) { onDispose { command.retire() } }
    val currentCanOpen by rememberUpdatedState(canOpenBrowser)
    val currentOpen by rememberUpdatedState(onBrowser)
    var result by remember(command) { mutableStateOf<AuthenticationNavigationResult?>(null) }
    val v = LocalEsvVisuals.current
    val available = runtime.authenticationOriginAvailable(identity.taskId, browser)
    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(if (available) R.string.save_auth_origin_boundary else R.string.save_auth_origin_unavailable),
            style = v.captionTextStyle, color = v.colors.muted)
        EsvButton(stringResource(R.string.save_auth_origin_open), onClick = {
            if (!admission()) return@EsvButton
            val open = currentOpen
            result = if (open == null || !currentCanOpen()) AuthenticationNavigationResult.UNAVAILABLE
                else runtime.returnToOriginForAuthentication(command, browser, browserRevision)
            if (result == AuthenticationNavigationResult.ACCEPTED) open?.invoke()
        }, enabled = available && currentOpen != null && !runtime.mediaTaskActivity.active &&
            currentCanOpen() && runtime.authenticationBrowserAvailable() &&
            result != AuthenticationNavigationResult.ACCEPTED && result != AuthenticationNavigationResult.ALREADY_USED)
        result?.takeIf { it != AuthenticationNavigationResult.ACCEPTED }?.let {
            EsvText(stringResource(if (it == AuthenticationNavigationResult.UNAVAILABLE)
                R.string.save_auth_origin_unavailable else authenticationNavigationText(it)),
                style = v.captionTextStyle, color = v.colors.error)
        }
    }
}
