package com.easysavevideo.app.vault

import com.easysavevideo.library.DeleteOutcome
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportCleanup
import com.easysavevideo.library.export.ExportEvidence
import com.easysavevideo.library.export.ExportInput
import com.easysavevideo.library.export.ExportLimits
import com.easysavevideo.library.export.ExportReadback
import com.easysavevideo.library.export.ExportRelease
import com.easysavevideo.library.export.ExportWrite

/** Source-only bounded profile. This is not an enabled product/security acceptance switch. */
internal object VaultMediaLimits {
    const val MAX_BYTES = ExportLimits.MAX_BYTES
    const val MAX_CIPHERTEXT_BYTES = 2_147_516_472L
    const val BUFFER_BYTES = ExportLimits.BUFFER_BYTES
    const val OPERATION_MILLIS = 30L * 60L * 1000L
    const val MAX_ZERO_PROGRESS = 8
    const val MAX_JOURNAL_BYTES = 64 * 1024
    const val PROFILE = 1 // Exact AES256_GCM_HKDF_1MB; never selected by caller text.
}
internal enum class VaultPhoneTargetUse { WRITE, READBACK, PUBLISH, CLEANUP }
internal enum class VaultMediaKind { MOVE_IN, COPY_OUT }
internal enum class VaultMediaPhase {
    PREPARED, SOURCE_RESERVED, ENCRYPTING, VERIFIED, COMMITTED_SOURCE_PENDING,
    PICKING_TARGET, TARGET_BOUND, SOURCE_VALIDATED, COPYING, VERIFYING, PUBLISH_INTENT,
    COMPLETE, CANCELLED, CLEANUP_PENDING, RECONCILIATION_REQUIRED,
    CHILD_CREATE_INTENT, OUTPUT_RETAINED,
}
internal data class VaultMediaOperation(val operationId: String, val sequence: Long, val kind: VaultMediaKind) {
    init { require(operationId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && sequence > 0) }
    override fun toString() = "VaultMediaOperation(kind=$kind, REDACTED)"
}
internal data class VaultMediaObjectIdentity(val objectId: String, val generation: Long) {
    init { require(objectId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && generation > 0) }
    override fun toString() = "VaultMediaObjectIdentity(REDACTED)"
}
internal data class VaultMediaReadRef(val vaultId: String, val objectId: String, val objectGeneration: Long, val catalogRevision: Long) {
    init { require(vaultId.matches(Regex("[A-Za-z0-9_-]{1,64}"))); VaultMediaObjectIdentity(objectId, objectGeneration); require(catalogRevision > 0) }
    val identity get() = VaultMediaObjectIdentity(objectId, objectGeneration)
    override fun toString() = "VaultMediaReadRef(REDACTED)"
}
internal data class VaultMediaSourceEvidence(val source: MediaReadRef, val evidence: ExportEvidence,
    val displayName: String, val durationMillis: Long?, val width: Int?, val height: Int?) {
    init {
        require(displayName == com.easysavevideo.library.LibraryNames.validate(displayName))
        require(durationMillis == null || durationMillis >= 0)
        require((width == null) == (height == null)); require(width == null || width > 0); require(height == null || height > 0)
    }
    override fun toString() = "VaultMediaSourceEvidence(REDACTED)"
}
internal data class VaultCiphertextEvidence(val bytes: Long, val sha256: String) {
    init { require(bytes in 1..VaultMediaLimits.MAX_CIPHERTEXT_BYTES && sha256.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString() = "VaultCiphertextEvidence(REDACTED)"
}
internal data class VaultMediaEntry(val identity: VaultMediaObjectIdentity, val profile: Int,
    val plaintext: ExportEvidence, val ciphertext: VaultCiphertextEvidence, val wrappedKeyset: VaultCiphertext,
    val displayName: String, val durationMillis: Long?, val width: Int?, val height: Int?,
    val moveOperation: VaultMediaOperation, val source: MediaReadRef, val finalized: Boolean = true,
    val sourceCleanupPending: Boolean = true) {
    init { require(profile == VaultMediaLimits.PROFILE && finalized && moveOperation.kind == VaultMediaKind.MOVE_IN) }
    override fun toString() = "VaultMediaEntry(REDACTED)"
}
internal class VaultAuthenticatedCatalog(val catalogRevision: Long, entries: List<VaultMediaEntry>) {
    private val values = entries.toList()
    val entries: List<VaultMediaEntry> get() = values.toList()
    init { require(catalogRevision > 0 && values.map { it.identity }.distinct().size == values.size) }
    override fun toString() = "VaultAuthenticatedCatalog(count=${values.size})"
}
/** Stored only inside an authenticated encrypted journal; URI text never recreates a grant. */
internal data class VaultMediaTargetRecord(val operationId: String, val grantId: String, val nonce: String,
    val uri: String?, val flags: Int) {
    init {
        require(operationId.length in 1..64 && grantId.length in 1..128 && nonce.length in 1..64)
        require(uri == null || uri.length in 1..8192 && uri.toByteArray(Charsets.UTF_8).size in 1..8192); require(flags in 0..3)
    }
    override fun toString() = "VaultMediaTargetRecord(REDACTED)"
}
internal data class VaultMediaJournal(val operation: VaultMediaOperation, val phase: VaultMediaPhase,
    val expectedCatalogRevision: Long, val source: MediaReadRef? = null,
    val objectIdentity: VaultMediaObjectIdentity? = null, val target: VaultMediaTargetRecord? = null,
    val cancelRequested: Boolean = false, val plaintextEvidence: ExportEvidence? = null,
    val ciphertextEvidence: VaultCiphertextEvidence? = null, val batch: VaultCopyBatchState? = null) {
    init { require(expectedCatalogRevision > 0); require(target == null || target.operationId == operation.operationId) }
    override fun toString() = "VaultMediaJournal(kind=${operation.kind}, phase=$phase)"
}
/** These objects confer no authority unless present in their injected owner's identity registry. */
internal interface VaultPreparedMediaImport : AutoCloseable
internal interface VaultMediaMutation : AutoCloseable
internal interface VaultFinalizedMediaProof : AutoCloseable
internal interface CommittedMoveReceipt : AutoCloseable
internal interface VaultUncommittedMoveReceipt : AutoCloseable
internal interface VaultMoveReservation : AutoCloseable
internal interface VaultOrdinaryRetirementReceipt
internal interface VaultSourceDeletedReceipt
internal interface VaultPhoneFileRequest
internal interface VaultPhoneDirectoryTarget
internal sealed interface VaultPhoneSelection {
    data class File(val target: VaultPhoneFileTarget) : VaultPhoneSelection
    data class Directory(val target: VaultPhoneDirectoryTarget) : VaultPhoneSelection
}
internal interface VaultPhoneFileTarget
internal data class VaultMediaProposal(val operation: VaultMediaOperation, val phase: VaultMediaPhase,
    val proposed: VaultStoredSnapshot, val journal: VaultMediaJournal, val finalObject: VaultMediaObjectIdentity? = null,
    val source: MediaReadRef? = null, val durablePin: Boolean = false)
internal data class VaultMediaFinalizedEvidence(val identity: VaultMediaObjectIdentity,
    val plaintext: ExportEvidence, val ciphertext: VaultCiphertextEvidence)
internal data class VaultCommittedMedia(val snapshot: VaultStoredSnapshot, val receipt: CommittedMoveReceipt)
internal data class VaultSourceCleanupResult(val outcome: DeleteOutcome, val receipt: VaultSourceDeletedReceipt?)

/** Ciphertext only. A subordinate of the one store owner; never a path/URI or UI object.
 * readAt stays anchored to the same immutable file identity across authentication/export passes.
 * Successful forceAndCloseWrite permanently seals append; opened/final handles always reject append.
 * Close uncertainty prevents final proof; no write is legal after proof issuance.
 * Closing a Tink per-pass adapter must NOT close this anchor; the workflow owns final release. */
internal interface VaultOwnedCiphertext {
    val operationId: String
    val identity: VaultMediaObjectIdentity
    fun append(bytes: ByteArray, offset: Int, length: Int)
    fun readAt(position: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun size(): Long
    fun forceAndCloseWrite(): ExportRelease
    fun closeAndObserve(): ExportRelease
}
internal interface VaultMoveSourceCapability {
    val metadata: VaultMediaSourceEvidence
    val input: ExportInput
}
/** The runtime invokes no blocking work while holding this gate. publish is one bounded copy. */
internal interface VaultMediaPublicationGate {
    fun requireCurrent()
    fun admit(): VaultMediaIoPermit
}
internal interface VaultMediaIoPermit : AutoCloseable {
    fun publish(scratch: ByteArray, sourceOffset: Int, destination: ByteArray, destinationOffset: Int, length: Int)
    /** Finally-only, after actual IO return and scratch retirement; never on timeout. */
    override fun close()
}
internal interface VaultMediaReadCapability {
    val authenticatedSize: Long
    fun readAt(position: Long, destination: ByteArray, offset: Int, length: Int, context: VaultOperationContext): Int
    fun revoke()
    fun closeAndObserve(): ExportRelease
}
/** Same AndroidSaveStorage/Library owner, injected into the existing Vault entry. */
internal interface VaultMediaSourcePort {
    /** Pure nonblocking source-owner release observation; never initializes or reads storage. */
    fun observeMediaDrain(): ExportRelease = ExportRelease.RELEASED
    fun reserve(ref: MediaReadRef, operation: VaultMediaOperation): VaultMoveReservation
    /** Recovery may return a cancel-only token proving this exact operation never reserved a source. */
    fun reserveForReconciliation(ref: MediaReadRef, operation: VaultMediaOperation): VaultMoveReservation = reserve(ref, operation)
    fun retirePresentation(reservation: VaultMoveReservation, onRetired: (VaultOrdinaryRetirementReceipt) -> Unit)
    fun acquire(reservation: VaultMoveReservation, retired: VaultOrdinaryRetirementReceipt): VaultMoveSourceCapability
    fun cleanup(reservation: VaultMoveReservation, receipt: CommittedMoveReceipt): VaultSourceCleanupResult
    fun cancelUncommitted(reservation: VaultMoveReservation, receipt: VaultUncommittedMoveReceipt): Boolean
    fun validateSourceRestored(ref: MediaReadRef, operation: VaultMediaOperation): Boolean = false
    fun validateSourceDeleted(receipt: VaultSourceDeletedReceipt, ref: MediaReadRef, operation: VaultMediaOperation): Boolean
}
/** Platform owns the sole outstanding picker and actual temporary grant. No arbitrary URI input. */
internal interface VaultPhoneFilesPort {
    /** Pure nonblocking actual-resource ledger; no resolver IO or grant acquisition. */
    fun observeMediaDrain(): ExportRelease = ExportRelease.RELEASED
    fun retireSelection(request: VaultPhoneFileRequest): Boolean = false
    fun prepareSelection(operation: VaultMediaOperation): VaultPhoneFileRequest
    fun prepareCleanupSelection(record: VaultMediaTargetRecord): VaultPhoneFileRequest = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareDirectorySelection(batchId: String): VaultPhoneFileRequest = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareDirectoryRecoverySelection(record: VaultPhoneTreeRecord): VaultPhoneFileRequest = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun directoryRecord(target: VaultPhoneDirectoryTarget, batchId: String): VaultPhoneTreeRecord = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun validateDirectory(target: VaultPhoneDirectoryTarget): Unit = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun prepareBatchChild(target: VaultPhoneDirectoryTarget, operation: VaultMediaOperation): VaultMediaTargetRecord = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun createBatchChild(directory: VaultPhoneDirectoryTarget, reservation: VaultMediaTargetRecord,
        operation: VaultMediaOperation, gate: VaultMediaPublicationGate): VaultPhoneFileTarget = throw VaultOperationException(VaultFailure.UNSUPPORTED)
    fun retirePhoneTarget(target: VaultPhoneFileTarget, operationId: String, batchId: String? = null): Boolean = false
    fun targetRecord(target: VaultPhoneFileTarget, operation: VaultMediaOperation): VaultMediaTargetRecord
    fun openWrite(target: VaultPhoneFileTarget): ExportWrite
    fun openReadback(target: VaultPhoneFileTarget): ExportReadback
    fun publish(target: VaultPhoneFileTarget)
    fun cleanup(target: VaultPhoneFileTarget): ExportCleanup
    fun recoverTarget(record: VaultMediaTargetRecord): VaultPhoneFileTarget?
    fun resourcesReleased(target: VaultPhoneFileTarget): Boolean
}

/** Finite wire profile. These metadata values never confer provider or crypto authority. */
internal object VaultCopyBatchLimits {
    const val MAX_ITEMS = 8
    const val MAX_URI_UTF8_BYTES = 1024
    const val MAX_RUN_STARTS = 8
    const val RUN_MILLIS = VaultMediaLimits.OPERATION_MILLIS
    const val MAX_DIRECTORY_ENTRIES = 1000 // Same admitted FolderCreateIdentity.MAX_CHILDREN profile.
    const val MAX_DIRECTORY_ID_BYTES = 1048576
    const val PROFILE = 1
}
internal enum class VaultCopyBatchOutcome(val wire: Int) {
    WAITING(0), ACTIVE(1), VERIFIED_COPY(2), FAILED_NO_OUTPUT(3), FAILED_CLEANED(4),
    CANCELLED_BEFORE_START(5), CANCELLED_CLEANED(6), PAUSED_NOT_STARTED(7), CLEANUP_REQUIRED(8),
    UNRESOLVED_RETAINED(9), FINAL_DECISION_PENDING(10),
}
internal enum class VaultCopyBatchStop(val wire: Int) { NONE(0), USER_CANCEL(1), LIFECYCLE_PAUSE(2), TARGET_PAUSE(3), BUDGET_EXHAUSTED(4) }
internal enum class VaultCopyBatchStatus(val wire: Int) { PREPARING(0), AWAITING_CONFIRMATION(1), RUNNING(2), PAUSED(3), FINISHED(4), BUDGET_EXHAUSTED(5) }
internal data class VaultCopyBatchItem(val operationId: String, val identity: VaultMediaObjectIdentity,
    val activatedSequence: Long? = null, val outcome: VaultCopyBatchOutcome = VaultCopyBatchOutcome.WAITING,
    val failure: VaultFailure? = null, val plaintextEvidence: ExportEvidence? = null,
    val target: VaultMediaTargetRecord? = null) {
    init { require(operationId.matches(Regex("[a-f0-9]{32}"))); require(activatedSequence == null || activatedSequence > 0) }
    override fun toString() = "VaultCopyBatchItem(outcome=$outcome, REDACTED)"
}
internal data class VaultPhoneTreeRecord(val batchId: String, val grantId: String, val nonce: String,
    val uri: String, val flags: Int) {
    init {
        require(listOf(batchId, grantId, nonce).all { it.matches(Regex("[a-f0-9]{32}")) })
        require(uri.length in 1..VaultCopyBatchLimits.MAX_URI_UTF8_BYTES && flags == 3)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(uri))
        require(encoded.remaining() <= VaultCopyBatchLimits.MAX_URI_UTF8_BYTES)
    }
    override fun toString() = "VaultPhoneTreeRecord(REDACTED)"
}
/** Defensive immutable list: neither caller mutation nor a cast of [items] can change a sealed plan. */
internal class VaultCopyBatchState(val batchId: String, val selectedCatalogRevision: Long,
    val profile: Int, val planDigest: String, items: List<VaultCopyBatchItem>, val tree: VaultPhoneTreeRecord?,
    val activeIndex: Int, val runCount: Int, val stop: VaultCopyBatchStop, val status: VaultCopyBatchStatus) {
    private val values = items.also { require(it.size in 1..VaultCopyBatchLimits.MAX_ITEMS) }.toList()
    val items: List<VaultCopyBatchItem> get() = values.toList()
    init {
        require(batchId.matches(Regex("[a-f0-9]{32}")) && selectedCatalogRevision > 0 && profile == VaultCopyBatchLimits.PROFILE)
        require(planDigest.matches(Regex("[a-f0-9]{64}")))
        require(values.size in 1..VaultCopyBatchLimits.MAX_ITEMS && activeIndex in values.indices && runCount in 0..VaultCopyBatchLimits.MAX_RUN_STARTS)
        require(values.map { it.identity }.distinct().size == values.size && values.map { it.operationId }.distinct().size == values.size)
        require(tree == null || tree.batchId == batchId)
    }
    fun copy(batchId: String = this.batchId, selectedCatalogRevision: Long = this.selectedCatalogRevision,
        profile: Int = this.profile, planDigest: String = this.planDigest, items: List<VaultCopyBatchItem> = this.items,
        tree: VaultPhoneTreeRecord? = this.tree, activeIndex: Int = this.activeIndex, runCount: Int = this.runCount,
        stop: VaultCopyBatchStop = this.stop, status: VaultCopyBatchStatus = this.status) = VaultCopyBatchState(
        batchId, selectedCatalogRevision, profile, planDigest, items, tree, activeIndex, runCount, stop, status)
    override fun equals(other: Any?): Boolean = other is VaultCopyBatchState && batchId == other.batchId &&
        selectedCatalogRevision == other.selectedCatalogRevision && profile == other.profile && planDigest == other.planDigest &&
        values == other.values && tree == other.tree && activeIndex == other.activeIndex && runCount == other.runCount && stop == other.stop && status == other.status
    override fun hashCode(): Int = listOf(batchId, selectedCatalogRevision, profile, planDigest, values, tree, activeIndex, runCount, stop, status).hashCode()
    override fun toString() = "VaultCopyBatchState(count=${values.size}, status=$status, REDACTED)"
}
