package com.easysavevideo.app.vault

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.app.ui.LocalPrivateInputAdmission
import com.easysavevideo.app.ui.PrivateAppDialog
import com.easysavevideo.core.model.VaultRecoveryChoice

/** Secrets exist only inside this request's protected dialog and its synchronously retired draft. */
@Suppress("DEPRECATION")
@Composable
internal fun VaultCredentialDialog(host: VaultProtectedHost, request: VaultCredentialRequest) {
    val draft = remember(request) { VaultCredentialDraft() }
    val revision = remember(request) { mutableIntStateOf(0) }
    val clear: () -> Unit = { request.clearDisplayInput(); draft.close(); revision.intValue++ }
    DisposableEffect(request, draft) {
        val retirement = request.onRetired(clear)
        onDispose { retirement.close(); request.clearDisplayInput(); draft.close() }
    }
    PrivateAppDialog(requestIdentity = request,
        onDismissRequest = { clear(); host.cancelCredential(request) },
        properties = DialogProperties(dismissOnClickOutside = false),
        isRequestCurrent = { host.requestCurrent(request) }) {
        val inputAdmission = LocalPrivateInputAdmission.current
        val accepts = { inputAdmission() && host.requestCurrent(request) && !draft.retired }
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision.intValue
        val visuals = LocalEsvVisuals.current
        val setup = request.mode == VaultCredentialMode.SETUP
        val reset = request.mode == VaultCredentialMode.RECOVERY_RESET
        val confirmation = request.mode == VaultCredentialMode.RECOVERY_CONFIRM
        val showingCode = request.mode == VaultCredentialMode.RECOVERY_DISPLAY
        CompositionLocalProvider(LocalClipboardManager provides VaultCredentialClipboard,
            LocalClipboard provides VaultCredentialClipboard, LocalTextToolbar provides VaultCredentialTextToolbar) {
            BoxWithConstraints {
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(600.dp))
                    .background(visuals.colors.surface, RoundedCornerShape(visuals.corner)).padding(visuals.spacingMedium)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    EsvText(stringResource(when (request.mode) {
                        VaultCredentialMode.SETUP -> R.string.vault_credential_setup_title
                        VaultCredentialMode.UNLOCK -> R.string.vault_credential_unlock_title
                        VaultCredentialMode.BIOMETRIC_UNLOCK -> R.string.vault_biometric_unlock
                        VaultCredentialMode.BIOMETRIC_ENROLL -> R.string.vault_biometric_enroll_password
                        VaultCredentialMode.BIOMETRIC_REVOKE -> R.string.vault_biometric_revoke_password
                        VaultCredentialMode.RECOVERY_ENROLL -> R.string.vault_recovery_auth_title
                        VaultCredentialMode.RECOVERY_DISPLAY -> R.string.vault_recovery_display_title
                        VaultCredentialMode.RECOVERY_CONFIRM -> R.string.vault_recovery_confirm_title
                        VaultCredentialMode.RECOVERY_RESET -> R.string.vault_recovery_reset_title
                    }), style = visuals.titleTextStyle)
                    EsvText(stringResource(R.string.vault_credential_private), style = visuals.captionTextStyle)
                    if (request.mode in setOf(VaultCredentialMode.BIOMETRIC_ENROLL, VaultCredentialMode.BIOMETRIC_REVOKE))
                        EsvText(stringResource(R.string.vault_biometric_limits), style = visuals.captionTextStyle)
                    if (!draft.retired) {
                        if (showingCode) {
                            EsvText(stringResource(R.string.vault_recovery_keep_offline), style = visuals.bodyTextStyle)
                            val text = try {
                                val chars = if (host.requestCurrent(request)) request.copyDisplayCharacters() else null
                                try { chars?.let(::String).orEmpty() } finally { chars?.fill('\u0000') }
                            } catch (_: VaultOperationException) { "" }
                            // Plain protected text only: no SelectionContainer, saveable state or copy action.
                            EsvText(text, style = visuals.bodyTextStyle)
                            EsvButton(stringResource(R.string.vault_recovery_continue), onClick = {
                                if (accepts()) { clear(); host.continueRecoveryDisplay(request) }
                            }, modifier = Modifier.fillMaxWidth())
                        } else {
                            if (confirmation || reset) {
                                VaultPasswordField(draft.recoveryText(), stringResource(R.string.vault_recovery_code)) { value ->
                                    if (accepts()) { draft.updateRecovery(value); revision.intValue++ }
                                }
                            }
                            if (!confirmation) {
                                VaultPasswordField(draft.passwordText(), stringResource(if (reset)
                                    R.string.vault_recovery_new_password else R.string.vault_credential_password)) { value ->
                                    if (accepts()) { draft.updatePassword(value); revision.intValue++ }
                                }
                            }
                            if (setup || reset) {
                                VaultPasswordField(draft.confirmationText(), stringResource(R.string.vault_credential_confirm)) { value ->
                                    if (accepts()) { draft.updateConfirmation(value); revision.intValue++ }
                                }
                                EsvText(stringResource(R.string.vault_credential_profile), style = visuals.captionTextStyle)
                            }
                            if (reset) EsvText(stringResource(R.string.vault_recovery_reset_warning), style = visuals.captionTextStyle)
                            if (setup) {
                                EsvText(stringResource(R.string.vault_recovery_choice), style = visuals.bodyTextStyle)
                                EsvButton(stringResource(if (draft.recoveryChoice == VaultRecoveryChoice.OPTED_IN)
                                    R.string.vault_recovery_opted_in else R.string.vault_recovery_enable), onClick = {
                                    if (accepts()) { draft.chooseRecovery(VaultRecoveryChoice.OPTED_IN); revision.intValue++ }
                                }, modifier = Modifier.fillMaxWidth())
                                EsvText(stringResource(R.string.vault_recovery_setup_explanation), style = visuals.captionTextStyle)
                                EsvButton(stringResource(if (draft.recoveryChoice == VaultRecoveryChoice.DECLINED)
                                    R.string.vault_recovery_declined else R.string.vault_recovery_decline), onClick = {
                                    if (accepts()) { draft.chooseRecovery(VaultRecoveryChoice.DECLINED); revision.intValue++ }
                                }, modifier = Modifier.fillMaxWidth())
                            }
                            draft.error?.let { error ->
                                EsvText(stringResource(when (error) {
                                    VaultCredentialDraftError.PASSWORD_INVALID -> R.string.vault_credential_invalid
                                    VaultCredentialDraftError.PASSWORD_MISMATCH -> R.string.vault_credential_mismatch
                                    VaultCredentialDraftError.RECOVERY_CHOICE_REQUIRED -> R.string.vault_recovery_choice_required
                                    VaultCredentialDraftError.RECOVERY_UNAVAILABLE -> R.string.vault_recovery_unavailable_explanation
                                    VaultCredentialDraftError.RECOVERY_INVALID -> R.string.vault_recovery_invalid
                                }), style = visuals.captionTextStyle)
                            }
                            EsvButton(stringResource(when {
                                setup -> R.string.vault_credential_create
                                confirmation -> R.string.vault_recovery_verify
                                reset -> R.string.vault_recovery_reset_submit
                                request.mode == VaultCredentialMode.RECOVERY_ENROLL -> R.string.vault_recovery_enable
                                request.mode == VaultCredentialMode.BIOMETRIC_ENROLL -> R.string.vault_biometric_enable
                                request.mode == VaultCredentialMode.BIOMETRIC_REVOKE -> R.string.vault_biometric_disable
                                else -> R.string.vault_runtime_unlock
                            }), onClick = {
                                if (accepts()) {
                                    when {
                                        confirmation -> draft.prepareRecoveryConfirmation()?.let { code ->
                                            try { host.submitRecoveryConfirmation(request, code) }
                                            catch (_: Exception) { code.close(); clear(); host.cancelCredential(request) }
                                        }
                                        reset -> draft.prepareRecoveryReset()?.let { submission ->
                                            try { host.submitPasswordReset(request, submission.input, submission.password) }
                                            catch (_: Exception) { submission.close(); clear(); host.cancelCredential(request) }
                                        }
                                        else -> draft.prepare(setup)?.let { submission ->
                                            try { host.submitCredential(request, submission.input, submission.recoveryChoice) }
                                            catch (_: Exception) { submission.input.close(); clear(); host.cancelCredential(request) }
                                        }
                                    }
                                    revision.intValue++
                                }
                            }, enabled = !setup || draft.recoveryChoice in setOf(VaultRecoveryChoice.DECLINED, VaultRecoveryChoice.OPTED_IN),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                    EsvButton(stringResource(R.string.vault_credential_cancel), onClick = {
                        if (inputAdmission() && host.requestCurrent(request)) { clear(); host.cancelCredential(request) }
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun VaultPasswordField(value: String, label: String, onValueChange: (String) -> Unit) {
    val visuals = LocalEsvVisuals.current
    EsvText(label, style = visuals.captionTextStyle)
    BasicTextField(value = value, onValueChange = onValueChange,
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = visuals.bodyTextStyle.copy(color = visuals.colors.text), cursorBrush = SolidColor(visuals.colors.accent),
        modifier = Modifier.fillMaxWidth().border(visuals.borderWidth, visuals.colors.border, RoundedCornerShape(visuals.corner))
            .padding(visuals.spacingMedium).semantics { contentDescription = label })
}

/** Credential fields never read or write the system clipboard, including keyboard/semantic actions. */
@Suppress("DEPRECATION")
private object VaultCredentialClipboard : ClipboardManager, Clipboard {
    override fun setText(annotatedString: AnnotatedString) = Unit
    override fun getText(): AnnotatedString? = null
    override fun hasText(): Boolean = false
    override fun getClip(): ClipEntry? = null
    override fun setClip(clipEntry: ClipEntry?) = Unit
    override suspend fun getClipEntry(): ClipEntry? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    override val nativeClipboard: android.content.ClipboardManager
        get() = throw UnsupportedOperationException("vault-clipboard-unavailable")
}

private object VaultCredentialTextToolbar : TextToolbar {
    override val status = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) = Unit
}
