package com.easysavevideo.save

import com.easysavevideo.core.model.OperationContext

/** A SQL-only fence, including rows discoverable by task/commit when Save has no artifact. */
enum class DirectWorkspacePresence { ABSENT, PRESENT, UNREADABLE }
enum class DirectRawKind { ALREADY_MP4, STRICT_MOV, WEBM_CANDIDATE }
enum class DirectRecoveryAction { RAW_COMPLETE, ADOPT, VERIFY, COMMIT, REVIEW }

/** A scan key, not deletion authority. Null retains an active, missing or unreadable row. */
data class DirectTerminalCleanupEntry(val cursor: Long, val record: SaveRecord?)
sealed interface DirectTerminalCleanupPage {
    /** Every scanned key appears, including ineligible rows; keys strictly increase after `after`. */
    data class Readable(val entries: List<DirectTerminalCleanupEntry>, val hasMore: Boolean) : DirectTerminalCleanupPage
    /** A failure to enumerate keys, not an empty page. The caller must retain its cursor. */
    data object Unreadable : DirectTerminalCleanupPage
}

/** Implementations are storage-issued identity handles, never reconstructible durable authority. */
interface DirectRawSelection : AutoCloseable { val kind: DirectRawKind }
interface DirectProofCompletion : AutoCloseable { val proof: DirectNormalizationProof }
/** Distinct producer result; it grants no converted adoption, decode or publication. */
interface DirectConversionProofCompletion : AutoCloseable { val proof: DirectConversionProof }
interface DirectVerificationCompletion : AutoCloseable { val evidence: VerificationEvidence }
interface NormalizedCommitHandle : AutoCloseable
interface NormalizedPublicationHandle { val receipt: MediaCommitReceipt }

/**
 * Production composition of the existing storage/remux/native owners. Every method must validate
 * the exact issued execution, expected local gate and its own storage identity before IO. Handles
 * are private-registry identities, not permission obtained by implementing these interfaces.
 * No File, descriptor, native batch owner or platform class crosses this core boundary.
 */
interface ControlledDirectStorage {
    /**
     * Memory-only actual-release event after exact raw Save CAS confirmation. Read the complete
     * core-issued fact with recordedTransferFrom(expectedAdmission), even if stop already won.
     * This callback opens no IO, issues no capability, and cannot turn a legacy branch back into MOV.
     */
    fun onTransferReleased(execution: AcceptedDirectAttempt)
    /** Pure bounded SQL ownership lookup. PRESENT includes CLEANED and orphan/conflicting rows. */
    fun inspectBeforeNetwork(taskId: String, artifact: StagingArtifact?, commitId: String): DirectWorkspacePresence
    /** Memory-only actual obligations; healthy currently-owned work is not itself a drain. */
    fun requiresProcessRecovery(): Boolean
    /**
     * SQL-only bounded keyset scan of non-CLEANED ledger rows with exact terminal Save snapshots.
     * Return at most limit entries (1..100); corrupt/ineligible rows have a null record so they do
     * not starve later keys. Inspect only the extra key, not its payload, for hasMore. A snapshot
     * grants no deletion right: cleanupTerminal must reread the exact ledger/Save/plan under its
     * own reservation. Empty readable pages have hasMore=false. Never start IO or resume work.
     */
    fun terminalCleanupPage(after: Long, limit: Int): DirectTerminalCleanupPage = DirectTerminalCleanupPage.Unreadable
    /** Closed readonly raw probe; its reservation is held until close or normalize handoff. */
    fun selectRaw(record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRawSelection
    /** Only a fresh acknowledged RAW_COMPLETE -> WRITING transition may issue its writer. */
    fun normalize(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectProofCompletion
    /** Same owned attempt and original Scope; candidate selection is not profile acceptance. */
    fun convert(selection: DirectRawSelection, record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext, limits: SaveLimits): DirectConversionProofCompletion =
        throw SaveFailure(SaveError.UNSUPPORTED_MEDIA)
    /** Never reconstruct producer proof from a digest, repeat remux or acquire an HTTP session. */
    fun inspectRecovery(record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): DirectRecoveryAction
    fun adopt(record: SaveRecord, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext)
    /** Records actual legacy issuance and positive native/RAF release, not just Future completion. */
    fun verifyTransferred(record: SaveRecord, output: ArtifactOutputDescriptor.Transferred,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): VerificationEvidence
    /** Registers the production binding and calls the same serial verifier/native lane. */
    fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion
    fun verifyNormalized(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext, verifier: ArtifactVerifier): DirectVerificationCompletion =
        throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.ConvertedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord,
        output: ArtifactOutputDescriptor.ConvertedDirect, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle =
        throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    fun beginNormalizedCommit(record: SaveRecord, output: ArtifactOutputDescriptor.NormalizedDirect,
        execution: AcceptedDirectAttempt, permit: DirectLocalProcessingGate.LocalPermit,
        context: OperationContext): NormalizedCommitHandle
    fun inspectNormalizedCommit(handle: NormalizedCommitHandle, record: SaveRecord,
        output: ArtifactOutputDescriptor.NormalizedDirect, execution: AcceptedDirectAttempt,
        permit: DirectLocalProcessingGate.LocalPermit, context: OperationContext): NormalizedPublicationHandle
    /**
     * Exact durable terminal plan only. The original invocation context binds the live permit;
     * a separate bounded IO context may survive cancellation but grants no new execution authority.
     */
    fun cleanupTerminal(record: SaveRecord, permit: DirectLocalProcessingGate.LocalPermit,
        invocationContext: OperationContext, ioContext: OperationContext)
}

/** Typed transitions use the SAME Save/catalog/Library transaction and durable mutation policy. */
interface ControlledDirectJournal : SaveJournal {
    fun compareAndSetProof(expectedRevision: Long, next: SaveRecord, completion: DirectProofCompletion): Boolean = false
    fun compareAndSetConversionProof(expectedRevision: Long, next: SaveRecord,
        completion: DirectConversionProofCompletion): Boolean = false
    fun compareAndSetVerification(expectedRevision: Long, next: SaveRecord,
        completion: DirectVerificationCompletion): Boolean = false
    fun compareAndSetNormalized(expectedRevision: Long, next: SaveRecord,
        publication: NormalizedPublicationHandle): Boolean = false
}

/** The production Coordinator owns this composition; the admission flag does not bypass checks. */
class ControlledDirectPipeline(
    val localGate: DirectLocalProcessingGate,
    val storage: ControlledDirectStorage,
    val movAdmissionEnabled: Boolean = false,
    /** Private routing only. Never changes public MIME policy or grants execution authority. */
    val conversionExecutionEnabled: Boolean = false,
) {
    fun acceptsRawMime(mime: String): Boolean = when (mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)) {
        "video/mp4", "application/mp4", "application/octet-stream" -> true
        "video/quicktime" -> movAdmissionEnabled
        else -> false
    }
}
