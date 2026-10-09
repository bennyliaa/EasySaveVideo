package com.easysavevideo.library.export

import com.easysavevideo.library.LibraryIds
import com.easysavevideo.library.MediaReadRef
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

object ExportLimits {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    const val BUFFER_BYTES = 64 * 1024
    const val MAX_ACTIVE = 32
    const val MAX_RUNNING = 4
    const val MAX_RECOVERY_BATCH = 32
}

enum class ExportKind { MEDIA_STORE, SAF, SHARE_COPY }
enum class ExportPhase {
    PREPARED, CREATE_INTENT, COPYING, VERIFYING, PUBLISH_INTENT,
    SUCCEEDED, COPIED_UNVERIFIED, CLEANUP_PENDING, FAILED, CANCELLED, RECONCILIATION_REQUIRED;
    val terminal: Boolean get() = this in setOf(SUCCEEDED, COPIED_UNVERIFIED, FAILED, CANCELLED)
}
enum class ExportVerification { NONE, SOURCE_VERIFIED, DESTINATION_VERIFIED, READBACK_UNSUPPORTED }
enum class ExportRelease { RELEASED, UNCONFIRMED }
enum class ExportCleanup { NOT_NEEDED, REMOVED, PENDING, FAILED, UNCONFIRMED }
enum class ExportError {
    NONE, IO, SOURCE_MISMATCH, DESTINATION_MISMATCH, RELEASE_UNCONFIRMED,
    CREATE_UNCERTAIN, PUBLISH_UNCERTAIN, CANCELLED, RECOVERY_REQUIRED, READBACK_UNSUPPORTED,
    IDEMPOTENCY_CONFLICT, CAPACITY, INVALID_STATE,
}
enum class ExportCancellation { ACCEPTED, TOO_LATE, NOT_FOUND }
class ExportFailure(val code: ExportError) : RuntimeException(code.name)

/** A grant ID is an opaque adapter-issued capability identity, never a caller path or URI. */
data class ExportDestination(val kind: ExportKind, val grantId: String) {
    init { LibraryIds.requireValid(grantId) }
    override fun toString() = "ExportDestination(kind=$kind, [redacted])"
}

data class ExportRequest(val operationId: String, val source: MediaReadRef, val destination: ExportDestination) {
    init { LibraryIds.requireValid(operationId) }
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("ordinary-export-v1", operationId, source.itemId, source.revision.toString(),
            source.representationGeneration.toString(), destination.kind.name, destination.grantId).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().exportHex()
    }
    override fun toString() = "ExportRequest([redacted], kind=${destination.kind})"
}

/** Issued by the storage owner while resolving the exact source. User input is not evidence. */
data class ExportEvidence(val bytes: Long, val sha256: String) {
    init { require(bytes in 1..ExportLimits.MAX_BYTES); require(sha256.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString() = "ExportEvidence([redacted])"
}

data class ExportOutput(val outputId: String, val kind: ExportKind) {
    init { LibraryIds.requireValid(outputId) }
    override fun toString() = "ExportOutput(kind=$kind, [redacted])"
}

data class ExportRecord(
    val request: ExportRequest,
    val phase: ExportPhase = ExportPhase.PREPARED,
    val output: ExportOutput? = null,
    val evidence: ExportEvidence? = null,
    val cancelRequested: Boolean = false,
    val verification: ExportVerification = ExportVerification.NONE,
    val cleanup: ExportCleanup = ExportCleanup.NOT_NEEDED,
    val error: ExportError = ExportError.NONE,
) {
    init {
        require(output == null || output.kind == request.destination.kind)
        require(verification == ExportVerification.NONE || evidence != null)
        require(phase != ExportPhase.SUCCEEDED ||
            (output != null && verification == ExportVerification.DESTINATION_VERIFIED && !cancelRequested))
        require(phase != ExportPhase.COPIED_UNVERIFIED ||
            (output != null && request.destination.kind == ExportKind.SAF &&
                verification == ExportVerification.READBACK_UNSUPPORTED && !cancelRequested))
    }
    val operationId: String get() = request.operationId
    val fingerprint: String get() = request.fingerprint()
    override fun toString() = "ExportRecord(phase=$phase, verification=$verification, cleanup=$cleanup, error=$error)"
}

/**
 * One application-wide coordinator owns this journal. Calls are serialized by its commit gate.
 * Every mutation is durable before returning; exceptions must never be interpreted as rollback.
 * create atomically replays identical fingerprints, rejects conflicts, and enforces MAX_ACTIVE
 * before side effects. Terminal history does not consume active capacity. Rows and every query are
 * bounded; retain necessary idempotency/cleanup history without a lifetime export-count cutoff.
 * Retained operation IDs cannot silently be evicted and reused.
 * put must preserve request/fingerprint and must not clear an already persisted cancellation.
 * No names, paths, URIs, titles, or exception text belong in these records.
 */
interface ExportJournal {
    fun create(request: ExportRequest): ExportRecord
    fun get(operationId: String): ExportRecord?
    fun put(record: ExportRecord)
    /** Fair/rotating bounded unfinished records; a failing early item cannot starve later records. */
    fun unfinished(limit: Int): List<ExportRecord>
}

/** close retains all storage/native ownership until release is confirmed; it never guesses. */
interface ExportInput {
    val stream: InputStream
    fun close(): ExportRelease
}
interface ExportWrite {
    val stream: OutputStream
    fun close(): ExportRelease
}
data class ExportSource(val ref: MediaReadRef, val evidence: ExportEvidence, val input: ExportInput) {
    override fun toString() = "ExportSource([redacted])"
}
sealed class ExportReadback {
    data class Opened(val input: ExportInput) : ExportReadback()
    data object Unsupported : ExportReadback()
}
sealed class ExportReconciliation {
    /** Absence and release of ALL operation handles must be established, including across the
     * create-to-record gap. A null URI alone is not proof. */
    data object Absent : ExportReconciliation()
    /** published means committed visibility (MediaStore IS_PENDING=0), not SAF partial visibility. */
    data class Owned(val output: ExportOutput, val published: Boolean, val resourcesReleased: Boolean) : ExportReconciliation()
    data object Unknown : ExportReconciliation()
}

/**
 * Android adapters resolve only operation-issued capabilities, never caller-controlled paths.
 * Source opening atomically validates item/revision/generation/lifecycle and acquires its read lease.
 * Creating an output may have happened even if create throws: reconcile must resolve that gap.
 * MEDIA_STORE create means app-owned IS_PENDING=1; only publish can clear it. SAF is nonatomic from
 * creation onward. SHARE_COPY remains private to a narrowly scoped read-only app provider.
 * Output identity is durably owned by this operation before openWrite; overwriting unrelated
 * documents is forbidden. flush/close do not imply readback success. Unsupported is appropriate
 * only when the provider cannot read back, not as a substitute for a failed or corrupt read.
 * cleanup acts only on this operation's owned output, never the original or unrelated media;
 * refuses removal while any handle release is uncertain; reports verified absence as REMOVED.
 * reconcile checks durable operation ownership, actual pending/published state and live handles.
 */
interface ExportPort {
    fun openSource(request: ExportRequest): ExportSource
    fun create(record: ExportRecord): ExportOutput
    fun openWrite(record: ExportRecord, output: ExportOutput): ExportWrite
    fun openReadback(record: ExportRecord, output: ExportOutput): ExportReadback
    fun publish(record: ExportRecord, output: ExportOutput)
    fun cleanup(record: ExportRecord, output: ExportOutput): ExportCleanup
    fun reconcile(record: ExportRecord): ExportReconciliation
}

internal fun ByteArray.exportHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and 255
    "0123456789abcdef"[value ushr 4].toString() + "0123456789abcdef"[value and 15]
}
