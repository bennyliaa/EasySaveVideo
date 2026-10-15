package com.easysavevideo.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*

/** App-shell controls. Actions carry the exact runtime owner; no browser route owns this session. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RecordingSessionControls(presentation: PrivateRecordingPresentationUi, modifier: Modifier = Modifier) {
    val ui = presentation
    val v = LocalEsvVisuals.current
    var audio by remember(ui.presentationId, ui.audioChoice) { mutableStateOf(ui.audioChoice) }
    var confirmDiscard by remember(ui.presentationId) { mutableStateOf(false) }
    // Back may close unsubmitted setup. Once submitted it belongs to normal app/browser navigation.
    BackHandler(enabled = ui.stage in setOf(PrivateRecordingStage.CONFIGURE,
        PrivateRecordingStage.WAITING_SYSTEM, PrivateRecordingStage.CONTINUE)) { ui.handleBack() }
    Column(modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
        .background(v.colors.surface).padding(v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(R.string.private_record_title), style = v.titleTextStyle)
        EsvText(stringResource(when (ui.stage) {
            PrivateRecordingStage.CONFIGURE -> R.string.private_record_disclosure
            PrivateRecordingStage.WAITING_SYSTEM -> R.string.private_record_waiting
            PrivateRecordingStage.CONTINUE -> R.string.private_record_continue_help
            PrivateRecordingStage.ARMING -> R.string.private_record_arming
            PrivateRecordingStage.RECORDING -> R.string.private_record_active
            PrivateRecordingStage.STOPPING -> R.string.private_record_stopping
            PrivateRecordingStage.VERIFYING -> R.string.private_record_verifying
            PrivateRecordingStage.COMMITTING -> R.string.private_record_committing
            PrivateRecordingStage.SUCCEEDED -> R.string.private_record_saved
            PrivateRecordingStage.FAILED -> R.string.private_record_failed
            PrivateRecordingStage.CANCELLED -> R.string.private_record_cancelled
            PrivateRecordingStage.BLOCKED -> if (ui.recoveryBlocked) R.string.private_record_recovery_required
                else R.string.private_record_unavailable
        }), style = v.captionTextStyle)
        if (ui.stage == PrivateRecordingStage.CONFIGURE || ui.stage == PrivateRecordingStage.CONTINUE) {
            if (ui.stage == PrivateRecordingStage.CONTINUE)
                EsvText(stringResource(R.string.private_record_disclosure), style = v.captionTextStyle)
            if (Build.VERSION.SDK_INT <= 32)
                EsvText(stringResource(R.string.private_record_legacy_recents), style = v.captionTextStyle)
            EsvText(stringResource(R.string.private_record_limits), style = v.captionTextStyle)
            EsvText(stringResource(R.string.private_record_audio_help), style = v.captionTextStyle)
        }
        if (ui.stage == PrivateRecordingStage.CONFIGURE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                PrivateRecordingAudioChoice.entries.forEach { choice ->
                    EsvButton(stringResource(if (choice == PrivateRecordingAudioChoice.SILENT)
                        R.string.private_record_silent else R.string.private_record_device_audio),
                        onClick = { audio = choice }, modifier = Modifier.semantics { selected = audio == choice })
                }
            }
            EsvButton(stringResource(R.string.private_record_start), enabled = ui.canStart, onClick = { ui.onStart(audio) })
        }
        if (ui.stage == PrivateRecordingStage.CONTINUE)
            EsvButton(stringResource(R.string.private_record_continue), enabled = ui.canContinue, onClick = ui.onContinue)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            if (ui.stage == PrivateRecordingStage.ARMING || ui.stage == PrivateRecordingStage.RECORDING)
                EsvButton(stringResource(R.string.private_record_stop_save), onClick = ui.onStopSave)
            if (ui.canDiscard)
                EsvButton(stringResource(R.string.private_record_discard), enabled = !confirmDiscard, onClick = { confirmDiscard = true })
            if (!ui.canDiscard && !ui.recoveryBlocked && ui.stage !in setOf(PrivateRecordingStage.ARMING, PrivateRecordingStage.RECORDING,
                    PrivateRecordingStage.STOPPING, PrivateRecordingStage.VERIFYING, PrivateRecordingStage.COMMITTING))
                EsvButton(stringResource(R.string.private_record_close), onClick = ui.onClose)
        }
        if (ui.canDiscard && confirmDiscard) {
            EsvText(stringResource(R.string.private_record_discard_help), style = v.captionTextStyle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                EsvButton(stringResource(R.string.private_record_discard_confirm), onClick = ui.onDiscard)
                EsvButton(stringResource(R.string.private_record_keep), onClick = { confirmDiscard = false })
            }
        }
    }
}
