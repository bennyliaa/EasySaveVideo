package com.easysavevideo.app.vault

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals

/** Main calls this once, outside NavHost. Revocation never changes its composition identity. */
@Composable internal fun VaultPlaybackSurfaceHost(host: VaultProtectedHost) {
    val visuals = LocalEsvVisuals.current
    AndroidView(factory = { host.playbackContainer },
        modifier = Modifier.fillMaxWidth().padding(horizontal = visuals.spacingMedium, vertical = 0.dp)
            .padding(top = if (host.playbackSlotRetained) 88.dp else 0.dp)
            .height(if (host.playbackSlotRetained) 200.dp else 0.dp))
    DisposableEffect(host) { onDispose { host.playbackCompositionRemoved() } }
}

/** Only process-local ticket and bounded scalar state cross into Compose; no player/read capability. */
@Composable internal fun VaultPlaybackControls(host: VaultProtectedHost, view: VaultPlaybackView, accepts: () -> Boolean) {
    val ticket = view.ticket ?: return
    EsvText(stringResource(when (view.status) {
        VaultPlaybackStatus.PREPARING -> R.string.vault_playback_preparing
        VaultPlaybackStatus.PLAYING -> R.string.vault_playback_playing
        VaultPlaybackStatus.ENDED -> R.string.vault_playback_ended
        VaultPlaybackStatus.RETIRING -> R.string.vault_playback_retiring
        VaultPlaybackStatus.UNAVAILABLE -> R.string.vault_playback_unavailable
        else -> R.string.vault_playback_ready
    }))
    // The independently retained native slot occupies this protected page area.
    Spacer(Modifier.height(240.dp))
    val ready = view.status in setOf(VaultPlaybackStatus.READY, VaultPlaybackStatus.PLAYING,
        VaultPlaybackStatus.PAUSED, VaultPlaybackStatus.ENDED)
    val duration = view.durationMillis
    EsvText(stringResource(R.string.vault_playback_position, view.positionMillis.coerceAtLeast(0) / 1000,
        (duration ?: 0L).coerceAtLeast(0) / 1000), style = LocalEsvVisuals.current.captionTextStyle)
    EsvButton(stringResource(if (view.status == VaultPlaybackStatus.PLAYING) R.string.vault_playback_pause
        else R.string.vault_playback_play), enabled = ready, onClick = {
        if (accepts()) {
            if (view.status == VaultPlaybackStatus.PLAYING) host.pausePlayback(ticket) else host.playPlayback(ticket)
        }
    })
    val canSeek = ready && view.seekable && duration != null && duration >= 0
    EsvButton(stringResource(R.string.vault_playback_back), enabled = canSeek, onClick = {
        if (accepts() && canSeek) host.seekPlayback(ticket,
            (view.positionMillis.coerceAtLeast(0) - VaultPlaybackLimits.SEEK_STEP_MILLIS).coerceAtLeast(0))
    })
    EsvButton(stringResource(R.string.vault_playback_forward), enabled = canSeek, onClick = {
        if (accepts() && canSeek) {
            val position = view.positionMillis.coerceIn(0, checkNotNull(duration))
            val target = position + minOf(VaultPlaybackLimits.SEEK_STEP_MILLIS, duration - position)
            host.seekPlayback(ticket, target)
        }
    })
    EsvButton(stringResource(R.string.vault_playback_restart), enabled = canSeek, onClick = {
        if (accepts() && canSeek) host.seekPlayback(ticket, 0)
    })
    EsvButton(stringResource(R.string.vault_playback_close), onClick = { if (accepts()) host.closePlayback(ticket) })
}
