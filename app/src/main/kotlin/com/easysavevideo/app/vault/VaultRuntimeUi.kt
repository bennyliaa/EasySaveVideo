package com.easysavevideo.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.app.ui.LocalPrivateInputAdmission

/** Status/revision only. No credential, plaintext key, path or authenticated capability enters UI state. */
internal data class VaultRenderedState(val status: VaultRuntimeStatus, val revision: Long? = null, val coverGeneration: Long = 0,
    val recoveryState: VaultRecoveryState = VaultRecoveryState.NONE, val pendingPassword: Boolean = false,
    val recoveryNotice: VaultRecoveryNotice = VaultRecoveryNotice.NONE)

/** Current settings consumer of the single disabled gate; never constructs an auth/store owner. */
@Composable internal fun VaultRuntimeAvailability(onOpen: (() -> Unit)? = null) {
    if (!AndroidVaultRuntimeEntry.ENABLED) {
        EsvText(stringResource(R.string.ui_vault_disabled), style = LocalEsvVisuals.current.captionTextStyle)
    } else if (onOpen != null) {
        EsvButton(stringResource(R.string.vault_runtime_open), onClick = {
            if (AndroidVaultRuntimeEntry.ENABLED) onOpen()
        })
    }
}

/** The Main host owns the exact root, surfaces, lifecycles and cover receipts. UI cannot acknowledge them. */
@Composable internal fun VaultRuntimePage(host: VaultProtectedHost) {
    val state = host.presentation.rendered
    val visuals = LocalEsvVisuals.current
    val inputAdmission = LocalPrivateInputAdmission.current
    val capturedAction = host.captureAction()
    val accepts = { inputAdmission() && capturedAction() }
    Column(Modifier.fillMaxSize().background(visuals.colors.surface).padding(visuals.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
        EsvText(stringResource(when (state.status) {
            VaultRuntimeStatus.EMPTY -> R.string.vault_runtime_empty
            VaultRuntimeStatus.READY_TO_REVEAL -> R.string.vault_runtime_ready
            VaultRuntimeStatus.SETUP_REQUIRED -> R.string.vault_runtime_setup_pending
            VaultRuntimeStatus.LOADING, VaultRuntimeStatus.WORKING -> R.string.vault_runtime_working
            VaultRuntimeStatus.LOCKED -> R.string.vault_runtime_locked
            VaultRuntimeStatus.RECOVERY_CODE_READY -> R.string.vault_recovery_ready_to_display
            VaultRuntimeStatus.RECOVERY_REQUIRED -> R.string.vault_runtime_recovery_required
            VaultRuntimeStatus.UNAVAILABLE -> R.string.vault_runtime_unavailable
            VaultRuntimeStatus.DISABLED -> R.string.ui_vault_disabled
        }))
        if (state.recoveryNotice != VaultRecoveryNotice.NONE) {
            EsvText(stringResource(when (state.recoveryNotice) {
                VaultRecoveryNotice.PASSWORD_CREATED -> R.string.vault_recovery_password_created
                VaultRecoveryNotice.CODE_ENABLED -> R.string.vault_recovery_verified
                VaultRecoveryNotice.PASSWORD_RESET -> R.string.vault_recovery_reset_complete
                VaultRecoveryNotice.NONE -> R.string.vault_runtime_locked
            }), style = visuals.captionTextStyle)
        }
        if (state.status == VaultRuntimeStatus.SETUP_REQUIRED) {
            EsvButton(stringResource(R.string.vault_runtime_setup), onClick = {
                if (accepts()) host.beginCredential(VaultCredentialMode.SETUP)
            }, enabled = host.credentialRequest == null)
        }
        if (state.status == VaultRuntimeStatus.LOCKED) {
            EsvButton(stringResource(R.string.vault_runtime_unlock), onClick = {
                if (accepts()) host.beginCredential(VaultCredentialMode.UNLOCK)
            }, enabled = host.credentialRequest == null)
        }
        if (state.status == VaultRuntimeStatus.LOCKED) {
            EsvText(stringResource(when (state.recoveryState) {
                VaultRecoveryState.NONE -> R.string.vault_recovery_not_enabled
                VaultRecoveryState.PENDING -> R.string.vault_recovery_unfinished
                VaultRecoveryState.ACTIVE -> R.string.vault_recovery_recorded
            }), style = visuals.captionTextStyle)
            if (state.pendingPassword) EsvText(stringResource(R.string.vault_recovery_reset_unfinished), style = visuals.captionTextStyle)
            EsvButton(stringResource(if (state.recoveryState == VaultRecoveryState.ACTIVE)
                R.string.vault_recovery_reset_title else R.string.vault_recovery_enable), onClick = {
                if (accepts()) host.beginCredential(if (state.recoveryState == VaultRecoveryState.ACTIVE)
                    VaultCredentialMode.RECOVERY_RESET else VaultCredentialMode.RECOVERY_ENROLL)
            }, enabled = host.credentialRequest == null)
        }
        if (state.status == VaultRuntimeStatus.RECOVERY_CODE_READY) {
            EsvButton(stringResource(R.string.vault_recovery_show), onClick = {
                if (accepts()) host.beginCredential(VaultCredentialMode.RECOVERY_DISPLAY)
            }, enabled = host.credentialRequest == null)
        }
        if (state.status == VaultRuntimeStatus.READY_TO_REVEAL) {
            EsvButton(stringResource(R.string.vault_runtime_open), onClick = {
                if (accepts()) host.revealCatalog()
            })
        }
        if (state.status == VaultRuntimeStatus.EMPTY || state.status == VaultRuntimeStatus.READY_TO_REVEAL) {
            EsvButton(stringResource(R.string.vault_runtime_lock), onClick = { if (accepts()) host.lock() })
        }
    }
    host.credentialRequest?.let { request -> VaultCredentialDialog(host, request) }
}
