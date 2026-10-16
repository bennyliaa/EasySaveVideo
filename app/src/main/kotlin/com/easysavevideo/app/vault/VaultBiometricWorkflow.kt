package com.easysavevideo.app.vault

internal enum class VaultBiometricWorkflowKind { ENROLL, REVOKE }
internal enum class VaultBiometricNotice { NONE, ENABLED, REVOKED, UNFINISHED, UNAVAILABLE, RETIREMENT_PENDING }
/** Runtime-owned explicit intent. The original deadline and cancellation lineage never restart. */
internal class VaultBiometricWorkflow internal constructor(val kind: VaultBiometricWorkflowKind,
    internal val context: VaultOperationContext) {
    val deadlineElapsedMillis: Long get() = context.deadlineElapsedMillis
    override fun toString() = "VaultBiometricWorkflow(kind=$kind)"
}
internal typealias VaultBiometricBindingFactory = (VaultBiometricPurpose, Any?) -> VaultBiometricBinding
