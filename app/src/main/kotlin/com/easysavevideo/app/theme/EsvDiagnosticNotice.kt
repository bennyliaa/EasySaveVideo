package com.easysavevideo.app.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R

/** Presentation only: preserve diagnostic text verbatim behind an explicit disclosure. */
@Composable
fun EsvDiagnosticNotice(original: String) {
    var expanded by remember(original) { mutableStateOf(false) }
    Column {
        EsvText(stringResource(R.string.final_diagnostic_summary), style = LocalEsvVisuals.current.captionTextStyle)
        EsvButton(stringResource(if (expanded) R.string.final_diagnostic_hide else R.string.final_diagnostic_show),
            onClick = { expanded = !expanded })
        if (expanded) EsvText(original, style = LocalEsvVisuals.current.captionTextStyle)
    }
}
