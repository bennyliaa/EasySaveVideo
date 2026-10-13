package com.easysavevideo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.easysavevideo.app.R
import com.easysavevideo.app.browser.*
import com.easysavevideo.app.theme.*

/** Page text stays separate from trusted app controls. Input is process-local, never saveable. */
@Composable
internal fun BrowserJsDialog(request: BrowserJsDialogRequest,
    finish: (BrowserJsDialogRequest, Boolean, String?, Boolean) -> Unit) {
    val v = LocalEsvVisuals.current
    var input by remember(request) { mutableStateOf(request.defaultValue) }
    val dismiss = { finish(request, false, null, false) }
    val inputDescription = stringResource(R.string.browser_js_input)
    key(request) {
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            BoxWithConstraints {
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(600.dp))
                    .background(v.colors.surface, RoundedCornerShape(v.corner)).padding(v.spacingMedium)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                    EsvText(stringResource(when (request.kind) {
                        BrowserJsDialogKind.ALERT -> R.string.browser_js_alert
                        BrowserJsDialogKind.CONFIRM -> R.string.browser_js_confirm
                        BrowserJsDialogKind.PROMPT -> R.string.browser_js_prompt
                    }), style = v.titleTextStyle)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                        EsvText(stringResource(R.string.browser_js_origin, request.origin), style = v.captionTextStyle)
                        EsvText(stringResource(R.string.browser_js_untrusted), style = v.captionTextStyle)
                        EsvText(request.message)
                        if (request.kind == BrowserJsDialogKind.PROMPT) {
                            EsvText(inputDescription, style = v.captionTextStyle)
                            BasicTextField(input, onValueChange = { if (BrowserJsDialogPolicy.validInput(it)) input = it },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                textStyle = v.bodyTextStyle.copy(color = v.colors.text), cursorBrush = SolidColor(v.colors.accent),
                                modifier = Modifier.fillMaxWidth().border(v.borderWidth, v.colors.border, RoundedCornerShape(v.corner))
                                    .padding(v.spacingMedium).semantics { contentDescription = inputDescription })
                            EsvText(stringResource(R.string.browser_js_input_limit, BrowserJsDialogPolicy.MAX_INPUT), style = v.captionTextStyle)
                        }
                    }
                    // One bounded scroll owner keeps every control reachable with IME, short windows and large fonts.
                    EsvButton(stringResource(R.string.browser_js_cancel), onClick = dismiss, modifier = Modifier.fillMaxWidth())
                    EsvButton(stringResource(R.string.browser_js_ok), onClick = {
                        finish(request, true, if (request.kind == BrowserJsDialogKind.PROMPT) input else null, false)
                    }, modifier = Modifier.fillMaxWidth())
                    EsvButton(stringResource(R.string.browser_js_block), onClick = { finish(request, false, null, true) },
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
