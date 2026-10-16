package com.easysavevideo.app.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.EsvButton
import com.easysavevideo.app.theme.EsvText
import com.easysavevideo.app.theme.LocalEsvVisuals
import com.easysavevideo.app.ui.LocalPrivateInputAdmission

internal data class VaultMediaRow(val ref: VaultMediaReadRef, val name: String, val bytes: Long)

/** Bounded authenticated display metadata only. No credential, plaintext key, path or authenticated capability enters UI state. */
internal data class VaultRenderedState(val status: VaultRuntimeStatus, val revision: Long? = null, val coverGeneration: Long = 0,
    val recoveryState: VaultRecoveryState = VaultRecoveryState.NONE, val pendingPassword: Boolean = false,
    val recoveryNotice: VaultRecoveryNotice = VaultRecoveryNotice.NONE,
    val items: List<VaultMediaRow> = emptyList(), val media: VaultMediaView = VaultMediaView(),
    val playback: VaultPlaybackView = VaultPlaybackView(), val failureReason: VaultFailureReason? = null,
    val biometricState: VaultBiometricState = VaultBiometricState.NONE,
    val biometricNotice: VaultBiometricNotice = VaultBiometricNotice.NONE,
    val biometricAvailability: VaultBiometricAvailability? = null)

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
    // Process-local protected selection; never persisted through cover, rotation, or saved state.
    var selected by remember(state.coverGeneration, state.revision, state.status) { mutableStateOf(emptyList<VaultMediaReadRef>()) }
    // Non-saveable layout state and markers. No private identity or scroll position is restored.
    val listState = remember(host, state.coverGeneration, state.revision) { LazyListState() }
    val viewportRows = remember(host, state.coverGeneration, state.revision, state.items) {
        state.items.map { VaultThumbnailViewportRow(it.ref) }
    }
    val batch = state.media.batch
    val catalogVisible = state.status == VaultRuntimeStatus.CATALOG && !state.playback.occupied
    LaunchedEffect(host, listState, viewportRows, catalogVisible) {
        snapshotFlow {
            if (catalogVisible) vaultVisibleThumbnailRefs(viewportRows,
                listState.layoutInfo.visibleItemsInfo.map { it.contentType }) else emptyList()
        }.collectLatest { host.updateThumbnailViewport(it) }
    }
    DisposableEffect(host) { onDispose { host.thumbnailCompositionRemoved() } }
    LazyColumn(state = listState,
        modifier = Modifier.fillMaxSize().background(visuals.colors.surface).padding(visuals.spacingMedium),
        verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
        item(contentType = "public-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                EsvText(stringResource(when (state.status) {
                    VaultRuntimeStatus.CATALOG -> R.string.vault_media_catalog
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
                if (state.status in setOf(VaultRuntimeStatus.UNAVAILABLE, VaultRuntimeStatus.RECOVERY_REQUIRED)) {
                    EsvText(stringResource(vaultFailureMessage(state.failureReason ?: VaultFailureReason.UNKNOWN)))
                    EsvText(stringResource(R.string.vault_failure_ciphertext_preserved), style = visuals.captionTextStyle)
                    EsvText(stringResource(R.string.vault_failure_recovery_limits), style = visuals.captionTextStyle)
                }
                VaultPlaybackControls(host, state.playback, accepts)
                if (state.status == VaultRuntimeStatus.CATALOG) {
                    EsvButton(stringResource(if (host.thumbnailsRequested) R.string.vault_thumbnail_stop else R.string.vault_thumbnail_load_visible),
                        onClick = { if (accepts()) host.toggleThumbnails() },
                        enabled = host.thumbnailsRequested || (!state.playback.occupied && !state.media.busy && !state.media.hasMove && !state.media.hasCopy &&
                            (batch == null || batch.status in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED))))
                }
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
                    }, enabled = host.credentialRequest == null && !state.playback.occupied)
                }
                if (state.status == VaultRuntimeStatus.LOCKED) {
                    EsvButton(stringResource(R.string.vault_runtime_unlock), onClick = {
                        if (accepts()) host.beginCredential(VaultCredentialMode.UNLOCK)
                    }, enabled = host.credentialRequest == null && !state.playback.occupied)
                }
                if (state.biometricAvailability != null && state.biometricAvailability != VaultBiometricAvailability.AVAILABLE) {
                    EsvText(stringResource(when (state.biometricAvailability) {
                        VaultBiometricAvailability.KEY_UNAVAILABLE -> R.string.vault_biometric_key_unavailable
                        VaultBiometricAvailability.TEMPORARY_LOCKOUT -> R.string.vault_biometric_temporary_lockout
                        VaultBiometricAvailability.PERMANENT_LOCKOUT -> R.string.vault_biometric_permanent_lockout
                        else -> R.string.vault_biometric_unavailable
                    }), style = visuals.captionTextStyle)
                }
                if (state.biometricNotice != VaultBiometricNotice.NONE) {
                    EsvText(stringResource(when (state.biometricNotice) {
                        VaultBiometricNotice.ENABLED -> R.string.vault_biometric_enabled
                        VaultBiometricNotice.REVOKED -> R.string.vault_biometric_revoked
                        VaultBiometricNotice.UNFINISHED -> R.string.vault_biometric_unfinished
                        VaultBiometricNotice.UNAVAILABLE -> R.string.vault_biometric_unavailable
                        VaultBiometricNotice.RETIREMENT_PENDING -> R.string.vault_biometric_retirement_pending
                        VaultBiometricNotice.NONE -> R.string.vault_runtime_locked
                    }), style = visuals.captionTextStyle)
                }
                if (state.status == VaultRuntimeStatus.LOCKED) {
                    EsvText(stringResource(when (state.biometricState) {
                        VaultBiometricState.NONE -> R.string.vault_biometric_not_enabled
                        VaultBiometricState.PENDING -> R.string.vault_biometric_pending
                        VaultBiometricState.ACTIVE -> R.string.vault_biometric_active
                        VaultBiometricState.REVOKED -> R.string.vault_biometric_revoked
                    }), style = visuals.captionTextStyle)
                    if (state.biometricState == VaultBiometricState.ACTIVE) {
                        EsvButton(stringResource(R.string.vault_biometric_unlock), onClick = {
                            if (accepts()) host.beginCredential(VaultCredentialMode.BIOMETRIC_UNLOCK)
                        }, enabled = host.credentialRequest == null && !state.playback.occupied && !state.media.busy && host.runtime.biometricCleanupComplete())
                    }
                    EsvText(stringResource(R.string.vault_biometric_limits), style = visuals.captionTextStyle)
                    EsvButton(stringResource(if (state.biometricState in setOf(VaultBiometricState.ACTIVE, VaultBiometricState.PENDING))
                        R.string.vault_biometric_disable else R.string.vault_biometric_enable), onClick = {
                        if (accepts()) host.beginCredential(if (state.biometricState in setOf(VaultBiometricState.ACTIVE, VaultBiometricState.PENDING))
                            VaultCredentialMode.BIOMETRIC_REVOKE else VaultCredentialMode.BIOMETRIC_ENROLL)
                    }, enabled = host.credentialRequest == null && !state.pendingPassword && !state.playback.occupied && !state.media.busy &&
                        state.biometricNotice != VaultBiometricNotice.RETIREMENT_PENDING && host.runtime.biometricCleanupComplete())
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
                    }, enabled = host.credentialRequest == null && !state.playback.occupied && !state.media.busy)
                }
                if (state.status == VaultRuntimeStatus.RECOVERY_CODE_READY) {
                    EsvButton(stringResource(R.string.vault_recovery_show), onClick = {
                        if (accepts()) host.beginCredential(VaultCredentialMode.RECOVERY_DISPLAY)
                    }, enabled = host.credentialRequest == null && !state.playback.occupied)
                }
                if (state.status == VaultRuntimeStatus.READY_TO_REVEAL) {
                    EsvButton(stringResource(R.string.vault_runtime_open), onClick = {
                        if (accepts()) host.revealCatalog()
                    })
                }
                if (state.media.notice != VaultMediaNotice.NONE) {
                    EsvText(stringResource(when (state.media.notice) {
                        VaultMediaNotice.MOVE_CONFIRM -> R.string.vault_media_move_explanation
                        VaultMediaNotice.COPYING_CONFIRM, VaultMediaNotice.AUTHENTICATE_TO_CONTINUE -> R.string.vault_media_continue
                        VaultMediaNotice.WORKING -> R.string.vault_runtime_working
                        VaultMediaNotice.PICKING -> R.string.vault_media_picking
                        VaultMediaNotice.MOVED -> R.string.vault_media_moved
                        VaultMediaNotice.COPIED -> R.string.vault_media_copied
                        VaultMediaNotice.CANCELLED -> R.string.vault_media_cancelled
                        VaultMediaNotice.TARGET_PERMISSION_REQUIRED -> R.string.vault_media_permission
                        VaultMediaNotice.RELEASE_UNKNOWN -> R.string.vault_media_release_unknown
                        VaultMediaNotice.BATCH_CONFIRM -> R.string.vault_batch_explanation
                        VaultMediaNotice.BATCH_RESULT -> R.string.vault_batch_results
                        VaultMediaNotice.OUTPUT_RETAINED -> R.string.vault_media_output_retained
                        VaultMediaNotice.BUDGET_EXHAUSTED -> R.string.vault_batch_budget
                        else -> R.string.vault_media_reconcile
                    }), style = visuals.captionTextStyle)
                }
                if (state.status in setOf(VaultRuntimeStatus.EMPTY, VaultRuntimeStatus.CATALOG) && !state.playback.occupied) {
                    if (state.media.hasMove) EsvButton(stringResource(R.string.vault_media_move), onClick = {
                        if (accepts()) host.confirmMoveToVault()
                    }, enabled = !state.playback.occupied && !state.media.busy)
                    if (state.media.hasCopy) EsvButton(stringResource(if (state.media.notice == VaultMediaNotice.CANCELLED)
                        R.string.vault_media_recover else R.string.vault_media_copy_confirm), onClick = {
                        if (accepts()) host.confirmCopyToPhone()
                    }, enabled = !state.playback.occupied && !state.media.busy)
                    if (batch != null) {
                        EsvText(stringResource(R.string.vault_batch_progress, batch.verified, batch.total, batch.activeIndex + 1), style = visuals.captionTextStyle)
                        batch.outcomes.forEachIndexed { index, outcome ->
                            EsvText(stringResource(R.string.vault_batch_row, index + 1, stringResource(when (outcome) {
                                VaultCopyBatchOutcome.VERIFIED_COPY -> R.string.vault_batch_verified
                                VaultCopyBatchOutcome.WAITING, VaultCopyBatchOutcome.PAUSED_NOT_STARTED -> R.string.vault_batch_not_started
                                VaultCopyBatchOutcome.ACTIVE -> R.string.vault_runtime_working
                                VaultCopyBatchOutcome.FINAL_DECISION_PENDING -> R.string.vault_media_reconcile
                                VaultCopyBatchOutcome.CLEANUP_REQUIRED -> R.string.vault_batch_manual
                                VaultCopyBatchOutcome.UNRESOLVED_RETAINED -> R.string.vault_media_output_retained
                                VaultCopyBatchOutcome.CANCELLED_BEFORE_START, VaultCopyBatchOutcome.CANCELLED_CLEANED -> R.string.vault_media_cancelled
                                else -> R.string.vault_batch_failed
                            })), style = visuals.captionTextStyle)
                        }
                        state.media.actionToken?.let { token ->
                            if (batch.status !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED))
                                EsvButton(stringResource(R.string.vault_batch_confirm), onClick = {
                                    if (accepts()) host.confirmBatchCopy(token)
                                }, enabled = !state.playback.occupied && !state.media.busy && !state.media.canKeepOutput)
                        }
                    }
                    if (state.media.canKeepOutput) state.media.actionToken?.let { token ->
                        EsvText(stringResource(R.string.vault_batch_keep_warning), style = visuals.captionTextStyle)
                        EsvButton(stringResource(R.string.vault_batch_keep), onClick = {
                            if (accepts()) {
                                if (batch != null) host.keepCurrentBatchOutputAndContinue(token) else host.keepCurrentSingleOutput(token)
                            }
                        }, enabled = !state.playback.occupied && !state.media.busy)
                    }
                }
            }
        }
        if (state.status in setOf(VaultRuntimeStatus.EMPTY, VaultRuntimeStatus.CATALOG) && !state.playback.occupied) {
            itemsIndexed(state.items, contentType = { index, _ -> viewportRows[index] }) { _, item ->
                Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    VaultPrivateThumbnail(host.thumbnailState(item.ref))
                    EsvText(item.name)
                    EsvButton(stringResource(R.string.vault_playback_open), onClick = {
                        if (accepts()) host.openPlayback(item.ref)
                    }, enabled = !state.playback.occupied && !state.media.busy && !state.media.hasMove && !state.media.hasCopy &&
                        (batch == null || batch.status in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED)))
                    EsvButton(stringResource(if (item.ref in selected) R.string.vault_batch_deselect else R.string.vault_batch_select), onClick = {
                        if (accepts()) selected = if (item.ref in selected) selected - item.ref else (selected + item.ref).take(VaultCopyBatchLimits.MAX_ITEMS)
                    }, enabled = !state.playback.occupied && !state.media.busy && !state.media.hasMove && !state.media.hasCopy &&
                        (batch == null || batch.status in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED)) &&
                        (item.ref in selected || selected.size < VaultCopyBatchLimits.MAX_ITEMS))
                    EsvButton(stringResource(R.string.vault_media_copy), onClick = {
                        if (accepts()) host.copyMediaToPhone(item.ref)
                    }, enabled = !state.playback.occupied && !state.media.busy && !state.media.hasMove && !state.media.hasCopy &&
                        (batch == null || batch.status in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED)))
                }
            }
            item(contentType = "public-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                    if (selected.isNotEmpty()) EsvButton(stringResource(R.string.vault_batch_choose_folder, selected.size), onClick = {
                        if (accepts()) { val plan = selected.toList(); selected = emptyList(); host.copyMediaBatchToPhone(plan) }
                    }, enabled = !state.playback.occupied && !state.media.busy && !state.media.hasCopy && !state.media.hasMove)
                    EsvButton(stringResource(R.string.vault_media_recover), onClick = {
                        if (accepts()) host.reconcileMedia()
                    }, enabled = !state.playback.occupied && !state.media.busy)
                }
            }
        }
        item(contentType = "public-lock") {
            Column(verticalArrangement = Arrangement.spacedBy(visuals.spacingSmall)) {
                if (state.media.hasMove || state.media.hasCopy || state.media.busy || state.media.batch?.status?.let {
                        it !in setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED) } == true) {
                    EsvButton(stringResource(R.string.vault_media_cancel), onClick = { if (accepts()) host.cancelMedia() })
                }
                if (state.status == VaultRuntimeStatus.WORKING) {
                    EsvButton(stringResource(R.string.vault_biometric_cancel), onClick = { if (accepts()) host.lock() })
                }
                if (state.status in setOf(VaultRuntimeStatus.EMPTY, VaultRuntimeStatus.CATALOG, VaultRuntimeStatus.READY_TO_REVEAL)) {
                    EsvButton(stringResource(R.string.vault_runtime_lock), onClick = { if (accepts()) host.lock() })
                }
            }
        }
    }
    host.credentialRequest?.let { request -> VaultCredentialDialog(host, request) }
}

/** An exhaustive finite mapping; no platform exception, alias or path reaches presentation. */
internal fun vaultFailureMessage(reason: VaultFailureReason): Int = when (reason) {
    VaultFailureReason.DEVICE_KEY_UNAVAILABLE -> R.string.vault_failure_device_key
    VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT -> R.string.vault_failure_installation
    VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT -> R.string.vault_failure_retry_ledger
    VaultFailureReason.STORE_UNREADABLE -> R.string.vault_failure_store_unreadable
    VaultFailureReason.STORE_INCOMPLETE -> R.string.vault_failure_store_incomplete
    VaultFailureReason.UNKNOWN -> R.string.vault_failure_unknown
}
