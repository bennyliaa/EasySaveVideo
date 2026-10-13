package com.easysavevideo.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.EsvButton

/** A visible, localized action; its hit target cannot be reduced by a theme token. */
@Composable
internal fun InputClearButton(description: Int, enabled: Boolean, onClear: () -> Unit) {
    val label = stringResource(description)
    EsvButton(stringResource(R.string.input_clear), onClick = onClear, enabled = enabled,
        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { contentDescription = label })
}
