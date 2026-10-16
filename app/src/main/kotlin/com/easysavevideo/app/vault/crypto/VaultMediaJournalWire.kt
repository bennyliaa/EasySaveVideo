package com.easysavevideo.app.vault.crypto

import com.easysavevideo.app.vault.*
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.ExportEvidence
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Encrypted evidence only. Decoding never restores a grant, owner, read proof or resource drain.
 * Legacy scalar bytes are frozen at v1. v2 adds one bounded batch or retained-single receipt.
 */
internal object VaultMediaJournalWire {
    const val MAX_ENVELOPE_BYTES = VaultRuntimeLimits.ENVELOPE_BYTES
    const val MAX_PLAIN_BYTES = MAX_ENVELOPE_BYTES - 2048
    private const val MAGIC = 0x45534a32
    private const val LEGACY_LAST_PHASE = 14
    private val ID = Regex("[a-f0-9]{32}")
    private val DIGEST = Regex("[a-f0-9]{64}")
    private val terminal = setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED)
    private val rowTerminal = setOf(VaultCopyBatchOutcome.VERIFIED_COPY, VaultCopyBatchOutcome.FAILED_NO_OUTPUT,
        VaultCopyBatchOutcome.FAILED_CLEANED, VaultCopyBatchOutcome.CANCELLED_BEFORE_START,
        VaultCopyBatchOutcome.CANCELLED_CLEANED, VaultCopyBatchOutcome.UNRESOLVED_RETAINED)
    private val closedBatch = setOf(VaultCopyBatchStatus.FINISHED, VaultCopyBatchStatus.BUDGET_EXHAUSTED)

    private fun version(value: VaultMediaJournal) = if (value.batch != null || value.phase == VaultMediaPhase.OUTPUT_RETAINED) 2 else 1

    fun encode(value: VaultMediaJournal): ByteArray = with(VaultMediaWireIO) {
        validate(value)
        val version = version(value)
        encode(MAX_PLAIN_BYTES) {
            writeInt(MAGIC); writeInt(version); writeScalarBinding(value)
            writeInt(phaseCode(value.phase)); writeBoolean(value.cancelRequested)
            writeBoolean(value.target != null); value.target?.let { target(it, value.batch != null) }
            writeBoolean(value.plaintextEvidence != null); value.plaintextEvidence?.let { evidence(it) }
            writeBoolean(value.ciphertextEvidence != null); value.ciphertextEvidence?.let { cipherEvidence(it) }
            if (version == 2) { writeBoolean(value.batch != null); value.batch?.let { batch(it) } }
        }
    }
    fun decode(bytes: ByteArray): VaultMediaJournal = with(VaultMediaWireIO) {
        require(bytes.size in 1..MAX_PLAIN_BYTES) { "media-journal-size" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "media-journal-magic" }
        val version = input.readInt(); require(version in 1..2) { "media-journal-version" }
        val binding = readScalarBinding(input)
        val phaseNumber = input.readInt()
        require(version == 2 || phaseNumber in 0..LEGACY_LAST_PHASE) { "media-journal-v1-phase" }
        val phase = phase(phaseNumber)
        val cancelled = input.bool()
        // Before the batch flag is read, the whole input is already bounded to 14 KiB.
        // Per-field legacy bounds are narrowed by validate() before this record is accepted.
        val target = if (input.bool()) input.target(false) else null
        val plain = if (input.bool()) input.evidence() else null
        val cipher = if (input.bool()) input.cipherEvidence() else null
        val batch = if (version == 2 && input.bool()) input.batch() else null
        val result = binding.copy(phase = phase, target = target, cancelRequested = cancelled,
            plaintextEvidence = plain, ciphertextEvidence = cipher, batch = batch)
        require(input.available() == 0 && version(result) == version) { "media-journal-canonical-version" }
        validate(result)
        val canonical = encode(result)
        try { require(MessageDigest.isEqual(bytes, canonical)) { "media-journal-canonical" } }
        finally { canonical.fill(0) }
        result
    }

    /** Compact authenticated header; a decoded header is never a fabricated batch journal. */
    internal data class Binding(val operation: VaultMediaOperation, val expectedCatalogRevision: Long,
        val source: MediaReadRef?, val objectIdentity: VaultMediaObjectIdentity?,
        val batchId: String? = null, val planDigest: String? = null, val activeIndex: Int? = null) {
        override fun toString() = "VaultMediaJournalBinding(REDACTED)"
    }
    fun binding(value: VaultMediaJournal): ByteArray {
        validate(value)
        return VaultMediaWireIO.encode(1024) {
            writeInt(MAGIC); writeInt(version(value)); writeScalarBinding(value)
            if (version(value) == 2) with(VaultMediaWireIO) {
                writeBoolean(value.batch != null)
                value.batch?.let { text(it.batchId, 32); blob(digestBytes(it.planDigest), 32); writeInt(it.activeIndex) }
            }
        }
    }
    fun decodeBinding(bytes: ByteArray): Binding = with(VaultMediaWireIO) {
        require(bytes.size in 1..1024) { "media-journal-binding-size" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "media-journal-binding-magic" }
        val version = input.readInt(); require(version in 1..2) { "media-journal-binding-version" }
        val scalar = readScalarBinding(input)
        validateScalar(scalar)
        var batchId: String? = null; var digest: String? = null; var index: Int? = null
        if (version == 2 && input.bool()) {
            batchId = input.text(32); digest = input.digest(); index = input.readInt()
            require(ID.matches(batchId) && index in 0 until VaultCopyBatchLimits.MAX_ITEMS &&
                scalar.operation.kind == VaultMediaKind.COPY_OUT && ID.matches(scalar.operation.operationId)) { "media-journal-binding-batch" }
        }
        require(input.available() == 0) { "media-journal-binding-trailer" }
        // Primitive readers are canonical (strict UTF-8/boolean/length), maps reject unknown codes.
        Binding(scalar.operation, scalar.expectedCatalogRevision, scalar.source, scalar.objectIdentity, batchId, digest, index)
    }
    private fun DataOutputStream.writeScalarBinding(value: VaultMediaJournal) = with(VaultMediaWireIO) {
        // Keep the exact historical order: ID, sequence, kind, revision, source?, identity?.
        text(value.operation.operationId, 64); writeLong(value.operation.sequence); writeInt(kindCode(value.operation.kind))
        writeLong(value.expectedCatalogRevision)
        writeBoolean(value.source != null); value.source?.let { source(it) }
        writeBoolean(value.objectIdentity != null); value.objectIdentity?.let { text(it.objectId, 64); writeLong(it.generation) }
    }
    private fun readScalarBinding(input: DataInputStream): VaultMediaJournal = with(VaultMediaWireIO) {
        VaultMediaJournal(VaultMediaOperation(input.text(64), input.readLong(), kind(input.readInt())),
            VaultMediaPhase.PREPARED, input.readLong(), if (input.bool()) input.source() else null,
            if (input.bool()) VaultMediaObjectIdentity(input.text(64), input.readLong()) else null)
    }
    private fun DataOutputStream.target(value: VaultMediaTargetRecord, bounded: Boolean) = with(VaultMediaWireIO) {
        text(value.operationId, if (bounded) 32 else 64); text(value.grantId, if (bounded) 32 else 128)
        text(value.nonce, if (bounded) 32 else 64); writeBoolean(value.uri != null)
        value.uri?.let { text(it, if (bounded) VaultCopyBatchLimits.MAX_URI_UTF8_BYTES else 8192) }; writeInt(value.flags)
    }
    private fun DataInputStream.target(bounded: Boolean): VaultMediaTargetRecord = with(VaultMediaWireIO) {
        VaultMediaTargetRecord(text(if (bounded) 32 else 64), text(if (bounded) 32 else 128), text(if (bounded) 32 else 64),
            if (bool()) text(if (bounded) VaultCopyBatchLimits.MAX_URI_UTF8_BYTES else 8192) else null, readInt())
    }
    private fun DataOutputStream.batch(value: VaultCopyBatchState) = with(VaultMediaWireIO) {
        text(value.batchId, 32); writeLong(value.selectedCatalogRevision); writeInt(value.profile)
        writeInt(value.items.size); writeInt(value.activeIndex); writeInt(value.runCount)
        writeInt(stopCode(value.stop)); writeInt(statusCode(value.status)); blob(digestBytes(value.planDigest), 32)
        writeBoolean(value.tree != null)
        value.tree?.let { text(it.batchId, 32); text(it.grantId, 32); text(it.nonce, 32); text(it.uri, 1024); writeInt(it.flags) }
        value.items.forEach {
            text(it.identity.objectId, 64); writeLong(it.identity.generation); text(it.operationId, 32)
            writeInt(outcomeCode(it.outcome)); writeInt(failureCode(it.failure))
            writeBoolean(it.activatedSequence != null); it.activatedSequence?.let { writeLong(it) }
            writeBoolean(it.plaintextEvidence != null); it.plaintextEvidence?.let { evidence(it) }
            writeBoolean(it.target != null); it.target?.let { target(it, true) }
        }
    }
    private fun DataInputStream.batch(): VaultCopyBatchState = with(VaultMediaWireIO) {
        val id = text(32); val revision = readLong(); val profile = readInt(); val count = readInt()
        require(count in 1..VaultCopyBatchLimits.MAX_ITEMS) { "media-batch-count" }
        val index = readInt(); val runs = readInt(); val stop = stop(readInt()); val status = status(readInt()); val digest = digest()
        val tree = if (bool()) VaultPhoneTreeRecord(text(32), text(32), text(32), text(1024), readInt()) else null
        val items = ArrayList<VaultCopyBatchItem>(count)
        repeat(count) {
            val identity = VaultMediaObjectIdentity(text(64), readLong()); val operation = text(32)
            val outcome = outcome(readInt()); val failure = failure(readInt())
            val sequence = if (bool()) readLong() else null
            val evidence = if (bool()) evidence() else null
            items += VaultCopyBatchItem(operation, identity, sequence, outcome, failure, evidence, if (bool()) target(true) else null)
        }
        VaultCopyBatchState(id, revision, profile, digest, items, tree, index, runs, stop, status)
    }
    private fun DataInputStream.digest(): String = with(VaultMediaWireIO) {
        val bytes = blob(32); require(bytes.size == 32) { "media-plan-digest" }
        try { hex(bytes) } finally { bytes.fill(0) }
    }
    private fun digestBytes(value: String): ByteArray {
        require(DIGEST.matches(value)) { "media-plan-digest" }
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Immutable plan digest, derived locally; it is not a caller assertion of authority. */
    fun planDigest(batchId: String, selectedCatalogRevision: Long, profile: Int, items: List<VaultCopyBatchItem>): String {
        require(ID.matches(batchId) && selectedCatalogRevision in 1 until Long.MAX_VALUE && profile == VaultCopyBatchLimits.PROFILE &&
            items.size in 1..VaultCopyBatchLimits.MAX_ITEMS) { "media-plan-profile" }
        require(items.map { it.operationId }.distinct().size == items.size && items.map { it.identity.objectId }.distinct().size == items.size) { "media-plan-duplicate" }
        val bytes = with(VaultMediaWireIO) { encode(2048) {
            writeInt(0x45534250); writeInt(1); text(batchId, 32); writeLong(selectedCatalogRevision); writeInt(profile); writeInt(items.size)
            items.forEach { require(ID.matches(it.operationId) && it.identity.generation in 1 until Long.MAX_VALUE) { "media-plan-item" }
                text(it.operationId, 32); text(it.identity.objectId, 64); writeLong(it.identity.generation) }
        } }
        return try { hex(MessageDigest.getInstance("SHA-256").digest(bytes)) } finally { bytes.fill(0) }
    }
    fun validate(value: VaultMediaJournal) {
        validateScalar(value)
        value.batch?.let { validateBatch(value, it) }
    }
    private fun validateScalar(value: VaultMediaJournal) {
        require(value.operation.sequence in 1 until Long.MAX_VALUE && value.expectedCatalogRevision in 1 until Long.MAX_VALUE) { "media-journal-profile" }
        if (value.phase == VaultMediaPhase.CANCELLED) require(value.cancelRequested) { "media-journal-cancel" }
        if (value.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET))
            require(value.target == null) { "media-journal-early-target" }
        val common = setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED,
            VaultMediaPhase.CLEANUP_PENDING, VaultMediaPhase.RECONCILIATION_REQUIRED)
        when (value.operation.kind) {
            VaultMediaKind.MOVE_IN -> {
                require(value.source != null && value.objectIdentity != null && value.target == null) { "media-journal-source" }
                require(value.phase in common + setOf(VaultMediaPhase.SOURCE_RESERVED, VaultMediaPhase.ENCRYPTING,
                    VaultMediaPhase.VERIFIED, VaultMediaPhase.COMMITTED_SOURCE_PENDING)) { "media-move-phase" }
                if (value.phase in setOf(VaultMediaPhase.SOURCE_RESERVED, VaultMediaPhase.ENCRYPTING, VaultMediaPhase.VERIFIED,
                        VaultMediaPhase.COMMITTED_SOURCE_PENDING, VaultMediaPhase.COMPLETE))
                    require(value.plaintextEvidence != null) { "media-move-plaintext-evidence" }
                if (value.phase in setOf(VaultMediaPhase.VERIFIED, VaultMediaPhase.COMMITTED_SOURCE_PENDING, VaultMediaPhase.COMPLETE))
                    require(value.ciphertextEvidence != null) { "media-move-ciphertext-evidence" }
            }
            VaultMediaKind.COPY_OUT -> {
                require(value.source == null && value.objectIdentity != null) { "media-journal-object" }
                require(value.phase in common + setOf(VaultMediaPhase.PICKING_TARGET, VaultMediaPhase.TARGET_BOUND,
                    VaultMediaPhase.SOURCE_VALIDATED, VaultMediaPhase.COPYING, VaultMediaPhase.VERIFYING,
                    VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.CHILD_CREATE_INTENT, VaultMediaPhase.OUTPUT_RETAINED)) { "media-copy-phase" }
                if (value.phase in setOf(VaultMediaPhase.TARGET_BOUND, VaultMediaPhase.SOURCE_VALIDATED,
                        VaultMediaPhase.COPYING, VaultMediaPhase.VERIFYING, VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE))
                    require(value.target != null) { "media-copy-target" }
                if (value.phase in setOf(VaultMediaPhase.SOURCE_VALIDATED, VaultMediaPhase.COPYING,
                        VaultMediaPhase.VERIFYING, VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE))
                    require(value.plaintextEvidence != null) { "media-copy-plaintext-evidence" }
            }
        }
        if (value.phase == VaultMediaPhase.CHILD_CREATE_INTENT) require(value.batch != null && value.target != null && value.target.uri == null && !value.cancelRequested) { "media-create-intent" }
        if (value.phase == VaultMediaPhase.OUTPUT_RETAINED) require(value.batch == null && value.operation.kind == VaultMediaKind.COPY_OUT && value.target != null && value.cancelRequested && value.ciphertextEvidence == null) { "media-retained-single" }
        value.objectIdentity?.let { require(it.generation < Long.MAX_VALUE) { "media-journal-generation" } }
        value.target?.let {
            require(it.operationId == value.operation.operationId && value.operation.kind == VaultMediaKind.COPY_OUT) { "media-journal-target" }
            require(it.grantId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it.nonce.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "media-journal-target" }
        }
    }


    private fun boundedUri(value: String) {
        require(value.length in 1..VaultCopyBatchLimits.MAX_URI_UTF8_BYTES) { "media-batch-uri" }
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(bytes.remaining() in 1..VaultCopyBatchLimits.MAX_URI_UTF8_BYTES) { "media-batch-uri" }
    }
    private fun batchTarget(value: VaultMediaTargetRecord, operationId: String) {
        require(value.operationId == operationId && listOf(value.operationId, value.grantId, value.nonce).all(ID::matches) && value.flags == 3) { "media-batch-target" }
        value.uri?.let(::boundedUri)
    }
    private fun validateBatch(value: VaultMediaJournal, batch: VaultCopyBatchState) {
        require(value.operation.kind == VaultMediaKind.COPY_OUT && value.ciphertextEvidence == null && value.phase != VaultMediaPhase.OUTPUT_RETAINED) { "media-batch-kind" }
        require(batch.selectedCatalogRevision <= value.expectedCatalogRevision && batch.planDigest ==
            planDigest(batch.batchId, batch.selectedCatalogRevision, batch.profile, batch.items)) { "media-batch-plan" }
        require(batch.activeIndex in batch.items.indices && batch.runCount in 0..VaultCopyBatchLimits.MAX_RUN_STARTS) { "media-batch-cursor" }
        batch.tree?.let { require(it.batchId == batch.batchId && ID.matches(it.grantId) && ID.matches(it.nonce) && it.flags == 3) { "media-batch-tree" }; boundedUri(it.uri) }
        value.target?.let { batchTarget(it, value.operation.operationId) }
        val active = batch.items[batch.activeIndex]
        require(active.operationId == value.operation.operationId && active.identity == value.objectIdentity && active.activatedSequence == value.operation.sequence) { "media-batch-active" }
        var lastSequence = 0L
        batch.items.forEachIndexed { index, row ->
            require(row.identity.generation in 1 until Long.MAX_VALUE) { "media-batch-generation" }
            row.target?.let { batchTarget(it, row.operationId) }
            row.activatedSequence?.let { require(it in 1 until Long.MAX_VALUE && it > lastSequence) { "media-batch-sequence" }; lastSequence = it }
            if (index < batch.activeIndex) require(row.outcome in rowTerminal && row.activatedSequence != null) { "media-batch-archive" }
            if (index > batch.activeIndex) require(row.outcome in setOf(VaultCopyBatchOutcome.WAITING, VaultCopyBatchOutcome.PAUSED_NOT_STARTED,
                VaultCopyBatchOutcome.CANCELLED_BEFORE_START) && row.activatedSequence == null && row.target == null && row.plaintextEvidence == null && row.failure == null) { "media-batch-future" }
            when (row.outcome) {
                VaultCopyBatchOutcome.VERIFIED_COPY -> require(row.activatedSequence != null && row.target?.uri != null && row.plaintextEvidence != null && row.failure == null) { "media-batch-success" }
                VaultCopyBatchOutcome.FAILED_NO_OUTPUT -> require(row.activatedSequence != null && row.target == null && row.failure != null) { "media-batch-no-output" }
                VaultCopyBatchOutcome.FAILED_CLEANED -> require(row.activatedSequence != null && row.target?.uri != null && row.failure != null) { "media-batch-cleaned" }
                VaultCopyBatchOutcome.CANCELLED_CLEANED -> require(row.activatedSequence != null && row.target?.uri != null) { "media-batch-cleaned" }
                VaultCopyBatchOutcome.UNRESOLVED_RETAINED -> require(row.activatedSequence != null && row.target != null) { "media-batch-retained" }
                VaultCopyBatchOutcome.CANCELLED_BEFORE_START -> require(row.target == null && row.plaintextEvidence == null) { "media-batch-unstarted" }
                VaultCopyBatchOutcome.WAITING, VaultCopyBatchOutcome.PAUSED_NOT_STARTED -> require(row.activatedSequence == null && row.target == null && row.plaintextEvidence == null && row.failure == null) { "media-batch-unstarted" }
                VaultCopyBatchOutcome.ACTIVE, VaultCopyBatchOutcome.CLEANUP_REQUIRED, VaultCopyBatchOutcome.FINAL_DECISION_PENDING -> require(index == batch.activeIndex) { "media-batch-double-active" }
            }
        }
        val expectedOutcome = when (value.phase) {
            VaultMediaPhase.COMPLETE -> setOf(VaultCopyBatchOutcome.VERIFIED_COPY)
            VaultMediaPhase.CANCELLED -> rowTerminal - VaultCopyBatchOutcome.VERIFIED_COPY
            VaultMediaPhase.PUBLISH_INTENT -> setOf(VaultCopyBatchOutcome.FINAL_DECISION_PENDING)
            VaultMediaPhase.CLEANUP_PENDING, VaultMediaPhase.RECONCILIATION_REQUIRED -> setOf(VaultCopyBatchOutcome.CLEANUP_REQUIRED)
            else -> setOf(VaultCopyBatchOutcome.ACTIVE)
        }
        require(active.outcome in expectedOutcome) { "media-batch-result-phase" }
        if (value.phase in terminal || active.outcome in setOf(VaultCopyBatchOutcome.FINAL_DECISION_PENDING, VaultCopyBatchOutcome.CLEANUP_REQUIRED))
            require(active.target == value.target && active.plaintextEvidence == value.plaintextEvidence) { "media-batch-result-evidence" }
        else require((active.target == null || active.target == value.target) && (active.plaintextEvidence == null || active.plaintextEvidence == value.plaintextEvidence)) { "media-batch-active-evidence" }
        if (value.phase == VaultMediaPhase.CHILD_CREATE_INTENT || value.target != null) require(batch.tree != null && batch.runCount > 0) { "media-batch-create-run" }
        if (value.phase in setOf(VaultMediaPhase.TARGET_BOUND, VaultMediaPhase.SOURCE_VALIDATED, VaultMediaPhase.COPYING, VaultMediaPhase.VERIFYING, VaultMediaPhase.PUBLISH_INTENT, VaultMediaPhase.COMPLETE))
            require(value.target?.uri != null) { "media-batch-bound-uri" }
        val archivedTargets = batch.items.filterIndexed { index, _ -> index != batch.activeIndex }.mapNotNull { it.target }
        value.target?.let { current -> require(archivedTargets.none { old -> old.grantId == current.grantId || old.nonce == current.nonce ||
            current.uri != null && old.uri == current.uri }) { "media-batch-target-reuse" } }
        require(batch.items.mapNotNull { it.target?.grantId }.distinct().size == batch.items.mapNotNull { it.target?.grantId }.size &&
            batch.items.mapNotNull { it.target?.nonce }.distinct().size == batch.items.mapNotNull { it.target?.nonce }.size) { "media-batch-duplicate-owner" }
        require(batch.items.mapNotNull { it.target?.uri }.distinct().size == batch.items.mapNotNull { it.target?.uri }.size) { "media-batch-duplicate-target" }
        if (batch.status == VaultCopyBatchStatus.PAUSED) require(batch.stop != VaultCopyBatchStop.NONE) { "media-batch-pause" }
        if (active.outcome == VaultCopyBatchOutcome.ACTIVE) require(!value.cancelRequested && active.failure == null) { "media-batch-active-cancel" }
        if (batch.status == VaultCopyBatchStatus.RUNNING) require(batch.tree != null && batch.runCount > 0 && batch.stop == VaultCopyBatchStop.NONE) { "media-batch-run" }
        if (batch.status == VaultCopyBatchStatus.AWAITING_CONFIRMATION) require(batch.tree != null && batch.runCount == 0 && batch.activeIndex == 0 && value.phase == VaultMediaPhase.PICKING_TARGET) { "media-batch-confirm" }
        if (batch.status == VaultCopyBatchStatus.PREPARING) require(batch.tree == null && batch.runCount == 0 && batch.activeIndex == 0 && value.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET)) { "media-batch-preparing" }
        if (batch.status in closedBatch) {
            require(value.phase in terminal && batch.items.all { it.outcome in rowTerminal || it.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED }) { "media-batch-closed" }
            if (batch.status == VaultCopyBatchStatus.FINISHED) require(batch.items.none { it.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED }) { "media-batch-finished" }
            else require(batch.runCount == VaultCopyBatchLimits.MAX_RUN_STARTS && batch.stop == VaultCopyBatchStop.BUDGET_EXHAUSTED) { "media-batch-budget" }
        }
    }

    /** Metadata comparison only; production calls it with the same root's authenticated catalog. */
    fun requireSourcePlan(catalog: VaultAuthenticatedCatalog, journal: VaultMediaJournal) {
        validate(journal)
        val batch = requireNotNull(journal.batch)
        require(catalog.catalogRevision == journal.expectedCatalogRevision) { "media-batch-catalog" }
        batch.items.forEach { row ->
            val entry = catalog.entries.singleOrNull { it.identity == row.identity }
                ?: throw IllegalArgumentException("media-batch-source-changed")
            if (row.plaintextEvidence != null) require(entry.plaintext == row.plaintextEvidence) { "media-batch-source-evidence" }
            if (row.operationId == journal.operation.operationId && journal.plaintextEvidence != null)
                require(entry.plaintext == journal.plaintextEvidence) { "media-batch-active-source-evidence" }
        }
    }

    /** Encodes the maximal future receipt for this exact plan before any picker/create.
     * Public placeholders are only sizing data; neither this method nor its bytes authorize IO. */
    fun requireCapacity(value: VaultMediaJournal) { worstCasePayload(value).fill(0) }
    fun worstCasePayload(value: VaultMediaJournal): ByteArray {
        validate(value)
        val batch = value.batch ?: return encode(value)
        val evidence = ExportEvidence(VaultMediaLimits.MAX_BYTES, "f".repeat(64))
        val rows = batch.items.mapIndexed { index, row -> row.copy(activatedSequence = index + 1L,
            outcome = VaultCopyBatchOutcome.VERIFIED_COPY, failure = null, plaintextEvidence = evidence,
            target = VaultMediaTargetRecord(row.operationId, (100 + index).toString(16).padStart(32, '0'),
                (200 + index).toString(16).padStart(32, '0'), index.toString() + "u".repeat(1023), 3)) }
        val last = rows.last()
        return encode(value.copy(operation = VaultMediaOperation(last.operationId, rows.size.toLong(), VaultMediaKind.COPY_OUT),
            phase = VaultMediaPhase.COMPLETE, source = null, objectIdentity = last.identity, target = last.target, cancelRequested = false,
            plaintextEvidence = evidence, ciphertextEvidence = null, batch = batch.copy(items = rows, tree = VaultPhoneTreeRecord(
                batch.batchId, "e".repeat(32), "d".repeat(32), "t".repeat(1024), 3), activeIndex = rows.lastIndex,
                runCount = VaultCopyBatchLimits.MAX_RUN_STARTS, stop = VaultCopyBatchStop.NONE, status = VaultCopyBatchStatus.FINISHED)))
    }

    fun aad(identity: VaultPersistentIdentity, revision: Long, purpose: Int,
        binding: ByteArray? = null): ByteArray = with(VaultMediaWireIO) {
        VaultCryptoWire.requireIdentity(identity)
        require(revision in 1 until Long.MAX_VALUE && purpose in 1..2 && (binding != null) == (purpose == 2)) { "media-journal-aad" }
        encode(2048) {
            writeInt(0x45534a41); writeInt(1); writeInt(purpose); text(identity.vaultId, 64); writeInt(identity.rootVersion)
            writeLong(revision); if (binding != null) blob(binding, 1024)
        }
    }

    fun requireSuccessor(previous: VaultMediaJournal?, next: VaultMediaJournal) {
        validate(next)
        if (previous != null) validate(previous)
        if (previous?.batch == null && next.batch == null) { requireScalarSuccessor(previous, next); return }
        if (previous == null || previous.batch == null || previous.batch.batchId != next.batch?.batchId) {
            require(previous == null || previous.phase in terminal && (previous.batch == null || previous.batch.status in closedBatch)) { "media-batch-pending" }
            require(next.operation.sequence == (previous?.operation?.sequence ?: 0) + 1 && next.phase == VaultMediaPhase.PREPARED) { "media-batch-initial-sequence" }
            next.batch?.let { batch ->
                require(batch.activeIndex == 0 && batch.tree == null && batch.runCount == 0 && batch.stop == VaultCopyBatchStop.NONE &&
                    batch.status == VaultCopyBatchStatus.PREPARING && !next.cancelRequested && next.target == null && next.plaintextEvidence == null &&
                    batch.items.first().failure == null && batch.items.first().outcome == VaultCopyBatchOutcome.ACTIVE &&
                    batch.items.drop(1).all { it.outcome == VaultCopyBatchOutcome.WAITING }) { "media-batch-initial" }
                requireCapacity(next)
            }
            return
        }
        val old = previous.batch; val batch = requireNotNull(next.batch)
        require(old.batchId == batch.batchId && old.selectedCatalogRevision == batch.selectedCatalogRevision && old.profile == batch.profile &&
            old.planDigest == batch.planDigest && old.items.map { it.operationId to it.identity } == batch.items.map { it.operationId to it.identity }) { "media-batch-plan-substitution" }
        require(old.tree == null || old.tree == batch.tree) { "media-batch-tree-substitution" }
        if (old.tree == null && batch.tree != null) require(previous.phase == VaultMediaPhase.PICKING_TARGET && next.phase == previous.phase &&
            previous.operation == next.operation && old.runCount == 0 && batch.status == VaultCopyBatchStatus.AWAITING_CONFIRMATION) { "media-batch-tree-bind" }
        require(next.expectedCatalogRevision >= previous.expectedCatalogRevision) { "media-journal-revision-regression" }
        if (previous == next) return
        require(old.status !in closedBatch) { "media-batch-terminal" }
        // Refresh binds identical durable evidence to the freshly authenticated current catalog.
        // It is a new exact-snapshot/context mutation; old proofs cannot be reused by this method.
        if (next.expectedCatalogRevision > previous.expectedCatalogRevision) {
            require(previous.copy(expectedCatalogRevision = next.expectedCatalogRevision) == next) { "media-batch-revision-refresh" }; return
        }
        require(batch.activeIndex in old.activeIndex..old.activeIndex + 1 && batch.runCount in old.runCount..old.runCount + 1) { "media-batch-monotonic" }
        val start = batch.runCount == old.runCount + 1
        if (start) require(batch.activeIndex == old.activeIndex && next.operation == previous.operation && next.phase == previous.phase &&
            old.status in setOf(VaultCopyBatchStatus.AWAITING_CONFIRMATION, VaultCopyBatchStatus.PAUSED) &&
            (previous.phase in terminal || previous.target == null && previous.phase in setOf(VaultMediaPhase.PREPARED, VaultMediaPhase.PICKING_TARGET)) &&
            old.stop != VaultCopyBatchStop.USER_CANCEL && batch.status == VaultCopyBatchStatus.RUNNING && batch.stop == VaultCopyBatchStop.NONE &&
            scalarEqual(previous, next)) { "media-batch-run-reservation" }
        if (batch.status == VaultCopyBatchStatus.RUNNING && old.status != VaultCopyBatchStatus.RUNNING) require(start) { "media-batch-run-charge" }
        if (old.stop == VaultCopyBatchStop.USER_CANCEL) require(batch.stop == old.stop) { "media-batch-user-stop" }
        if (old.stop != VaultCopyBatchStop.NONE && batch.stop == VaultCopyBatchStop.NONE) require(start) { "media-batch-stop-clear" }
        old.items.indices.forEach { index ->
            val a = old.items[index]; val b = batch.items[index]
            if (index < old.activeIndex || a.outcome in rowTerminal) require(a == b) { "media-batch-result-immutable" }
            if (index > old.activeIndex && !(batch.activeIndex == index && index == old.activeIndex + 1)) {
                val allowed = a == b || a.copy(outcome = VaultCopyBatchOutcome.PAUSED_NOT_STARTED) == b && batch.stop in setOf(
                    VaultCopyBatchStop.LIFECYCLE_PAUSE, VaultCopyBatchStop.TARGET_PAUSE, VaultCopyBatchStop.BUDGET_EXHAUSTED) ||
                    a.copy(outcome = VaultCopyBatchOutcome.CANCELLED_BEFORE_START) == b && batch.stop == VaultCopyBatchStop.USER_CANCEL ||
                    a.outcome == VaultCopyBatchOutcome.PAUSED_NOT_STARTED && b == a.copy(outcome = VaultCopyBatchOutcome.WAITING) && start
                require(allowed && a.outcome !in rowTerminal || a == b) { "media-batch-future-mutation" }
            }
        }
        if (batch.activeIndex != old.activeIndex) {
            require(!start && old.stop == VaultCopyBatchStop.NONE && batch.stop == VaultCopyBatchStop.NONE &&
                old.status == VaultCopyBatchStatus.RUNNING && batch.status == VaultCopyBatchStatus.RUNNING && previous.phase in terminal &&
                old.items[old.activeIndex].outcome in rowTerminal && next.phase == VaultMediaPhase.PREPARED &&
                next.operation.sequence == previous.operation.sequence + 1 && next.operation.operationId != previous.operation.operationId &&
                next.target == null && next.plaintextEvidence == null && !next.cancelRequested) { "media-batch-handover" }
            val waiting = old.items[batch.activeIndex]
            require(waiting.outcome == VaultCopyBatchOutcome.WAITING && batch.items[batch.activeIndex] == waiting.copy(
                activatedSequence = next.operation.sequence, outcome = VaultCopyBatchOutcome.ACTIVE)) { "media-batch-handover-row" }
            return
        }
        require(previous.operation == next.operation && previous.source == next.source && previous.objectIdentity == next.objectIdentity &&
            (previous.plaintextEvidence == null || previous.plaintextEvidence == next.plaintextEvidence) &&
            previous.ciphertextEvidence == next.ciphertextEvidence && (!previous.cancelRequested || next.cancelRequested)) { "media-batch-scalar-substitution" }
        val bind = previous.phase == VaultMediaPhase.CHILD_CREATE_INTENT && next.phase == VaultMediaPhase.TARGET_BOUND &&
            previous.target != null && previous.target.uri == null && next.target?.uri != null && previous.target.copy(uri = next.target.uri) == next.target
        require(previous.target == null || previous.target == next.target || bind) { "media-batch-target-substitution" }
        if (previous.target == null && next.target != null) require(next.phase == VaultMediaPhase.CHILD_CREATE_INTENT) { "media-batch-first-reservation" }
        if (previous.phase == next.phase) {
            require(scalarEqual(previous, next)) { "media-batch-same-phase" }
            require(old.items[old.activeIndex] == batch.items[batch.activeIndex]) { "media-batch-same-result" }
            return
        }
        require(previous.phase !in terminal) { "media-batch-terminal-phase" }
        if (previous.phase == VaultMediaPhase.PUBLISH_INTENT) {
            require(next.phase == VaultMediaPhase.COMPLETE && !next.cancelRequested) { "media-batch-final-decision" }; return
        }
        val cancel = next.phase == VaultMediaPhase.CANCELLED && next.cancelRequested
        val failure = next.phase in setOf(VaultMediaPhase.CLEANUP_PENDING, VaultMediaPhase.RECONCILIATION_REQUIRED)
        val legal = when (previous.phase) {
            VaultMediaPhase.PREPARED -> setOf(VaultMediaPhase.PICKING_TARGET, VaultMediaPhase.CHILD_CREATE_INTENT)
            VaultMediaPhase.PICKING_TARGET -> setOf(VaultMediaPhase.CHILD_CREATE_INTENT)
            VaultMediaPhase.CHILD_CREATE_INTENT -> setOf(VaultMediaPhase.TARGET_BOUND)
            VaultMediaPhase.TARGET_BOUND -> setOf(VaultMediaPhase.SOURCE_VALIDATED)
            VaultMediaPhase.SOURCE_VALIDATED -> setOf(VaultMediaPhase.COPYING)
            VaultMediaPhase.COPYING -> setOf(VaultMediaPhase.VERIFYING)
            VaultMediaPhase.VERIFYING -> setOf(VaultMediaPhase.PUBLISH_INTENT)
            else -> emptySet()
        }
        require(cancel || failure || next.phase in legal) { "media-batch-transition" }
        if (!cancel && !failure) require(batch.stop == VaultCopyBatchStop.NONE && batch.status == VaultCopyBatchStatus.RUNNING ||
            next.phase == VaultMediaPhase.PICKING_TARGET && batch.status == VaultCopyBatchStatus.PREPARING) { "media-batch-stopped" }
        if (next.phase == VaultMediaPhase.PICKING_TARGET) require(old.activeIndex == 0 && old.tree == null && old.runCount == 0 &&
            batch.status == VaultCopyBatchStatus.PREPARING) { "media-batch-single-picker" }
        if (next.phase == VaultMediaPhase.CHILD_CREATE_INTENT) require(previous.target == null && batch.tree != null && batch.runCount > 0 && !next.cancelRequested) { "media-batch-create-once" }
    }
    private fun scalarEqual(a: VaultMediaJournal, b: VaultMediaJournal) = a.copy(batch = null) == b.copy(batch = null)

    private fun requireScalarSuccessor(previous: VaultMediaJournal?, next: VaultMediaJournal) {
        validate(next)
        if (previous == null) {
            require(next.operation.sequence == 1L && next.phase == VaultMediaPhase.PREPARED) { "media-journal-initial" }; return
        }
        if (previous.operation != next.operation) {
            require(previous.phase in setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED) &&
                next.operation.sequence == previous.operation.sequence + 1 && next.phase == VaultMediaPhase.PREPARED) { "media-journal-sequence" }
            return
        }
        require(previous.source == next.source && previous.objectIdentity == next.objectIdentity &&
            (previous.target == null || previous.target == next.target) &&
            (previous.plaintextEvidence == null || previous.plaintextEvidence == next.plaintextEvidence) &&
            (previous.ciphertextEvidence == null || previous.ciphertextEvidence == next.ciphertextEvidence) &&
            (!previous.cancelRequested || next.cancelRequested)) { "media-journal-substitution" }
        require(next.expectedCatalogRevision >= previous.expectedCatalogRevision) { "media-journal-revision-regression" }
        if (previous == next) return
        // Credential reseal preserves pending journal ciphertext. Explicit COPY_OUT recovery
        // may bind that identical nonterminal phase to the now-current catalog revision using
        // a fresh exact-snapshot/root-bound mutation, never an old proof or a new decision.
        if (previous.operation.kind == VaultMediaKind.COPY_OUT &&
            previous.phase !in setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED) &&
            next.expectedCatalogRevision > previous.expectedCatalogRevision &&
            previous.copy(expectedCatalogRevision = next.expectedCatalogRevision) == next) return
        require(previous.phase !in setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED)) { "media-journal-terminal" }
        if (previous.phase == VaultMediaPhase.PUBLISH_INTENT) {
            require(next.phase == VaultMediaPhase.COMPLETE && !next.cancelRequested && next.expectedCatalogRevision == previous.expectedCatalogRevision) { "media-final-decision" }; return
        }
        if (next.phase == VaultMediaPhase.OUTPUT_RETAINED) {
            require(previous.operation.kind == VaultMediaKind.COPY_OUT && previous.target != null &&
                previous.phase in setOf(VaultMediaPhase.TARGET_BOUND, VaultMediaPhase.SOURCE_VALIDATED, VaultMediaPhase.COPYING,
                    VaultMediaPhase.VERIFYING, VaultMediaPhase.CLEANUP_PENDING, VaultMediaPhase.RECONCILIATION_REQUIRED) &&
                next.expectedCatalogRevision == previous.expectedCatalogRevision) { "media-retained-transition" }; return
        }
        val failure = next.phase in setOf(VaultMediaPhase.CLEANUP_PENDING, VaultMediaPhase.RECONCILIATION_REQUIRED)
        val cancel = next.phase == VaultMediaPhase.CANCELLED && next.cancelRequested &&
            previous.phase !in setOf(VaultMediaPhase.COMMITTED_SOURCE_PENDING, VaultMediaPhase.PUBLISH_INTENT)
        val legal = when (previous.phase) {
            VaultMediaPhase.PREPARED -> setOf(VaultMediaPhase.SOURCE_RESERVED, VaultMediaPhase.PICKING_TARGET, VaultMediaPhase.TARGET_BOUND)
            VaultMediaPhase.SOURCE_RESERVED -> setOf(VaultMediaPhase.ENCRYPTING)
            VaultMediaPhase.ENCRYPTING -> setOf(VaultMediaPhase.VERIFIED)
            VaultMediaPhase.VERIFIED -> setOf(VaultMediaPhase.COMMITTED_SOURCE_PENDING)
            VaultMediaPhase.COMMITTED_SOURCE_PENDING -> setOf(VaultMediaPhase.COMPLETE)
            VaultMediaPhase.PICKING_TARGET -> setOf(VaultMediaPhase.TARGET_BOUND)
            VaultMediaPhase.TARGET_BOUND -> setOf(VaultMediaPhase.SOURCE_VALIDATED)
            VaultMediaPhase.SOURCE_VALIDATED -> setOf(VaultMediaPhase.COPYING)
            VaultMediaPhase.COPYING -> setOf(VaultMediaPhase.VERIFYING)
            VaultMediaPhase.VERIFYING -> setOf(VaultMediaPhase.PUBLISH_INTENT)
            VaultMediaPhase.PUBLISH_INTENT -> setOf(VaultMediaPhase.COMPLETE)
            VaultMediaPhase.CLEANUP_PENDING, VaultMediaPhase.RECONCILIATION_REQUIRED -> setOf(VaultMediaPhase.COMPLETE, VaultMediaPhase.CANCELLED, VaultMediaPhase.OUTPUT_RETAINED)
            else -> emptySet()
        }
        require(failure || cancel || next.phase in legal) { "media-journal-transition" }
    }

    // Stable explicit numbers: do not encode or accept a future enum merely by its ordinal.
    private fun kindCode(value: VaultMediaKind) = when (value) { VaultMediaKind.MOVE_IN -> 0; VaultMediaKind.COPY_OUT -> 1 }
    private fun kind(value: Int) = when (value) { 0 -> VaultMediaKind.MOVE_IN; 1 -> VaultMediaKind.COPY_OUT; else -> badCode() }
    private fun phaseCode(value: VaultMediaPhase): Int = when (value) {
        VaultMediaPhase.PREPARED -> 0; VaultMediaPhase.SOURCE_RESERVED -> 1; VaultMediaPhase.ENCRYPTING -> 2
        VaultMediaPhase.VERIFIED -> 3; VaultMediaPhase.COMMITTED_SOURCE_PENDING -> 4; VaultMediaPhase.PICKING_TARGET -> 5
        VaultMediaPhase.TARGET_BOUND -> 6; VaultMediaPhase.SOURCE_VALIDATED -> 7; VaultMediaPhase.COPYING -> 8
        VaultMediaPhase.VERIFYING -> 9; VaultMediaPhase.PUBLISH_INTENT -> 10; VaultMediaPhase.COMPLETE -> 11
        VaultMediaPhase.CANCELLED -> 12; VaultMediaPhase.CLEANUP_PENDING -> 13; VaultMediaPhase.RECONCILIATION_REQUIRED -> 14
        VaultMediaPhase.CHILD_CREATE_INTENT -> 15; VaultMediaPhase.OUTPUT_RETAINED -> 16
    }
    private fun phase(value: Int) = when (value) {
        0 -> VaultMediaPhase.PREPARED; 1 -> VaultMediaPhase.SOURCE_RESERVED; 2 -> VaultMediaPhase.ENCRYPTING
        3 -> VaultMediaPhase.VERIFIED; 4 -> VaultMediaPhase.COMMITTED_SOURCE_PENDING; 5 -> VaultMediaPhase.PICKING_TARGET
        6 -> VaultMediaPhase.TARGET_BOUND; 7 -> VaultMediaPhase.SOURCE_VALIDATED; 8 -> VaultMediaPhase.COPYING
        9 -> VaultMediaPhase.VERIFYING; 10 -> VaultMediaPhase.PUBLISH_INTENT; 11 -> VaultMediaPhase.COMPLETE
        12 -> VaultMediaPhase.CANCELLED; 13 -> VaultMediaPhase.CLEANUP_PENDING; 14 -> VaultMediaPhase.RECONCILIATION_REQUIRED
        15 -> VaultMediaPhase.CHILD_CREATE_INTENT; 16 -> VaultMediaPhase.OUTPUT_RETAINED; else -> badCode()
    }
    private fun outcomeCode(value: VaultCopyBatchOutcome): Int = when (value) {
        VaultCopyBatchOutcome.WAITING -> 0; VaultCopyBatchOutcome.ACTIVE -> 1; VaultCopyBatchOutcome.VERIFIED_COPY -> 2
        VaultCopyBatchOutcome.FAILED_NO_OUTPUT -> 3; VaultCopyBatchOutcome.FAILED_CLEANED -> 4; VaultCopyBatchOutcome.CANCELLED_BEFORE_START -> 5
        VaultCopyBatchOutcome.CANCELLED_CLEANED -> 6; VaultCopyBatchOutcome.PAUSED_NOT_STARTED -> 7; VaultCopyBatchOutcome.CLEANUP_REQUIRED -> 8
        VaultCopyBatchOutcome.UNRESOLVED_RETAINED -> 9; VaultCopyBatchOutcome.FINAL_DECISION_PENDING -> 10
    }
    private fun outcome(value: Int) = when (value) {
        0 -> VaultCopyBatchOutcome.WAITING; 1 -> VaultCopyBatchOutcome.ACTIVE; 2 -> VaultCopyBatchOutcome.VERIFIED_COPY
        3 -> VaultCopyBatchOutcome.FAILED_NO_OUTPUT; 4 -> VaultCopyBatchOutcome.FAILED_CLEANED; 5 -> VaultCopyBatchOutcome.CANCELLED_BEFORE_START
        6 -> VaultCopyBatchOutcome.CANCELLED_CLEANED; 7 -> VaultCopyBatchOutcome.PAUSED_NOT_STARTED; 8 -> VaultCopyBatchOutcome.CLEANUP_REQUIRED
        9 -> VaultCopyBatchOutcome.UNRESOLVED_RETAINED; 10 -> VaultCopyBatchOutcome.FINAL_DECISION_PENDING; else -> badCode()
    }
    private fun stopCode(value: VaultCopyBatchStop) = when (value) { VaultCopyBatchStop.NONE -> 0; VaultCopyBatchStop.USER_CANCEL -> 1
        VaultCopyBatchStop.LIFECYCLE_PAUSE -> 2; VaultCopyBatchStop.TARGET_PAUSE -> 3; VaultCopyBatchStop.BUDGET_EXHAUSTED -> 4 }
    private fun stop(value: Int) = when (value) { 0 -> VaultCopyBatchStop.NONE; 1 -> VaultCopyBatchStop.USER_CANCEL
        2 -> VaultCopyBatchStop.LIFECYCLE_PAUSE; 3 -> VaultCopyBatchStop.TARGET_PAUSE; 4 -> VaultCopyBatchStop.BUDGET_EXHAUSTED; else -> badCode() }
    private fun statusCode(value: VaultCopyBatchStatus) = when (value) { VaultCopyBatchStatus.PREPARING -> 0; VaultCopyBatchStatus.AWAITING_CONFIRMATION -> 1
        VaultCopyBatchStatus.RUNNING -> 2; VaultCopyBatchStatus.PAUSED -> 3; VaultCopyBatchStatus.FINISHED -> 4; VaultCopyBatchStatus.BUDGET_EXHAUSTED -> 5 }
    private fun status(value: Int) = when (value) { 0 -> VaultCopyBatchStatus.PREPARING; 1 -> VaultCopyBatchStatus.AWAITING_CONFIRMATION
        2 -> VaultCopyBatchStatus.RUNNING; 3 -> VaultCopyBatchStatus.PAUSED; 4 -> VaultCopyBatchStatus.FINISHED; 5 -> VaultCopyBatchStatus.BUDGET_EXHAUSTED; else -> badCode() }
    private fun failureCode(value: VaultFailure?) = when (value) { null -> -1; VaultFailure.CANCELLED -> 0; VaultFailure.DEADLINE -> 1
        VaultFailure.REJECTED -> 2; VaultFailure.UNAVAILABLE -> 3; VaultFailure.CORRUPT -> 4; VaultFailure.CONFLICT -> 5
        VaultFailure.INCOMPLETE -> 6; VaultFailure.UNSUPPORTED -> 7; VaultFailure.RELEASE_UNKNOWN -> 8
        // Detailed primary diagnostics are process-local. Reuse only the established coarse wire codes.
        VaultFailure.DEVICE_KEY_UNAVAILABLE, VaultFailure.STORE_UNREADABLE -> 3
        VaultFailure.INSTALLATION_MISMATCH_OR_CORRUPT, VaultFailure.RETRY_LEDGER_UNAVAILABLE_OR_CORRUPT -> 4 }
    private fun failure(value: Int) = when (value) { -1 -> null; 0 -> VaultFailure.CANCELLED; 1 -> VaultFailure.DEADLINE
        2 -> VaultFailure.REJECTED; 3 -> VaultFailure.UNAVAILABLE; 4 -> VaultFailure.CORRUPT; 5 -> VaultFailure.CONFLICT
        6 -> VaultFailure.INCOMPLETE; 7 -> VaultFailure.UNSUPPORTED; 8 -> VaultFailure.RELEASE_UNKNOWN; else -> badCode() }
    private fun badCode(): Nothing = throw IllegalArgumentException("media-journal-code")
}
