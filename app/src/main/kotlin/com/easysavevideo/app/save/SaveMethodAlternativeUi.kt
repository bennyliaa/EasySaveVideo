package com.easysavevideo.app.save

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals

internal enum class SaveAlternativeFeedback { UNAVAILABLE, OUTCOME_UNKNOWN }

/** Presentation only: dismissing feedback never cancels, deletes, retries or repairs a task. */
@Composable
internal fun SaveMethodAlternativeFeedback(runtime: AndroidSaveRuntime) {
    val admission = LocalPrivateInputAdmission.current
    val feedback = runtime.alternativeFeedback ?: return
    val v = LocalEsvVisuals.current
    EsvText(stringResource(when (feedback) {
        SaveAlternativeFeedback.UNAVAILABLE -> R.string.save_alternative_unavailable
        SaveAlternativeFeedback.OUTCOME_UNKNOWN -> R.string.save_alternative_unknown
    }), style = v.captionTextStyle, color = v.colors.error)
    EsvButton(stringResource(R.string.save_alternative_dismiss), onClick = { if (admission()) runtime.dismissAlternativeFeedback() })
}
