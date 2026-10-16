package com.easysavevideo.app.vault

import com.easysavevideo.core.model.VaultAttemptIdentity
import com.easysavevideo.core.model.VaultRetryLedger
import com.easysavevideo.core.model.VaultRetryPolicy
import com.easysavevideo.core.model.VaultRetryWrite
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed R2 implementation budgets. Expiry revokes acceptance, never proves worker release. */
internal object VaultRuntimeLimits {
    const val PROMPT_MILLIS = 120_000L
    const val KDF_MILLIS = 15_000L
    const val DATABASE_MILLIS = 10_000L
    const val CLEANUP_MILLIS = 5_000L
    const val AUTHENTICATION_MILLIS = 135_000L
    const val RECOVERY_WORKFLOW_MILLIS = PROMPT_MILLIS + AUTHENTICATION_MILLIS + PROMPT_MILLIS + 2 * DATABASE_MILLIS
    const val BIOMETRIC_WORKFLOW_MILLIS = AUTHENTICATION_MILLIS + 2 * PROMPT_MILLIS + 2 * DATABASE_MILLIS
    const val ENVELOPE_BYTES = 16 * 1024
    const val EMPTY_CATALOG_BYTES = 64 * 1024
    fun retryPolicy() = VaultRetryPolicy(
        listOf(0, 0, 0, 1_000, 5_000, 30_000, 60_000, 300_000, 900_000, 3_600_000),
        3_600_000, AUTHENTICATION_MILLIS, DATABASE_MILLIS, CLEANUP_MILLIS,
    )
}

internal fun interface VaultMonotonicClock { fun elapsedMillis(): Long }
internal class VaultOperationContext(
    val deadlineElapsedMillis: Long,
    private val clock: VaultMonotonicClock,
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
) {
    init { require(deadlineElapsedMillis >= 0) }
    fun checkActive() {
        if (cancelled.get()) throw VaultOperationException(VaultFailure.CANCELLED)
        val now = clock.elapsedMillis()
        if (now < 0 || now >= deadlineElapsedMillis) throw VaultOperationException(VaultFailure.DEADLINE)
    }
    fun cancel() { cancelled.set(true) }
    /** Exact cancellation lineage is shared only by bounded children of this operation. */
    fun sharesOperation(other: VaultOperationContext): Boolean = cancelled === other.cancelled
    fun bounded(budgetMillis: Long, deadlineElapsedMillis: Long = this.deadlineElapsedMillis): VaultOperationContext {
        require(budgetMillis > 0)
        val now = clock.elapsedMillis()
        checkActive()
        val end = if (now > Long.MAX_VALUE - budgetMillis) Long.MAX_VALUE else now + budgetMillis
        return VaultOperationContext(minOf(this.deadlineElapsedMillis, deadlineElapsedMillis, end), clock, cancelled)
    }
}
internal enum class VaultFailure {
    CANCELLED, DEADLINE, REJECTED, UNAVAILABLE, CORRUPT, CONFLICT, INCOMPLETE, UNSUPPORTED, RELEASE_UNKNOWN,
    // Only the corresponding trusted leaf may report these primary-Vault failures.
    DEVICE_KEY_UNAVAILABLE, INSTALLATION_MISMATCH_OR_CORRUPT, RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT, STORE_UNREADABLE,
}
/** Public, bounded explanation only. It is never authentication or repair authority. */
internal enum class VaultFailureReason {
    DEVICE_KEY_UNAVAILABLE, INSTALLATION_MISMATCH_OR_CORRUPT, RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT,
    STORE_UNREADABLE, STORE_INCOMPLETE, UNKNOWN,
}
internal fun VaultFailure.confirmedPrimaryFailure(): Boolean = when (this) {
    VaultFailure.DEVICE_KEY_UNAVAILABLE, VaultFailure.INSTALLATION_MISMATCH_OR_CORRUPT,
    VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT, VaultFailure.STORE_UNREADABLE -> true
    else -> false
}
internal fun VaultFailure.safeReason(): VaultFailureReason = when (this) {
    VaultFailure.DEVICE_KEY_UNAVAILABLE -> VaultFailureReason.DEVICE_KEY_UNAVAILABLE
    VaultFailure.INSTALLATION_MISMATCH_OR_CORRUPT -> VaultFailureReason.INSTALLATION_MISMATCH_OR_CORRUPT
    VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT -> VaultFailureReason.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT
    VaultFailure.STORE_UNREADABLE -> VaultFailureReason.STORE_UNREADABLE
    VaultFailure.INCOMPLETE -> VaultFailureReason.STORE_INCOMPLETE
    else -> VaultFailureReason.UNKNOWN
}
internal class VaultOperationException(val category: VaultFailure) : Exception(category.name)
internal enum class VaultCredentialKind { PIN, PASSWORD }
internal class VaultCredentialInput(val kind: VaultCredentialKind, chars: CharArray) : AutoCloseable {
    companion object {
        const val MAX_PIN_CHARS = 12
        const val MAX_PASSWORD_CHARS = 256
    }
    init { require(chars.size in 1..if (kind == VaultCredentialKind.PIN) MAX_PIN_CHARS else MAX_PASSWORD_CHARS) }
    private var value: CharArray? = chars.copyOf()
    @Synchronized fun copyCharacters(): CharArray = value?.copyOf() ?: throw VaultOperationException(VaultFailure.CANCELLED)
    @Synchronized override fun close() { value?.fill('\u0000'); value = null }
    override fun toString(): String = "VaultCredentialInput(REDACTED)"
}
/** Ciphertext only; defensive copies prevent mutation after CAS preparation. */
internal class VaultCiphertext(bytes: ByteArray, maximumBytes: Int = VaultRuntimeLimits.ENVELOPE_BYTES) {
    init { require(maximumBytes in 1..VaultRuntimeLimits.EMPTY_CATALOG_BYTES && bytes.isNotEmpty() && bytes.size <= maximumBytes) }
    private val value = bytes.copyOf()
    val size: Int get() = value.size
    fun copyBytes(): ByteArray = value.copyOf()
    override fun equals(other: Any?): Boolean = other is VaultCiphertext && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "VaultCiphertext(bytes=$size)"
}
internal data class VaultPersistentIdentity(
    val vaultId: String,
    val installationId: String,
    val initializationOperationId: String,
    val deviceKeyAlias: String,
    val biometricKeyAlias: String,
    val schemaVersion: Int = 1,
    val rootVersion: Int = 1,
)
internal enum class VaultStorePhase { INITIALIZING, STAGED, READY }
internal enum class VaultSlotKind { PIN, PASSWORD, BIOMETRIC, RECOVERY }
internal data class VaultSlotRecord(
    val slotId: String,
    val generation: Long,
    val kind: VaultSlotKind,
    val envelope: VaultCiphertext,
    val required: Boolean,
)
internal class VaultStagedData(
    slots: List<VaultSlotRecord>,
    val catalogKeysetEnvelope: VaultCiphertext,
    val catalogEnvelope: VaultCiphertext,
    val catalogRevision: Long,
    val installationMarker: VaultCiphertext,
    val retryLedger: VaultRetryLedger,
    val retryLedgerEnvelope: VaultCiphertext,
    val recoveryState: VaultRecoveryState = VaultRecoveryState.NONE,
    val pendingPassword: VaultPendingPassword? = null,
    val mediaJournalEnvelope: VaultCiphertext? = null,
    val mediaJournalRevision: Long = 0,
    val biometricState: VaultBiometricState = VaultBiometricState.NONE,
) {
    init {
        require((mediaJournalEnvelope == null) == (mediaJournalRevision == 0L) && mediaJournalRevision >= 0)
        require(mediaJournalEnvelope == null || mediaJournalEnvelope.size <= VaultMediaLimits.MAX_JOURNAL_BYTES)
        require(slots.size in 1..3 && slots.map { it.slotId }.distinct().size == slots.size)
        require(slots.all { it.slotId.length in 1..128 && it.generation > 0 && it.envelope.size <= VaultRuntimeLimits.ENVELOPE_BYTES })
        require(catalogRevision > 0 && catalogKeysetEnvelope.size <= VaultRuntimeLimits.ENVELOPE_BYTES &&
            catalogEnvelope.size <= VaultRuntimeLimits.EMPTY_CATALOG_BYTES && installationMarker.size <= VaultRuntimeLimits.ENVELOPE_BYTES &&
            retryLedgerEnvelope.size <= VaultRuntimeLimits.ENVELOPE_BYTES)
    }
    private val slotValues = slots.toList()
    val slots: List<VaultSlotRecord> get() = slotValues.toList()
    fun copy(slots: List<VaultSlotRecord> = this.slots,
        catalogKeysetEnvelope: VaultCiphertext = this.catalogKeysetEnvelope,
        catalogEnvelope: VaultCiphertext = this.catalogEnvelope,
        catalogRevision: Long = this.catalogRevision,
        installationMarker: VaultCiphertext = this.installationMarker,
        retryLedger: VaultRetryLedger = this.retryLedger,
        retryLedgerEnvelope: VaultCiphertext = this.retryLedgerEnvelope,
        recoveryState: VaultRecoveryState = this.recoveryState,
        pendingPassword: VaultPendingPassword? = this.pendingPassword,
        mediaJournalEnvelope: VaultCiphertext? = this.mediaJournalEnvelope, mediaJournalRevision: Long = this.mediaJournalRevision,
        biometricState: VaultBiometricState = this.biometricState) = VaultStagedData(
        slots, catalogKeysetEnvelope, catalogEnvelope, catalogRevision, installationMarker, retryLedger, retryLedgerEnvelope, recoveryState, pendingPassword, mediaJournalEnvelope, mediaJournalRevision, biometricState)
    override fun equals(other: Any?): Boolean = other is VaultStagedData && slotValues == other.slotValues &&
        catalogKeysetEnvelope == other.catalogKeysetEnvelope && catalogEnvelope == other.catalogEnvelope &&
        catalogRevision == other.catalogRevision && installationMarker == other.installationMarker &&
        retryLedger == other.retryLedger && retryLedgerEnvelope == other.retryLedgerEnvelope &&
        recoveryState == other.recoveryState && pendingPassword == other.pendingPassword &&
        mediaJournalEnvelope == other.mediaJournalEnvelope && mediaJournalRevision == other.mediaJournalRevision && biometricState == other.biometricState
    override fun hashCode(): Int = listOf(slotValues, catalogKeysetEnvelope, catalogEnvelope, catalogRevision,
        installationMarker, retryLedger, retryLedgerEnvelope, recoveryState, pendingPassword, mediaJournalEnvelope, mediaJournalRevision, biometricState).hashCode()
    override fun toString(): String = "VaultStagedData(REDACTED)"
}
internal data class VaultStoredSnapshot(
    val identity: VaultPersistentIdentity,
    val phase: VaultStorePhase,
    val storeRevision: Long,
    /** Null only in INITIALIZING. Missing rows in other phases must be rejected. */
    val data: VaultStagedData?,
)
internal sealed interface VaultStoreObservation {
    data object Absent : VaultStoreObservation
    data class Present(val snapshot: VaultStoredSnapshot) : VaultStoreObservation
    data class Unavailable(val category: VaultFailure) : VaultStoreObservation
}
/** Opaque crypto-owned proof. Production implementation validates owner identity and exact bytes. */
internal interface VaultStagedVerification : AutoCloseable {
    fun requireMatches(snapshot: VaultStoredSnapshot)
}
/** No key bytes or bool unlock credential cross this port. Crypto owner registers object identity. */
internal interface VaultRootCapability : AutoCloseable
internal data class VaultEmptyCatalog(val catalogRevision: Long)

internal enum class VaultRecoveryState { NONE, PENDING, ACTIVE }
/** Creating identity remains authenticated metadata even when a later ledger makes it stale. */
internal data class VaultPendingPassword(val slot: VaultSlotRecord, val attempt: VaultAttemptIdentity,
    val reservation: VaultRetryLedger) {
    override fun toString(): String = "VaultPendingPassword(REDACTED)"
}
internal enum class VaultCredentialMutationKind { STAGE_RECOVERY, ACTIVATE_RECOVERY, STAGE_PASSWORD_RESET, ACTIVATE_PASSWORD_RESET,
    STAGE_BIOMETRIC, ACTIVATE_BIOMETRIC, REVOKE_BIOMETRIC }
/** Only the injected authentication owner's registry can authorize this opaque object. */
internal interface VaultCredentialMutation : AutoCloseable
internal class VaultCredentialMutationProposal(val kind: VaultCredentialMutationKind, val data: VaultStagedData,
    val attempt: VaultAttemptIdentity?, val operationId: String) {
    override fun toString(): String = "VaultCredentialMutationProposal(REDACTED)"
}
/** Single owned, bounded transient input. Generated display and user re-entry use different instances. */
internal class VaultRecoveryInput(chars: CharArray) : AutoCloseable {
    init { require(chars.size in 1..43) }
    private var value: CharArray? = chars.copyOf()
    @Synchronized fun copyCharacters(): CharArray = value?.copyOf() ?: throw VaultOperationException(VaultFailure.CANCELLED)
    @Synchronized override fun close() { value?.fill('\u0000'); value = null }
    override fun toString(): String = "VaultRecoveryInput(REDACTED)"
}
internal class VaultRecoveryEnrollment(val mutation: VaultCredentialMutation, display: VaultRecoveryInput) : AutoCloseable {
    private var ownedDisplay: VaultRecoveryInput? = display
    @Synchronized fun takeDisplay(): VaultRecoveryInput = ownedDisplay?.also { ownedDisplay = null }
        ?: throw VaultOperationException(VaultFailure.CANCELLED)
    @Synchronized override fun close() { try { mutation.close() } finally { ownedDisplay?.close(); ownedDisplay = null } }
    override fun toString(): String = "VaultRecoveryEnrollment(REDACTED)"
}


/** State is authenticated by the catalog; availability observations never change it. */
internal enum class VaultBiometricState { NONE, PENDING, ACTIVE, REVOKED }
internal enum class VaultBiometricPurpose { ENROLL_WRAP, ENROLL_VERIFY, UNLOCK, RETIRE_ALIAS }
internal enum class VaultBiometricAvailability { AVAILABLE, UNAVAILABLE, KEY_UNAVAILABLE, TEMPORARY_LOCKOUT, PERMANENT_LOCKOUT }
/** Exact runtime/host references. The predicate only revokes; it cannot grant crypto authority. */
internal class VaultBiometricBinding(
    val purpose: VaultBiometricPurpose,
    val hostIdentity: Any,
    val platformHost: Any,
    val hostGeneration: Long,
    val workflowIdentity: Any?,
    private val current: () -> Boolean,
) {
    init { require(hostGeneration >= 0) }
    fun requireCurrent() { if (!current()) throw VaultOperationException(VaultFailure.CANCELLED) }
    fun sameWorkflow(other: VaultBiometricBinding): Boolean = hostIdentity === other.hostIdentity && platformHost === other.platformHost &&
        hostGeneration == other.hostGeneration && workflowIdentity === other.workflowIdentity
    override fun toString() = "VaultBiometricBinding(REDACTED)"
}
/** Prepared and registered before start. Closing revokes acceptance, not actual platform drain. */
internal interface VaultBiometricOperation : AutoCloseable {
    val context: VaultOperationContext
    fun ownsSystemPromptFocus(): Boolean = false
    fun onDrain(listener: () -> Unit)
    fun observeDrain(): com.easysavevideo.library.export.ExportRelease
}
internal sealed interface VaultBiometricResult {
    class Unlocked(val root: VaultRootCapability) : VaultBiometricResult
    class Mutation(val mutation: VaultCredentialMutation) : VaultBiometricResult
    class Failed(val failure: VaultFailure, val availability: VaultBiometricAvailability? = null) : VaultBiometricResult
}
internal enum class VaultBiometricAliasRetirementKind { REVOKED_SLOT, ORPHAN_NONE }
/** Authentication owner registry identity only; never an alias string or caller-authored proof. */
internal interface VaultBiometricAliasRetirement : AutoCloseable
internal enum class VaultBiometricAliasRetirementResult { ABSENT, UNKNOWN }

internal interface VaultStorePort : AutoCloseable {
    /** Pure nonblocking ledger snapshot, including factories which failed before returning a handle. */
    fun observeMediaDrain(): com.easysavevideo.library.export.ExportRelease = com.easysavevideo.library.export.ExportRelease.RELEASED
    fun compareAndSwapMedia(expected: VaultStoredSnapshot, mutation: VaultMediaMutation,
        context: VaultOperationContext): VaultStoredSnapshot = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun createMediaFile(operation: VaultMediaOperation, identity: VaultMediaObjectIdentity,
        context: VaultOperationContext): VaultOwnedCiphertext = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun openMediaFile(identity: VaultMediaObjectIdentity,
        context: VaultOperationContext): VaultOwnedCiphertext = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun commitMedia(expected: VaultStoredSnapshot, mutation: VaultMediaMutation, file: VaultOwnedCiphertext,
        proof: VaultFinalizedMediaProof, context: VaultOperationContext): VaultCommittedMedia = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun abandonUncommittedMedia(expected: VaultStoredSnapshot, cancelledMutation: VaultMediaMutation,
        file: VaultOwnedCiphertext?, context: VaultOperationContext): VaultUncommittedMoveReceipt = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun recoverCommittedMove(expected: VaultStoredSnapshot, operation: VaultMediaOperation, source: com.easysavevideo.library.MediaReadRef,
        file: VaultOwnedCiphertext, proof: VaultFinalizedMediaProof, context: VaultOperationContext): CommittedMoveReceipt = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun validateCommittedMove(receipt: CommittedMoveReceipt, source: com.easysavevideo.library.MediaReadRef,
        operation: VaultMediaOperation): Boolean = false
    fun validateUncommittedMove(receipt: VaultUncommittedMoveReceipt, source: com.easysavevideo.library.MediaReadRef,
        operation: VaultMediaOperation): Boolean = false
    /** Fixed source issuer is injected at store construction; caller cannot select a verifier. */
    fun sourceDeleted(receipt: CommittedMoveReceipt, proof: VaultSourceDeletedReceipt): Unit = throw VaultOperationException(VaultFailure.UNSUPPORTED)

    /** Fixed-owner registered target state: UNKNOWN/final publication decisions always reject deletion. */
    fun canCleanupPhoneTarget(record: VaultMediaTargetRecord): Boolean = false
    fun validateBoundPhoneTarget(record: VaultMediaTargetRecord, use: VaultPhoneTargetUse = VaultPhoneTargetUse.CLEANUP): Boolean = false
    fun validateBoundPhoneTree(record: VaultPhoneTreeRecord): Boolean = false
    fun validateBatchChildCreate(tree: VaultPhoneTreeRecord, reservation: VaultMediaTargetRecord,
        operation: VaultMediaOperation): Boolean = false
    fun validatePhoneTargetRetirement(operationId: String, target: VaultMediaTargetRecord, batchId: String? = null): Boolean = false
    fun compareAndSwapCredentials(expected: VaultStoredSnapshot, mutation: VaultCredentialMutation,
        context: VaultOperationContext): VaultStoredSnapshot = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun compareAndSwapCredentialReset(expected: VaultStoredSnapshot, write: VaultRetryWrite,
        authenticatedLedger: VaultCiphertext, mutation: VaultCredentialMutation,
        context: VaultOperationContext): VaultStoredSnapshot = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun inspect(context: VaultOperationContext): VaultStoreObservation
    fun beginInitialization(identity: VaultPersistentIdentity, context: VaultOperationContext): VaultStoredSnapshot
    fun stage(expected: VaultStoredSnapshot, prepared: VaultStagedData, context: VaultOperationContext): VaultStoredSnapshot
    fun markReady(expected: VaultStoredSnapshot, verification: VaultStagedVerification, context: VaultOperationContext): VaultStoredSnapshot
    fun compareAndSwapRetry(expected: VaultStoredSnapshot, write: VaultRetryWrite,
        authenticatedLedger: VaultCiphertext, context: VaultOperationContext): VaultStoredSnapshot
}
internal interface VaultAuthenticationPort : AutoCloseable {

    fun observeBiometricDrain(): com.easysavevideo.library.export.ExportRelease = com.easysavevideo.library.export.ExportRelease.RELEASED
    fun biometricAvailability(snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultBiometricAvailability = VaultBiometricAvailability.UNAVAILABLE
    fun prepareBiometricUnlock(snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
        binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricOperation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareBiometricEnrollment(root: VaultRootCapability, snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
        binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricOperation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    /** Prior operation must own the exact confirmed STAGE proposal and original workflow. */
    fun prepareBiometricActivation(snapshot: VaultStoredSnapshot, enrollment: VaultBiometricOperation,
        binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricOperation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun startBiometric(operation: VaultBiometricOperation, completed: (VaultBiometricResult) -> Unit): Unit = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareBiometricRevocation(root: VaultRootCapability, snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
        binding: VaultBiometricBinding, context: VaultOperationContext): VaultCredentialMutation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareBiometricAliasRetirement(root: VaultRootCapability, snapshot: VaultStoredSnapshot, attempt: VaultAttemptIdentity,
        binding: VaultBiometricBinding, context: VaultOperationContext): VaultBiometricAliasRetirement = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    /** Caller must inspect the store immediately before this call, under the existing exclusive workflow gate. */
    fun retireBiometricAlias(snapshot: VaultStoredSnapshot, permission: VaultBiometricAliasRetirement,
        context: VaultOperationContext): VaultBiometricAliasRetirementResult = throw VaultOperationException(VaultFailure.UNSUPPORTED)

    /** Same-owner media factories can retain failed readers. No IO: reports only real registry release. */
    fun observeMediaDrain(): com.easysavevideo.library.export.ExportRelease = com.easysavevideo.library.export.ExportRelease.RELEASED

    fun authenticateCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultAuthenticatedCatalog = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun authenticateMediaJournal(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultMediaJournal? = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareMediaImport(root: VaultRootCapability, snapshot: VaultStoredSnapshot, source: VaultMediaSourceEvidence,
        operation: VaultMediaOperation, identity: VaultMediaObjectIdentity,
        context: VaultOperationContext): VaultPreparedMediaImport = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun encryptMedia(prepared: VaultPreparedMediaImport, source: VaultMoveSourceCapability,
        target: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext): Unit = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun verifyFinalMedia(prepared: VaultPreparedMediaImport, file: VaultOwnedCiphertext,
        gate: VaultMediaPublicationGate, context: VaultOperationContext): VaultFinalizedMediaProof = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun verifyCommittedMedia(root: VaultRootCapability, snapshot: VaultStoredSnapshot, ref: VaultMediaReadRef,
        file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate, context: VaultOperationContext): VaultFinalizedMediaProof = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun validateCommittedMedia(expected: VaultStoredSnapshot, operation: VaultMediaOperation,
        source: com.easysavevideo.library.MediaReadRef, file: VaultOwnedCiphertext,
        proof: VaultFinalizedMediaProof, context: VaultOperationContext): VaultMediaEntry = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun finishMediaImport(root: VaultRootCapability, expected: VaultStoredSnapshot, prepared: VaultPreparedMediaImport,
        proof: VaultFinalizedMediaProof, nextJournal: VaultMediaJournal,
        context: VaultOperationContext): VaultMediaMutation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun sealMediaJournal(root: VaultRootCapability, expected: VaultStoredSnapshot, next: VaultMediaJournal,
        context: VaultOperationContext): VaultMediaMutation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun validateMediaMutation(expected: VaultStoredSnapshot, mutation: VaultMediaMutation,
        context: VaultOperationContext): VaultMediaProposal = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun validateFinalizedMedia(proof: VaultFinalizedMediaProof, file: VaultOwnedCiphertext,
        context: VaultOperationContext): VaultMediaFinalizedEvidence = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun openMediaRead(root: VaultRootCapability, snapshot: VaultStoredSnapshot, ref: VaultMediaReadRef,
        file: VaultOwnedCiphertext, gate: VaultMediaPublicationGate,
        context: VaultOperationContext): VaultMediaReadCapability = throw VaultOperationException(VaultFailure.UNSUPPORTED)

    fun prepareRecoveryEnrollment(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultRecoveryEnrollment = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun verifyPendingRecovery(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput,
        context: VaultOperationContext): Unit = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareRecoveryActivation(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput,
        context: VaultOperationContext): VaultCredentialMutation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun preparePasswordReset(snapshot: VaultStoredSnapshot, input: VaultRecoveryInput, password: VaultCredentialInput,
        attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultCredentialMutation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun verifyPendingPasswordReset(snapshot: VaultStoredSnapshot, password: VaultCredentialInput,
        attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultCredentialMutation = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun validateCredentialMutation(snapshot: VaultStoredSnapshot, mutation: VaultCredentialMutation,
        context: VaultOperationContext): VaultCredentialMutationProposal = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    /** Pure complete profile preflight before any initialization write or key side effect. */
    fun validatePasswordInput(input: VaultCredentialInput)
    /** Read-only platform observation; true prevents trusted-first-use. */
    fun hasExistingVaultAliases(context: VaultOperationContext): Boolean
    /** Called only after INITIALIZING with these exact aliases is durable. No call at application startup. */
    fun preparePasswordSetup(identity: VaultPersistentIdentity, input: VaultCredentialInput,
        context: VaultOperationContext): VaultStagedData
    /** Persisted bytes must be read again, not the in-memory prepared object. */
    fun verifyStaged(snapshot: VaultStoredSnapshot, input: VaultCredentialInput,
        context: VaultOperationContext): VaultStagedVerification
    /** Sole production owner registry check; store must call this, not trust proof self-report alone. */
    fun validateStagedVerification(snapshot: VaultStoredSnapshot, verification: VaultStagedVerification)
    fun authenticateLedger(snapshot: VaultStoredSnapshot, context: VaultOperationContext): VaultRetryLedger
    fun sealLedger(identity: VaultPersistentIdentity, ledger: VaultRetryLedger,
        context: VaultOperationContext): VaultCiphertext
    fun unlockPassword(snapshot: VaultStoredSnapshot, input: VaultCredentialInput,
        attempt: VaultAttemptIdentity, context: VaultOperationContext): VaultRootCapability
    fun readEmptyCatalog(root: VaultRootCapability, snapshot: VaultStoredSnapshot,
        context: VaultOperationContext): VaultEmptyCatalog
}
