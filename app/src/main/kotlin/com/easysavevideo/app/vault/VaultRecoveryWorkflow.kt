package com.easysavevideo.app.vault

internal enum class VaultRecoveryWorkflowKind { ENROLL, RESET_PASSWORD }
internal enum class VaultRecoveryNotice { NONE, PASSWORD_CREATED, CODE_ENABLED, PASSWORD_RESET }
/** Exact runtime-owned intent and original expiry. It is neither authentication nor a secret. */
internal class VaultRecoveryWorkflow internal constructor(val kind: VaultRecoveryWorkflowKind, val deadlineElapsedMillis: Long) {
    override fun toString(): String = "VaultRecoveryWorkflow(kind=$kind)"
}
