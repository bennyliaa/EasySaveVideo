package com.easysavevideo.app.save

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.theme.*
import com.easysavevideo.save.SaveRecord

@Composable
internal fun SaveFailureGuidance(record: SaveRecord, runtime: AndroidSaveRuntime, onBrowser: (() -> Unit)?) {
    val admission = LocalPrivateInputAdmission.current
    val advice = saveFailureAdvice(record.task.state, record.request.method, record.error) ?: return
    val v = LocalEsvVisuals.current
    val identity = FailedTaskIdentity.from(record)
    val command = remember(identity) { FailureBrowserCommand(identity) }
    var result by remember(identity) { mutableStateOf<FailureNavigationResult?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(R.string.save_failure_next), style = v.bodyTextStyle)
        EsvText(stringResource(saveFailureAdviceResource(advice)), style = v.captionTextStyle)
        if (advice.canReturnToBrowser) {
            EsvText(stringResource(R.string.save_failure_fresh_boundary), style = v.captionTextStyle, color = v.colors.muted)
            if (onBrowser != null) EsvButton(stringResource(R.string.save_failure_open_browser), onClick = {
                if (!admission()) return@EsvButton
                result = runtime.returnToBrowserAfterFailure(command)
                if (result == FailureNavigationResult.ACCEPTED) onBrowser()
            }, enabled = result != FailureNavigationResult.ACCEPTED && result != FailureNavigationResult.ALREADY_USED)
            else EsvText(stringResource(R.string.save_failure_unavailable), style = v.captionTextStyle)
        }
        result?.takeIf { it != FailureNavigationResult.ACCEPTED }?.let {
            EsvText(stringResource(failureNavigationResultResource(it)), color = v.colors.error, style = v.captionTextStyle)
        }
    }
}

@Composable
internal fun SaveFailureBrowserReminder(runtime: AndroidSaveRuntime) {
    SaveMethodAlternativeFeedback(runtime)
    if (!runtime.showFailureBrowserReminder) return
    val v = LocalEsvVisuals.current
    Column(Modifier.fillMaxWidth().padding(horizontal = v.spacingMedium), verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        EsvText(stringResource(if (runtime.alternativeNeedsSource) R.string.save_alternative_fresh_source
            else R.string.save_failure_browser_hint), style = v.captionTextStyle)
        EsvButton(stringResource(R.string.save_failure_dismiss), onClick = runtime::dismissFailureBrowserReminder)
    }
}

internal fun saveFailureAdviceResource(advice: SaveFailureAdvice): Int = when (advice) {
    SaveFailureAdvice.CANCELLED -> R.string.save_failure_cancelled
    SaveFailureAdvice.PROTECTED -> R.string.save_failure_protected
    SaveFailureAdvice.CONNECTION_BLOCKED -> R.string.save_failure_connection_blocked
    SaveFailureAdvice.STORAGE -> R.string.save_failure_storage
    SaveFailureAdvice.RECOVERY -> R.string.save_failure_recovery
    SaveFailureAdvice.RELEASE_PENDING -> R.string.save_failure_release_pending
    SaveFailureAdvice.BUSY -> R.string.save_failure_busy
    SaveFailureAdvice.AUTHENTICATION -> R.string.save_failure_authentication
    SaveFailureAdvice.SOURCE_CHANGED -> R.string.save_failure_source_changed
    SaveFailureAdvice.NETWORK -> R.string.save_failure_network
    SaveFailureAdvice.RATE_LIMITED -> R.string.save_failure_rate_limited
    SaveFailureAdvice.REASSESS_METHOD -> R.string.save_failure_reassess_method
    SaveFailureAdvice.INVALID_OUTPUT -> R.string.save_failure_invalid_output
    SaveFailureAdvice.BUDGET -> R.string.save_failure_budget
    SaveFailureAdvice.UNKNOWN -> R.string.save_failure_unknown
    SaveFailureAdvice.RECORD_CONSENT -> R.string.save_failure_record_consent
    SaveFailureAdvice.RECORD_AUDIO -> R.string.save_failure_record_audio
    SaveFailureAdvice.RECORD_SETUP -> R.string.save_failure_record_setup
    SaveFailureAdvice.RECORD_OUTPUT -> R.string.save_failure_record_output
}
internal fun failureNavigationResultResource(result: FailureNavigationResult): Int = when (result) {
    FailureNavigationResult.ACCEPTED -> R.string.save_failure_browser_hint
    FailureNavigationResult.ALREADY_USED -> R.string.save_failure_already_used
    FailureNavigationResult.STALE_TASK -> R.string.save_failure_stale
    FailureNavigationResult.NOT_ALLOWED -> R.string.save_failure_not_allowed
    FailureNavigationResult.UNAVAILABLE -> R.string.save_failure_unavailable
}
