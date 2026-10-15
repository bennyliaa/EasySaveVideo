package com.easysavevideo.app.save.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.easysavevideo.app.library.storage.LibrarySchema
import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*

/**
 * Same-database SQL adapter for the storage owner's NormalizedCommitProtocol. No files, native
 * calls, generic CAS or secondary database are used here. The owner keeps this adapter private;
 * execution/reservation/publication-handle authority is checked by that protocol before entry.
 * Every mutation compares the complete Save/workspace/catalog/index/publication snapshot and
 * commits all of its effects together. A caller must not wrap these methods in another transaction.
 */
internal class AndroidNormalizedCommitJournal(
    private val database: () -> SQLiteDatabase,
    private val ownerLock: Any,
    private val closed: () -> Boolean,
    private val bindVerifiedPublication: (SQLiteDatabase, SaveRecord) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NormalizedCommitJournal {
    override fun read(owner: NormalizationOwner): NormalizedCommitState? = guard {
        transaction { db -> readSnapshot(db, owner) }
    }

    override fun writeIntent(expected: NormalizedCommitState, intent: OwnedMedia): Boolean = guard {
        if (!active(expected) || expected.owned.state != OwnedState.STAGING ||
            intent != expected.owned.copy(state = OwnedState.INTENT, evidence = expected.record.verification)) return@guard false
        val evidence = expected.record.verification ?: return@guard false
        mutate(expected, expected.copy(owned = intent)) { db ->
            changed(db.update("save_owned", ContentValues().apply {
                put("state", OwnedState.INTENT.name); put("evidence", SaveRecordCodec.evidenceBytes(evidence))
            }, "commit_id=? AND task_id=? AND artifact_id=? AND media_id=? AND state='STAGING' AND evidence IS NULL", ownedArgs(expected)))
        }
    }

    override fun writeIndex(expected: NormalizedCommitState): Boolean = guard {
        if (!active(expected) || expected.owned.state != OwnedState.INTENT || expected.indexed != null) return@guard false
        val receipt = expected.owned.receipt()
        val next = expected.copy(owned = expected.owned.copy(state = OwnedState.INDEXED), indexed = receipt)
        mutate(expected, next) { db ->
            db.insertOrThrow("save_media", null, ContentValues().apply {
                put("commit_id", receipt.commitId); put("artifact_id", receipt.artifactId); put("media_id", receipt.mediaId)
                put("size_bytes", receipt.sizeBytes); put("sha256", receipt.sha256); put("visible", 0)
            })
            changed(db.update("save_owned", ContentValues().apply { put("state", OwnedState.INDEXED.name) },
                "commit_id=? AND task_id=? AND artifact_id=? AND media_id=? AND state='INTENT'", ownedArgs(expected)))
        }
    }

    override fun publish(expected: NormalizedCommitState, succeeded: SaveRecord): Boolean = guard {
        if (!active(expected) || expected.owned.state != OwnedState.INDEXED ||
            expected.indexed != expected.owned.receipt() || !successProposal(expected, succeeded)) return@guard false
        mutate(expected, expected.copy(record = succeeded, indexVisible = true, libraryLinked = true)) { db ->
            val receipt = expected.owned.receipt()
            changed(db.update("save_media", ContentValues().apply { put("visible", 1) },
                "commit_id=? AND artifact_id=? AND media_id=? AND size_bytes=? AND sha256=? AND visible=0", receiptArgs(receipt)))
            LibrarySchema.requireVerifiedPublication(db, succeeded)
            LibrarySchema.publish(db, succeeded, nowMillis(), imported = false)
            // Same database and transaction, including any admitted destination source binding.
            bindVerifiedPublication(db, succeeded)
            changed(db.update("save_tasks", recordValues(succeeded),
                "task_id=? AND commit_id=? AND revision=? AND state='RUNNING'",
                arrayOf(expected.record.task.taskId, expected.record.commitId, expected.record.task.revision.toString())))
        }
    }

    override fun planCleanup(expected: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean): Boolean = guard {
        if (!terminal(expected) || !cleanupProposal(expected, pending, discard)) return@guard false
        val next = expected.copy(workspace = pending,
            owned = if (discard) expected.owned.copy(state = OwnedState.DISCARDED) else expected.owned,
            indexed = if (discard) null else expected.indexed)
        mutate(expected, next) { db ->
            updateWorkspace(db, expected.workspace, pending)
            if (discard) {
                // No visible row or Library association may authorize deletion, even a tombstone.
                if (hasLibraryAssociation(db, expected.owned) || mediaVisible(db, expected.owned.artifact.commitId)) reject()
                expected.indexed?.let { receipt ->
                    changed(db.delete("save_media",
                        "commit_id=? AND artifact_id=? AND media_id=? AND size_bytes=? AND sha256=? AND visible=0", receiptArgs(receipt)))
                }
                changed(db.update("save_owned", ContentValues().apply { put("state", OwnedState.DISCARDED.name) },
                    "commit_id=? AND task_id=? AND artifact_id=? AND media_id=? AND state=?",
                    ownedArgs(expected) + expected.owned.state.name))
            }
        }
    }

    override fun finishCleanup(expected: NormalizedCommitState, cleaned: NormalizationWorkspace): Boolean = guard {
        val row = expected.workspace
        if (!terminal(expected) || row.lifecycle != NormalizationLifecycle.CLEANUP_PENDING ||
            row.cleanup?.identities == null || row.revision == Long.MAX_VALUE ||
            cleaned != row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.CLEANED)) return@guard false
        if (expected.record.task.state != TaskState.SUCCEEDED &&
            (expected.owned.state != OwnedState.DISCARDED || expected.indexed != null)) return@guard false
        mutate(expected, expected.copy(workspace = cleaned)) { db -> updateWorkspace(db, row, cleaned) }
    }

    private fun mutate(expected: NormalizedCommitState, next: NormalizedCommitState,
        change: (SQLiteDatabase) -> Unit): Boolean = transaction { db ->
        if (readSnapshot(db, expected.workspace.key.owner) != expected) return@transaction false
        change(db)
        // Reject triggers, malformed projections or callback effects instead of acknowledging a
        // partially corresponding proposal. Throwing here rolls every preceding write back.
        if (readSnapshot(db, expected.workspace.key.owner) != next) reject()
        true
    }

    private fun readSnapshot(db: SQLiteDatabase, owner: NormalizationOwner): NormalizedCommitState? {
        val record = record(db, owner) ?: return null
        val owned = owned(db, owner) ?: return null
        val workspace = workspace(db, owner) ?: return null
        if (record.task.taskId != owner.taskId || record.commitId != owner.commitId ||
            record.artifact != owned.artifact || owned.taskId != owner.taskId ||
            owned.artifact != StagingArtifact(owner.artifactId, owner.commitId) || workspace.key.owner != owner) reject()
        normalizationId(owned.mediaId)
        val index = index(db, owned)
        return NormalizedCommitState(record, workspace, owned, index?.first, index?.second ?: false,
            hasLibraryAssociation(db, owned))
    }

    /** Bound lengths BEFORE asking CursorWindow to materialize any record/evidence blob. */
    private fun record(db: SQLiteDatabase, owner: NormalizationOwner): SaveRecord? {
        val row = db.rawQuery("""SELECT rowid,length(record) FROM save_tasks
            WHERE task_id=? OR commit_id=? LIMIT 2""", arrayOf(owner.taskId, owner.commitId)).use { c ->
            if (!c.moveToFirst()) null else {
                val id = c.getLong(0)
                if (c.isNull(1) || c.getLong(1) !in 8..SaveRecordCodec.MAX_BYTES.toLong() || c.moveToNext()) reject()
                id
            }
        } ?: return null
        return db.rawQuery("""SELECT record,task_id,idempotency_key,revision,state,commit_id FROM save_tasks
            WHERE rowid=? AND length(record) BETWEEN 8 AND 32768 AND length(task_id) BETWEEN 1 AND 128
            AND length(idempotency_key) BETWEEN 1 AND 128 AND length(state) BETWEEN 1 AND 32
            AND length(commit_id) BETWEEN 1 AND 128""", arrayOf(row.toString())).use { c ->
            if (!c.moveToFirst()) reject()
            SaveRecordCodec.decode(c.getBlob(0)).also {
                if (it.task.taskId != c.getString(1) || it.request.idempotencyKey != c.getString(2) ||
                    it.task.revision != c.getLong(3) || it.task.state.name != c.getString(4) ||
                    it.commitId != c.getString(5) || it.task.taskId != owner.taskId || it.commitId != owner.commitId) reject()
            }
        }
    }

    private fun owned(db: SQLiteDatabase, owner: NormalizationOwner): OwnedMedia? {
        val row = db.rawQuery("""SELECT rowid,length(evidence) FROM save_owned
            WHERE task_id=? OR artifact_id=? OR commit_id=? LIMIT 2""",
            arrayOf(owner.taskId, owner.artifactId, owner.commitId)).use { c ->
            if (!c.moveToFirst()) null else {
                val id = c.getLong(0)
                if ((!c.isNull(1) && c.getLong(1) !in 1..4096) || c.moveToNext()) reject()
                id
            }
        } ?: return null
        return db.rawQuery("""SELECT task_id,artifact_id,commit_id,media_id,state,evidence FROM save_owned WHERE rowid=?
            AND length(task_id) BETWEEN 1 AND 128 AND length(artifact_id)=36 AND length(commit_id) BETWEEN 1 AND 128
            AND length(media_id)=36 AND length(state) BETWEEN 1 AND 32 AND (evidence IS NULL OR length(evidence) BETWEEN 1 AND 4096)""",
            arrayOf(row.toString())).use { c ->
            if (!c.moveToFirst()) reject()
            OwnedMedia(c.getString(0), StagingArtifact(c.getString(1), c.getString(2)), c.getString(3),
                OwnedState.valueOf(c.getString(4)), if (c.isNull(5)) null else SaveRecordCodec.evidence(c.getBlob(5)))
        }
    }

    private fun workspace(db: SQLiteDatabase, owner: NormalizationOwner): NormalizationWorkspace? {
        val args = arrayOf(owner.taskId, owner.artifactId, owner.commitId)
        val live = db.rawQuery("""SELECT sequence,length(record) FROM save_normalization_workspace
            WHERE (task_id=? OR artifact_id=? OR commit_id=?) AND lifecycle!='CLEANED' ORDER BY sequence DESC LIMIT 2""", args).use { c ->
            buildList { while (c.moveToNext()) { if (c.getLong(1) !in 8..NormalizationWorkspaceCodec.MAX_BYTES.toLong()) reject(); add(c.getLong(0)) } }
        }
        if (live.size > 1) reject()
        val sequence = live.singleOrNull() ?: db.rawQuery("""SELECT sequence,length(record) FROM save_normalization_workspace
            WHERE task_id=? OR artifact_id=? OR commit_id=? ORDER BY sequence DESC LIMIT 1""", args).use { c ->
            if (!c.moveToFirst()) null else {
                if (c.getLong(1) !in 8..NormalizationWorkspaceCodec.MAX_BYTES.toLong()) reject(); c.getLong(0)
            }
        } ?: return null
        return db.rawQuery("""SELECT record,normalization_id,task_id,artifact_id,commit_id,revision,lifecycle,disposition,
            raw_size,raw_sha,source_sha,output_size,output_sha,proof_sha FROM save_normalization_workspace
            WHERE sequence=? AND length(record) BETWEEN 8 AND 2048 AND length(normalization_id)=36
            AND length(task_id) BETWEEN 1 AND 128 AND length(artifact_id)=36 AND length(commit_id) BETWEEN 1 AND 128
            AND length(lifecycle) BETWEEN 1 AND 32 AND length(disposition) BETWEEN 1 AND 32
            AND length(raw_sha)=64 AND length(source_sha)=64 AND (output_sha IS NULL OR length(output_sha)=64)
            AND (proof_sha IS NULL OR length(proof_sha)=64)""", arrayOf(sequence.toString())).use { c ->
            if (!c.moveToFirst()) reject()
            NormalizationWorkspaceCodec.decode(c.getBlob(0)).also { value ->
                if (value.key.normalizationId != c.getString(1) ||
                    value.key.owner != NormalizationOwner(c.getString(2), c.getString(3), c.getString(4)) || value.key.owner != owner ||
                    value.revision != c.getLong(5) || value.lifecycle.name != c.getString(6) || value.disposition.name != c.getString(7) ||
                    value.raw != NormalizationFingerprint(c.getLong(8), c.getString(9)) || value.sourceTransferSha256 != c.getString(10) ||
                    value.output != (if (c.isNull(11)) null else NormalizationFingerprint(c.getLong(11), c.getString(12))) ||
                    value.proofSha256 != (if (c.isNull(13)) null else c.getString(13))) reject()
            }
        }
    }

    private fun index(db: SQLiteDatabase, owned: OwnedMedia): Pair<MediaCommitReceipt, Boolean>? {
        val row = db.rawQuery("""SELECT rowid FROM save_media WHERE commit_id=? OR artifact_id=? OR media_id=? LIMIT 2""",
            arrayOf(owned.artifact.commitId, owned.artifact.artifactId, owned.mediaId)).use { c ->
            if (!c.moveToFirst()) null else c.getLong(0).also { if (c.moveToNext()) reject() }
        } ?: return null
        return db.rawQuery("""SELECT commit_id,artifact_id,media_id,size_bytes,sha256,visible FROM save_media WHERE rowid=?
            AND length(commit_id) BETWEEN 1 AND 128 AND length(artifact_id)=36 AND length(media_id)=36
            AND length(sha256)=64 AND visible IN (0,1)""", arrayOf(row.toString())).use { c ->
            if (!c.moveToFirst()) reject()
            val receipt = MediaCommitReceipt(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))
            if (receipt.commitId != owned.artifact.commitId || receipt.artifactId != owned.artifact.artifactId || receipt.mediaId != owned.mediaId) reject()
            receipt to (c.getInt(5) == 1)
        }
    }

    private fun hasLibraryAssociation(db: SQLiteDatabase, owned: OwnedMedia): Boolean = db.rawQuery("""SELECT 1 FROM library_item
        WHERE origin_commit_id=? OR item_id=? UNION ALL SELECT 1 FROM media_representation WHERE opaque_locator=? LIMIT 1""",
        arrayOf(owned.artifact.commitId, owned.mediaId, owned.mediaId)).use { it.moveToFirst() }

    private fun mediaVisible(db: SQLiteDatabase, commitId: String): Boolean = db.rawQuery(
        "SELECT visible FROM save_media WHERE commit_id=? LIMIT 1", arrayOf(commitId)).use {
        if (!it.moveToFirst()) false else { val visible = it.getInt(0); if (visible !in 0..1) reject(); visible == 1 }
    }

    /** Independent SQL-side binding. Active continuation separately requires current independent evidence. */
    private fun conversionBinding(state: NormalizedCommitState): Boolean {
        val record = state.record; val row = state.workspace; val owner = row.key.owner
        val proof = record.directConversion ?: return false
        val artifact = record.artifact ?: return false; val transfer = record.transfer ?: return false
        val source = record.request.networkSourceMetadata ?: return false
        return record.request.method == TaskMethod.DIRECT && record.task.method == TaskMethod.DIRECT &&
            record.task.taskId == owner.taskId && artifact == StagingArtifact(owner.artifactId, owner.commitId) &&
            record.commitId == owner.commitId && state.owned.taskId == owner.taskId && state.owned.artifact == artifact &&
            record.directNormalization == null && proof.isCurrentPolicy() &&
            proof.matchesSource(record.task.taskId, source.sourceIdentity, artifact, transfer) &&
            (!source.expectedAudio || proof.expectation.audio != null) &&
            proof.normalizationId == row.key.normalizationId && proof.digest() == row.proofSha256 &&
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) == row.raw &&
            NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) == row.output &&
            transfer.bytesOnDisk == row.raw.size && sourceTransferFingerprint(transfer) == row.sourceTransferSha256
    }
    private fun conversionTerminal(state: NormalizedCommitState): Boolean {
        val record = state.record; val row = state.workspace; val owned = state.owned
        val proof = record.directConversion ?: return false
        val artifact = record.artifact ?: return false; val transfer = record.transfer ?: return false
        val source = record.request.networkSourceMetadata ?: return false
        if (record.request.method != TaskMethod.DIRECT || record.task.method != TaskMethod.DIRECT ||
            record.task.state !in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED) ||
            record.directNormalization != null || record.checkpoint != null ||
            NormalizationOwner(record.task.taskId, artifact.artifactId, record.commitId) != row.key.owner || artifact.commitId != record.commitId ||
            owned.taskId != record.task.taskId || owned.artifact != artifact || row.failure != null ||
            !proof.matchesSource(record.task.taskId, source.sourceIdentity, artifact, transfer) ||
            (source.expectedAudio && proof.expectation.audio == null) ||
            proof.normalizationId != row.key.normalizationId || proof.digest() != row.proofSha256 ||
            NormalizationFingerprint(proof.rawSizeBytes, proof.rawSha256) != row.raw ||
            NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) != row.output ||
            transfer.bytesOnDisk != row.raw.size || sourceTransferFingerprint(transfer) != row.sourceTransferSha256) return false
        normalizationId(owned.mediaId)
        val evidence = record.verification
        if (evidence != null && !evidence.isValidFor(artifact, ArtifactOutputDescriptor.ConvertedDirect(transfer, proof), source.expectedAudio)) return false
        val successful = record.task.state == TaskState.SUCCEEDED
        if (successful) {
            if (record.phase != SavePhase.CLEANUP || evidence == null || row.disposition != NormalizationDisposition.CONTINUE ||
                owned.state != OwnedState.INDEXED || owned.evidence != evidence || record.receipt != owned.receipt()) return false
            // A later Library deletion does not revoke exact temporary-raw cleanup or permit republishing.
        } else {
            if (record.receipt != null || state.indexVisible || state.libraryLinked ||
                (record.phase == SavePhase.COMMITTING) != (evidence != null)) return false
            when (owned.state) {
                OwnedState.STAGING -> if (owned.evidence != null || state.indexed != null) return false
                OwnedState.INTENT -> if (evidence == null || owned.evidence != evidence || state.indexed != null) return false
                OwnedState.INDEXED -> if (evidence == null || owned.evidence != evidence || state.indexed != owned.receipt()) return false
                OwnedState.DISCARDED -> if (state.indexed != null || row.cleanup == null ||
                    (owned.evidence != null && owned.evidence != evidence)) return false
            }
        }
        when (row.lifecycle) {
            NormalizationLifecycle.OUTPUT_READY -> if (successful || record.phase !in setOf(SavePhase.NORMALIZING, SavePhase.ADOPTING) ||
                evidence != null || owned.state != OwnedState.STAGING) return false
            NormalizationLifecycle.ADOPT_INTENT -> if (successful || record.phase != SavePhase.ADOPTING ||
                evidence != null || owned.state != OwnedState.STAGING) return false
            NormalizationLifecycle.ADOPTED -> if (!successful && record.phase !in setOf(SavePhase.ADOPTING, SavePhase.VERIFYING, SavePhase.COMMITTING)) return false
            NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.CLEANED -> {
                if (!successful && (row.disposition != NormalizationDisposition.DISCARD_ONLY || owned.state != OwnedState.DISCARDED)) return false
                return conversionPlan(state, row.cleanup ?: return false)
            }
            else -> return false
        }
        return row.disposition == NormalizationDisposition.CONTINUE && row.cleanup == null
    }
    private fun conversionPlan(state: NormalizedCommitState, plan: NormalizationCleanupPlan): Boolean {
        val row = state.workspace; val ids = plan.identities ?: return false
        val all = listOfNotNull(ids.primary, ids.raw, ids.output, ids.commitOutput)
        if (all.distinct().size != all.size || all.map { it.device }.distinct().size > 1) return false
        val ready = plan.primary == row.raw && plan.raw == null && plan.output == row.output && plan.commitOutput == null
        val moved = plan.primary == null && plan.raw == row.raw && plan.output == row.output && plan.commitOutput == null
        val adopted = plan.primary == row.output && plan.raw == row.raw && plan.output == null && plan.commitOutput == null
        val promoted = plan.primary == null && plan.raw == row.raw && plan.output == null &&
            plan.commitOutput == NormalizationCommitOutput(state.owned.mediaId, row.output ?: return false)
        val valid = when (state.record.phase) {
            SavePhase.NORMALIZING -> ready
            SavePhase.ADOPTING -> ready || moved || adopted
            SavePhase.VERIFYING -> adopted
            SavePhase.COMMITTING -> adopted || promoted
            SavePhase.CLEANUP -> state.record.task.state == TaskState.SUCCEEDED && plan.primary == null &&
                (plan.raw == null || plan.raw == row.raw) && plan.output == null && plan.commitOutput == null
            else -> false
        }
        return valid
    }

    private fun active(state: NormalizedCommitState): Boolean {
        val record = state.record; val row = state.workspace
        val output = record.selectedOutputDescriptor() ?: return false
        val evidence = record.verification ?: return false
        val artifact = record.artifact ?: return false
        when (output) {
            is ArtifactOutputDescriptor.NormalizedDirect -> {
                val proof = output.normalizationProof
                if (!proof.isCurrentPolicy() ||
                    (record.request.networkSourceMetadata?.expectedAudio == true && proof.conservation.audio == null)) return false
            }
            is ArtifactOutputDescriptor.ConvertedDirect -> if (!output.conversionProof.isCurrentPolicy() ||
                (record.request.networkSourceMetadata?.expectedAudio == true && output.conversionProof.expectation.audio == null)) return false
            is ArtifactOutputDescriptor.Transferred -> return false
        }
        if (!binding(state) || record.task.state != TaskState.RUNNING || record.phase != SavePhase.COMMITTING ||
            record.receipt != null || record.checkpoint != null || state.indexVisible || state.libraryLinked ||
            row.lifecycle != NormalizationLifecycle.ADOPTED || row.disposition != NormalizationDisposition.CONTINUE ||
            row.cleanup != null || row.failure != null ||
            !evidence.isValidFor(artifact, output, record.request.networkSourceMetadata?.expectedAudio ?: false)) return false
        return when (state.owned.state) {
            OwnedState.STAGING -> state.owned.evidence == null && state.indexed == null
            OwnedState.INTENT -> state.owned.evidence == evidence && state.indexed == null
            OwnedState.INDEXED -> state.owned.evidence == evidence && state.indexed == state.owned.receipt()
            OwnedState.DISCARDED -> false
        }
    }

    private fun binding(state: NormalizedCommitState): Boolean {
        val record = state.record; val row = state.workspace; val owner = row.key.owner
        if (record.directConversion != null) return conversionBinding(state)
        val artifact = record.artifact ?: return false
        val transfer = record.transfer ?: return false
        if (record.request.method != TaskMethod.DIRECT || record.task.method != TaskMethod.DIRECT ||
            record.task.taskId != owner.taskId || artifact != StagingArtifact(owner.artifactId, owner.commitId) ||
            record.commitId != owner.commitId || state.owned.taskId != owner.taskId || state.owned.artifact != artifact ||
            transfer.bytesOnDisk != row.raw.size || sourceTransferFingerprint(transfer) != row.sourceTransferSha256) return false
        val proof = record.directNormalization
        if (proof != null && (proof.normalizationId != row.key.normalizationId || proof.rawSizeBytes != row.raw.size ||
                proof.rawSha256 != row.raw.sha256 || proof.digest() != row.proofSha256 ||
                NormalizationFingerprint(proof.outputSizeBytes, proof.outputSha256) != row.output)) return false
        if (row.lifecycle in setOf(NormalizationLifecycle.ADOPT_INTENT, NormalizationLifecycle.ADOPTED) && proof == null) return false
        return true
    }

    private fun terminal(state: NormalizedCommitState): Boolean {
        if (state.record.directConversion != null) return conversionTerminal(state)
        if (!binding(state)) return false
        val record = state.record; val row = state.workspace; val owned = state.owned
        if (record.task.state == TaskState.SUCCEEDED) return row.disposition == NormalizationDisposition.CONTINUE &&
            row.output != null && record.directNormalization?.digest() == row.proofSha256 &&
            owned.state == OwnedState.INDEXED && owned.evidence == record.verification && record.receipt == owned.receipt()
        if (record.task.state !in setOf(TaskState.FAILED, TaskState.CANCELLED) || record.receipt != null ||
            state.indexVisible || state.libraryLinked) return false
        if (owned.evidence != null && (owned.evidence != record.verification || record.directNormalization == null)) return false
        return when (owned.state) {
            OwnedState.STAGING -> owned.evidence == null && state.indexed == null
            OwnedState.INTENT -> owned.evidence != null && state.indexed == null
            OwnedState.INDEXED -> owned.evidence != null && state.indexed == owned.receipt()
            OwnedState.DISCARDED -> state.indexed == null && row.cleanup != null
        }
    }

    private fun successProposal(state: NormalizedCommitState, next: SaveRecord): Boolean {
        val prior = state.record
        if (prior.task.revision == Long.MAX_VALUE) return false
        val task = prior.task.copy(revision = prior.task.revision + 1, state = TaskState.SUCCEEDED,
            progressPercent = 100, errorCode = null, waitingUser = null)
        return next == prior.copy(task = task, phase = SavePhase.CLEANUP, receipt = state.owned.receipt(), error = null, waitReason = null)
    }

    private fun cleanupProposal(state: NormalizedCommitState, pending: NormalizationWorkspace, discard: Boolean): Boolean {
        val row = state.workspace; val plan = pending.cleanup ?: return false
        if (row.cleanup != null || row.revision == Long.MAX_VALUE || row.failure != null || plan.identities == null ||
            row.lifecycle in setOf(NormalizationLifecycle.CLEANED, NormalizationLifecycle.CLEANUP_PENDING, NormalizationLifecycle.RECOVERY_REQUIRED) ||
            discard != (state.record.task.state != TaskState.SUCCEEDED) ||
            pending != row.copy(revision = row.revision + 1, lifecycle = NormalizationLifecycle.CLEANUP_PENDING,
                disposition = if (discard) NormalizationDisposition.DISCARD_ONLY else row.disposition, cleanup = plan)) return false
        row.requireSuccessor(pending)
        if (state.record.directConversion != null && (!conversionTerminal(state) || !conversionPlan(state, plan))) return false
        if (!discard) return row.lifecycle == NormalizationLifecycle.ADOPTED &&
            plan.primary == null && plan.output == null && plan.commitOutput == null && (plan.raw == null || plan.raw == row.raw)
        if (state.owned.state in setOf(OwnedState.INTENT, OwnedState.INDEXED)) return row.lifecycle == NormalizationLifecycle.ADOPTED && plan.raw == row.raw && plan.output == null &&
                ((plan.primary == row.output && plan.primary != null && plan.commitOutput == null) ||
                    (plan.primary == null && plan.commitOutput == NormalizationCommitOutput(state.owned.mediaId, row.output ?: return false)))
        if (state.owned.state != OwnedState.STAGING || plan.commitOutput != null) return false
        return when (row.lifecycle) {
            NormalizationLifecycle.RAW_COMPLETE -> plan.primary == row.raw && plan.raw == null && (plan.output == null || plan.output.size == 0L)
            NormalizationLifecycle.WRITING -> plan.primary == row.raw && plan.raw == null
            NormalizationLifecycle.OUTPUT_READY -> plan.primary == row.raw && plan.raw == null && plan.output == row.output
            NormalizationLifecycle.ADOPT_INTENT ->
                (plan.primary == row.raw && plan.raw == null && plan.output == row.output) ||
                (plan.primary == null && plan.raw == row.raw && plan.output == row.output) ||
                (plan.primary == row.output && plan.raw == row.raw && plan.output == null)
            NormalizationLifecycle.ADOPTED -> plan.primary == row.output && plan.raw == row.raw && plan.output == null
            else -> false
        }
    }

    private fun updateWorkspace(db: SQLiteDatabase, expected: NormalizationWorkspace, next: NormalizationWorkspace) {
        expected.requireSuccessor(next)
        changed(db.update("save_normalization_workspace", workspaceValues(next),
            "normalization_id=? AND task_id=? AND artifact_id=? AND commit_id=? AND revision=?",
            arrayOf(expected.key.normalizationId, expected.key.owner.taskId, expected.key.owner.artifactId,
                expected.key.owner.commitId, expected.revision.toString())))
    }
    private fun workspaceValues(row: NormalizationWorkspace) = ContentValues().apply {
        put("normalization_id", row.key.normalizationId); put("task_id", row.key.owner.taskId)
        put("artifact_id", row.key.owner.artifactId); put("commit_id", row.key.owner.commitId)
        put("revision", row.revision); put("lifecycle", row.lifecycle.name); put("disposition", row.disposition.name)
        put("raw_size", row.raw.size); put("raw_sha", row.raw.sha256); put("source_sha", row.sourceTransferSha256)
        if (row.output == null) { putNull("output_size"); putNull("output_sha"); putNull("proof_sha") }
        else { put("output_size", row.output.size); put("output_sha", row.output.sha256); put("proof_sha", row.proofSha256) }
        put("record", NormalizationWorkspaceCodec.encode(row))
    }
    private fun recordValues(record: SaveRecord) = ContentValues().apply {
        put("task_id", record.task.taskId); put("idempotency_key", record.request.idempotencyKey)
        put("revision", record.task.revision); put("state", record.task.state.name); put("commit_id", record.commitId)
        put("record", SaveRecordCodec.encode(record))
    }
    private fun ownedArgs(expected: NormalizedCommitState) = arrayOf(expected.owned.artifact.commitId,
        expected.owned.taskId, expected.owned.artifact.artifactId, expected.owned.mediaId)
    private fun receiptArgs(receipt: MediaCommitReceipt) = arrayOf(receipt.commitId, receipt.artifactId,
        receipt.mediaId, receipt.sizeBytes.toString(), receipt.sha256)
    private fun changed(count: Int) { if (count != 1) reject() }
    private fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
    private inline fun <T> guard(block: () -> T): T = synchronized(ownerLock) {
        if (closed()) throw SaveFailure(SaveError.STORAGE_FAILURE)
        try { block() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: SQLiteFullException) { throw SaveFailure(SaveError.STORAGE_FULL) }
        catch (_: SQLiteException) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
        catch (_: IllegalArgumentException) { throw SaveFailure(SaveError.RECOVERY_REQUIRED) }
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database()
        if (db.inTransaction()) reject() // Acknowledgement must not depend on an unknown outer commit.
        db.beginTransactionNonExclusive()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
}
