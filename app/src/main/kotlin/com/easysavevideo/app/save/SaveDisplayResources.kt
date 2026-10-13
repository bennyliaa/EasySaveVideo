package com.easysavevideo.app.save

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.discovery.DiscoveryOriginHistoryOutcome
import java.util.Locale

/** API31/32 language override is a Compose resource context, not the process default locale. */
@Composable private fun saveDisplayLocale(): Locale = LocalConfiguration.current.locales.let {
    if (it.isEmpty) Locale.ROOT else it[0]
}
@Composable internal fun saveIntegerText(value: Long): String = saveDisplayInteger(value, saveDisplayLocale())
@Composable internal fun saveDurationText(durationUs: Long): String = saveDisplayDuration(durationUs, saveDisplayLocale())
@Composable internal fun saveSizeText(bytes: Long): String {
    val display = saveDisplaySize(bytes, saveDisplayLocale())
    return stringResource(when (display.unit) {
        SaveDisplaySizeUnit.KIB -> R.string.save_display_kib
        SaveDisplaySizeUnit.MIB -> R.string.save_display_mib
        SaveDisplaySizeUnit.GIB -> R.string.save_display_gib
    }, display.amount)
}
@Composable internal fun saveSecondsText(seconds: Long): String = pluralStringResource(
    R.plurals.save_display_seconds, saveDisplayQuantity(seconds), saveIntegerText(seconds))
@Composable internal fun saveAudioTracksText(count: Int): String = pluralStringResource(
    R.plurals.save_display_audio_tracks, count, saveIntegerText(count.toLong()))
@Composable internal fun saveResolutionText(width: Int, height: Int): String = stringResource(
    R.string.save_display_resolution, saveIntegerText(width.toLong()), saveIntegerText(height.toLong()))

/** Maps only the already-reported history outcome, never establishes media success. */
internal fun saveDiscoveryOutcomeResource(history: DiscoveryOriginHistoryOutcome): Int = when (history) {
    DiscoveryOriginHistoryOutcome.RECORDED -> R.string.save_display_history_recorded
    DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE -> R.string.save_display_history_stale
    DiscoveryOriginHistoryOutcome.HISTORY_FAILED -> R.string.save_display_history_failed
    DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED -> R.string.save_display_history_unverified
}

/** Only generated numeric indices are localized; no webpage title or user filename is inspected. */
@Composable internal fun pageSaveCandidateLabel(indices: PageSaveCandidateIndices): String = stringResource(
    R.string.save_display_candidate_label, saveIntegerText(indices.videoNumber.toLong()),
    saveIntegerText(indices.resourceNumber.toLong()))
