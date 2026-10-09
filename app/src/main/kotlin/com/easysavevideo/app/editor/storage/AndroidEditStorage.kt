package com.easysavevideo.app.editor.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.app.editor.EditPublicationCancellation
import com.easysavevideo.app.editor.EditPublicationAdmission
import com.easysavevideo.app.editor.EditPublicationAdmissionFailure
import com.easysavevideo.app.editor.engine.EditorOwnedOutput
import com.easysavevideo.app.editor.engine.EditorOwnedInput
import com.easysavevideo.app.editor.verification.EditOwnedOutputRead
import com.easysavevideo.app.library.storage.LibrarySchema
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.*
import com.easysavevideo.library.edit.*
import com.easysavevideo.app.storage.OwnedFileOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** Same database/lock/directory owner as Save and Library, independent typed edit journal. */
internal class AndroidEditStorage(
    private val database: () -> SQLiteDatabase,
    private val lock: Any,
    root: File,
    private val isClosed: () -> Boolean,
    private val library: LibraryRepository,
    private val openSource: (EditInputRef) -> EditorOwnedInput,
    private val retainSource: (EditInputRef) -> AutoCloseable,
    syncDirectory: (File) -> Unit,
    promoteNoReplace: (File, File) -> Unit,
    private val bindDestinationPublication: (SQLiteDatabase, String, MediaReadRef) -> Unit,
) {
    private val files = EditArtifactFiles(root, syncDirectory, promoteNoReplace)
    private class Active(val sourceLeases: List<AutoCloseable>) {
        var writers = 0; var nativeWriters = 0; var readers = 0; var unconfirmed = false
        val canFinalize: Boolean get() = writers == 0 && nativeWriters == 0 && readers == 0 && !unconfirmed
    }
    private val active = mutableMapOf<String, Active>()
    private data class Row(val request: EditRequest, val output: EditOutputRef, val mediaId: String,
        val phase: String, val cancelled: Boolean, val expectation: EditOutputExpectation?, val evidence: EditVerificationEvidence?)
    data class Recovery(val interrupted: Int, val cleanupPending: Int)

    fun hasUnconfirmedOwners(): Boolean = synchronized(lock) { active.values.any { it.unconfirmed } }
    fun hasActiveOwners(): Boolean = synchronized(lock) { active.isNotEmpty() }
    fun prepare(request: EditRequest): EditOutputRef = guard {
        if (active.isNotEmpty()) throw EditFailure(EditError.CAPACITY)
        if (read(request.operationId) != null) throw EditFailure(EditError.INVALID_STATE)
        val unfinished = database().rawQuery("SELECT COUNT(*) FROM edit_job WHERE phase NOT IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')", null).use { it.moveToFirst(); it.getLong(0) }
        if (unfinished >= EditLimits.MAX_ACTIVE) throw EditFailure(EditError.CAPACITY)
        val leases = ArrayList<AutoCloseable>()
        try {
            val identities = request.segments.map { it.source }.distinct().associateWith { ref ->
                leases += retainSource(ref)
                openSource(ref).use { it.sizeBytes to it.sha256 }
            }
            if (identities.values.any { it.first !in 1..EditLimits.MAX_SOURCE_BYTES } || identities.values.sumOf { it.first } > EditLimits.MAX_TOTAL_SOURCE_BYTES)
                throw EditFailure(EditError.SOURCE_LIMIT)
            val output = EditOutputRef(request.operationId, UUID.randomUUID().toString())
            val mediaId = UUID.randomUUID().toString()
            files.assertStagingAbsent(output.artifactId)
            requireFinalUnowned(mediaId)
            files.assertFinalAbsent(mediaId)
            transaction { db ->
                db.insertOrThrow("edit_job", null, ContentValues().apply {
                    put("operation_id", request.operationId); put("fingerprint", request.fingerprint())
                    put("artifact_id", output.artifactId); put("media_id", mediaId); put("phase", "PREPARED")
                    put("request", EditRecordCodec.request(request)); put("created_at", System.currentTimeMillis())
                })
                request.segments.forEachIndexed { index, segment ->
                    val identity = identities.getValue(segment.source)
                    db.insertOrThrow("edit_source", null, ContentValues().apply {
                        put("operation_id", request.operationId); put("sequence", index)
                        when (val source = segment.source) {
                            is MediaReadRef -> { put("source_kind", "CATALOG"); put("item_id", source.itemId); put("revision", source.revision); put("generation", source.representationGeneration) }
                            is RecordedSegmentReadRef -> { put("source_kind", "RECORDED_SEGMENT"); putNull("item_id"); putNull("revision"); put("generation", source.generation)
                                put("session_id", source.sessionId); put("segment_id", source.segmentId); put("segment_sequence", source.sequence) }
                        }
                        put("start_us", segment.startUs); put("end_us", segment.endUsExclusive)
                        put("size_bytes", identity.first); put("sha256", identity.second)
                    })
                }
            }
            try { files.create(output.artifactId) } catch (failure: Exception) {
                updateTerminal(output.operationId, "CLEANUP_PENDING", EditError.EXECUTION_FAILED)
                throw failure
            }
            active[output.operationId] = Active(leases.toList())
            output
        } catch (failure: Exception) {
            if (request.operationId !in active) {
                try { leases.asReversed().forEach { it.close() } }
                catch (_: Exception) {
                    active[request.operationId] = Active(leases.toList()).also { it.unconfirmed = true }
                    throw EditFailure(EditError.RELEASE_UNCONFIRMED)
                }
            }
            throw failure
        }
    }
    fun start(output: EditOutputRef, plan: EditPlan) = guard {
        val row = requireRow(output)
        if (row.request.fingerprint() != plan.request.fingerprint() || row.phase != "PREPARED" || row.cancelled) throw EditFailure(EditError.INVALID_STATE)
        if (files.availableBytes() < plan.requiredFreeBytes) throw EditFailure(EditError.INSUFFICIENT_SPACE)
        transaction { db ->
            plan.segments.forEachIndexed { index, segment ->
                check(db.update("edit_source", ContentValues().apply {
                    put("execution_start_us", segment.executionStartUs); put("execution_end_us", segment.executionEndUsExclusive)
                    put("frame_count", segment.frameCount)
                }, "operation_id=? AND sequence=? AND execution_start_us IS NULL", arrayOf(output.operationId, index.toString())) == 1)
            }
            check(db.update("edit_job", ContentValues().apply {
                put("expectation", EditRecordCodec.expectation(plan.expectation)); put("phase", "EXECUTING")
            }, "operation_id=? AND phase='PREPARED' AND cancelled=0", arrayOf(output.operationId)) == 1)
        }
    }
    fun engineOutput(output: EditOutputRef): EditorOwnedOutput = guard {
        val row = requireRow(output); check(row.phase == "EXECUTING" && !row.cancelled)
        val owner = requireActive(output)
        EditorOwnedOutput(files.staging(output.artifactId), openWriter = {
            guard {
                check(requireRow(output).phase == "EXECUTING" && owner.writers == 0 && owner.readers == 0 && !owner.unconfirmed)
                val file = files.staging(output.artifactId); check(file.length() == 0L)
                OwnedFileOutputStream(file, ownershipLock = lock,
                    onReleased = { owner.writers-- },
                    onUnconfirmed = { throw EditFailure(EditError.RELEASE_UNCONFIRMED) })
                    .also { owner.writers++ }
            }
        }, retainNativeWriter = {
            guard {
                check(owner.nativeWriters == 0 && !owner.unconfirmed); owner.nativeWriters++
                object : AutoCloseable { var released = false
                    override fun close() = synchronized(lock) { if (!released) { released = true; owner.nativeWriters-- } }
                }
            }
        }, verifyIdentity = { guard { requireRow(output); files.staging(output.artifactId); Unit } }, availableBytes = { guard { files.availableBytes() } })
    }
    fun beginVerification(output: EditOutputRef) = guard {
        val row = requireRow(output); check(row.phase == "EXECUTING" && !row.cancelled && requireActive(output).canFinalize)
        files.sync(output.artifactId)
        check(database().update("edit_job", ContentValues().apply { put("phase", "VERIFYING") },
            "operation_id=? AND phase='EXECUTING' AND cancelled=0", arrayOf(output.operationId)) == 1)
    }
    fun openVerification(output: EditOutputRef): EditOwnedOutputRead = guard {
        val row = requireRow(output); val owner = requireActive(output)
        check(row.phase == "VERIFYING" && !row.cancelled && owner.canFinalize)
        val file = files.staging(output.artifactId); val bytes = file.length()
        check(bytes in 1..requireNotNull(row.expectation).maxBytes)
        val stream = FileInputStream(file); owner.readers++
        object : EditOwnedOutputRead {
            override val output = output
            override val expectedBytes = bytes
            override val input = stream
            private var released = false
            override fun closeAfterNativeRelease() = synchronized(lock) {
                if (!released) {
                    try { stream.close() } finally {
                        if (runCatching { !stream.fd.valid() }.getOrDefault(false)) { released = true; owner.readers-- }
                    }
                    if (!released) { owner.unconfirmed = true; throw EditFailure(EditError.RELEASE_UNCONFIRMED) }
                }
            }
            override fun retainForProcessRecovery() = synchronized(lock) { owner.unconfirmed = true }
        }
    }
    fun cancel(operationId: String) = guard {
        database().update("edit_job", ContentValues().apply { put("cancelled", 1) },
            "operation_id=? AND phase NOT IN ('PUBLISH_INTENT','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')", arrayOf(operationId))
        Unit
    }
    fun publishedReference(output: EditOutputRef): MediaReadRef? = guard {
        val row = requireRow(output)
        if (row.phase != "SUCCEEDED") return@guard null
        val item = library.item(row.mediaId)
        if (item?.lifecycle != MediaLifecycle.READY) return@guard null
        val proof = row.evidence ?: return@guard null
        if (!proof.validFor(output, requireNotNull(row.expectation))) return@guard null
        item.readRef()
    }
    fun fence(output: EditOutputRef) = guard { requireActive(output).unconfirmed = true }
    fun publish(output: EditOutputRef, evidence: EditVerificationEvidence, context: OperationContext): MediaReadRef = guard {
        val row = requireRow(output)
        check(row.phase == "VERIFYING" && !row.cancelled && requireActive(output).canFinalize)
        val expected = requireNotNull(row.expectation)
        if (!evidence.validFor(output, expected)) throw EditFailure(EditError.VERIFICATION_FAILED)
        checkContext(context); requireCurrentSources(row.request)
        files.sync(output.artifactId)
        transaction { db ->
            checkContext(context)
            check(db.update("edit_job", ContentValues().apply { put("phase", "PUBLISH_INTENT"); put("evidence", EditRecordCodec.evidence(evidence)) },
                "operation_id=? AND phase='VERIFYING' AND cancelled=0", arrayOf(output.operationId)) == 1)
        }
        requireFinalUnowned(row.mediaId)
        files.promote(output.artifactId, row.mediaId, evidence.bytes, evidence.sha256) { checkContext(context) }
        // Long IO and exact source checks remain cancellable until this final short visibility
        // admission. The token lock is never held while SQLite commits or a descriptor closes.
        try { checkContext(context); requireCurrentSources(row.request) }
        catch (failure: EditFailure) {
            if (failure.code in setOf(EditError.CANCELLED, EditError.TIMEOUT)) throw EditPublicationAdmissionFailure(failure.code)
            throw failure
        }
        val current = requireRow(output)
        check(current.phase == "PUBLISH_INTENT" && !current.cancelled && current.evidence == evidence)
        val publication = context.cancelToken as? EditPublicationCancellation ?: throw EditFailure(EditError.INVALID_REQUEST)
        when (publication.admitPublication(context.deadlineEpochMillis)) {
            EditPublicationAdmission.ADMITTED -> Unit
            EditPublicationAdmission.CANCELLED -> throw EditPublicationAdmissionFailure(EditError.CANCELLED)
            EditPublicationAdmission.DEADLINE_EXCEEDED -> throw EditPublicationAdmissionFailure(EditError.TIMEOUT)
            EditPublicationAdmission.ALREADY_ADMITTED -> throw EditFailure(EditError.PUBLICATION_UNCERTAIN)
        }
        transaction { db ->
            val representation = UUID.nameUUIDFromBytes(("ordinary-edit-v1:" + row.mediaId).toByteArray(Charsets.UTF_8)).toString()
            val name = uniqueName(db, row.mediaId)
            db.insertOrThrow("library_item", null, ContentValues().apply {
                put("item_id", row.mediaId); putNull("origin_commit_id"); put("origin_edit_id", output.operationId)
                put("revision", 1L); put("lifecycle", "READY"); put("privacy_class", "ORDINARY"); putNull("folder_id")
                put("representation_id", representation); put("generation", 1L); put("display_name", name); put("name_key", LibraryNames.key(name))
                put("created_at", System.currentTimeMillis()); put("date_kind", "SAVED")
                put("duration_millis", (evidence.actualDurationUs + 999) / 1000); put("width", evidence.actualWidth); put("height", evidence.actualHeight)
            })
            db.insertOrThrow("media_representation", null, ContentValues().apply {
                put("representation_id", representation); put("item_id", row.mediaId); put("generation", 1L)
                put("kind", "ORDINARY_FILE_V1"); put("opaque_locator", row.mediaId); put("state", "READY")
                put("size_bytes", evidence.bytes); put("sha256", evidence.sha256)
            })
            if (row.request.recordedInputs) {
                val source = row.request.segments.first().source as RecordedSegmentReadRef
                check(db.update("recording_session", ContentValues().apply {
                    put("state", "PUBLISHED"); put("published_operation", output.operationId)
                }, "session_id=? AND generation=? AND state='PROCESSING' AND pending_operation=?",
                    arrayOf(source.sessionId, source.generation.toString(), output.operationId)) == 1)
            }
            check(db.update("edit_job", ContentValues().apply { put("phase", "SUCCEEDED") },
                "operation_id=? AND phase='PUBLISH_INTENT' AND cancelled=0", arrayOf(output.operationId)) == 1)
            bindDestinationPublication(db, output.operationId, MediaReadRef(row.mediaId, 1, 1))
            LibrarySchema.bumpVersion(db)
        }
        publication.committed()
        releaseSources(output)
        MediaReadRef(row.mediaId, 1, 1)
    }
    /** Only after engine and verifier affirm actual release; unknown release must call fence instead. */
    fun finishUnpublished(output: EditOutputRef, cancelled: Boolean, error: EditError?) = guard {
        val row = requireRow(output)
        if (row.phase == "SUCCEEDED") { releaseSources(output); return@guard }
        if (!requireActive(output).canFinalize) throw EditFailure(EditError.RELEASE_UNCONFIRMED)
        check(!isPublished(output.operationId))
        var cleanupPending = false
        try {
            requireFinalUnowned(row.mediaId)
            files.cleanup(output.artifactId, row.mediaId, row.evidence?.let { it.bytes to it.sha256 })
            updateTerminal(output.operationId, if (cancelled) "CANCELLED" else "FAILED", error)
        } catch (_: Exception) { cleanupPending = true; updateTerminal(output.operationId, "CLEANUP_PENDING", error) }
        releaseSources(output)
        if (cleanupPending) throw EditFailure(EditError.PUBLICATION_UNCERTAIN)
    }
    /** Restart never auto-publishes or resumes editing. Originals/recipes remain; only owned candidates clean. */
    fun recover(): Recovery = guard {
        check(active.isEmpty())
        val rows = database().rawQuery(RECOVERY_SQL, arrayOf(EditLimits.MAX_ACTIVE.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        var interrupted = 0; var pending = 0
        rows.forEach { operationId ->
            val row = requireNotNull(read(operationId)); check(!isPublished(operationId))
            try { requireFinalUnowned(row.mediaId); files.cleanup(row.output.artifactId, row.mediaId, row.evidence?.let { it.bytes to it.sha256 }); updateTerminal(operationId, "INTERRUPTED", null); interrupted++ }
            catch (_: Exception) { updateTerminal(operationId, "CLEANUP_PENDING", null); pending++ }
        }
        Recovery(interrupted, pending)
    }
    private fun releaseSources(output: EditOutputRef) {
        val owner = requireActive(output); check(owner.canFinalize)
        try { owner.sourceLeases.asReversed().forEach { it.close() } }
        catch (_: Exception) { owner.unconfirmed = true; throw EditFailure(EditError.RELEASE_UNCONFIRMED) }
        active.remove(output.operationId)
    }
    private fun requireCurrentSources(request: EditRequest) {
        request.segments.forEach { segment ->
            val source = segment.source
            if (source is MediaReadRef) {
                val item = library.item(source.itemId)
                if (item?.lifecycle != MediaLifecycle.READY || item.readRef() != source) throw EditFailure(EditError.STALE_SOURCE)
            }
        }
        val sourceRows = sourceSnapshots(request)
        request.segments.forEachIndexed { index, segment ->
            openSource(segment.source).use { input ->
                if (input.sizeBytes != sourceRows[index].first || input.sha256 != sourceRows[index].second) throw EditFailure(EditError.STALE_SOURCE)
            }
        }
    }
    private fun sourceSnapshots(request: EditRequest): List<Pair<Long, String>> = database().rawQuery(SOURCE_SQL, arrayOf(request.operationId)).use { c ->
        val result = ArrayList<Pair<Long, String>>()
        while (c.moveToNext()) {
            val index = result.size; check(index < request.segments.size)
            val segment = request.segments[index]
            val source = when (c.getString(8)) {
                "CATALOG" -> MediaReadRef(c.getString(1), c.getLong(2), c.getLong(3))
                "RECORDED_SEGMENT" -> RecordedSegmentReadRef(c.getString(9), c.getString(10), c.getLong(3), c.getInt(11))
                else -> throw EditFailure(EditError.INVALID_REQUEST)
            }
            check(c.getInt(0) == index && source == segment.source && c.getLong(4) == segment.startUs && c.getLong(5) == segment.endUsExclusive)
            val bytes = c.getLong(6); val sha = c.getString(7)
            check(bytes in 1..EditLimits.MAX_SOURCE_BYTES && sha.matches(Regex("[a-f0-9]{64}")))
            result += bytes to sha
        }
        check(result.size == request.segments.size); result
    }
    private fun updateTerminal(operationId: String, phase: String, error: EditError?) {
        database().update("edit_job", ContentValues().apply { put("phase", phase); if (error == null) putNull("error") else put("error", error.name) },
            "operation_id=? AND phase<>'SUCCEEDED'", arrayOf(operationId))
    }
    private fun requireFinalUnowned(mediaId: String) {
        database().rawQuery(FINAL_OWNERSHIP_SQL, arrayOf(mediaId, mediaId, mediaId)).use {
            if (it.moveToFirst()) throw EditFailure(EditError.PUBLICATION_UNCERTAIN)
        }
    }
    private fun isPublished(operationId: String): Boolean = database().rawQuery(
        "SELECT 1 FROM library_item WHERE origin_edit_id=? LIMIT 1", arrayOf(operationId)).use { it.moveToFirst() }
    private fun requireActive(output: EditOutputRef): Active = active[output.operationId] ?: throw EditFailure(EditError.INVALID_STATE)
    private fun requireRow(output: EditOutputRef): Row = requireNotNull(read(output.operationId)).also { check(it.output == output) }
    private fun read(operationId: String): Row? = database().rawQuery("""SELECT request,fingerprint,artifact_id,media_id,phase,cancelled,expectation,evidence FROM edit_job
        WHERE operation_id=? AND length(request) BETWEEN 8 AND 16384 AND (expectation IS NULL OR length(expectation) BETWEEN 8 AND 1024)
        AND (evidence IS NULL OR length(evidence) BETWEEN 8 AND 4096)""", arrayOf(operationId)).use { c ->
        if (!c.moveToFirst()) null else {
            val request = EditRecordCodec.request(c.getBlob(0)); check(request.operationId == operationId && request.fingerprint() == c.getString(1))
            Row(request, EditOutputRef(operationId, c.getString(2)), c.getString(3), c.getString(4), c.getInt(5) != 0,
                if (c.isNull(6)) null else EditRecordCodec.expectation(c.getBlob(6)), if (c.isNull(7)) null else EditRecordCodec.evidence(c.getBlob(7))).also { row ->
                check(UUID.fromString(row.output.artifactId).toString() == row.output.artifactId && UUID.fromString(row.mediaId).toString() == row.mediaId)
                sourceSnapshots(request)
                row.expectation?.let { expected ->
                    database().rawQuery(EXECUTION_SQL, arrayOf(operationId)).use { execution ->
                        var count = 0; var duration = 0L; var frames = 0L
                        while (execution.moveToNext()) {
                            check(count < request.segments.size && !execution.isNull(0) && !execution.isNull(1) && !execution.isNull(2))
                            val segment = request.segments[count++]
                            val start = execution.getLong(0); val end = execution.getLong(1); val sampleCount = execution.getLong(2)
                            check(start >= segment.startUs && end <= segment.endUsExclusive && end > start && sampleCount in 1..EditLimits.MAX_VIDEO_FRAMES)
                            duration += end - start; frames += sampleCount
                        }
                        check(count == request.segments.size && duration == expected.durationUs && frames == expected.videoFrameCount)
                    }
                }
                row.evidence?.let { check(it.validFor(row.output, requireNotNull(row.expectation))) }
                if (row.phase in setOf("PUBLISH_INTENT", "SUCCEEDED")) check(!row.cancelled && row.evidence != null)
            }
        }
    }
    private fun uniqueName(db: SQLiteDatabase, id: String): String {
        repeat(32) { attempt ->
            val name = "Edit ${if (attempt == 0) id else UUID.randomUUID()}"; val key = LibraryNames.key(name)
            val occupied = db.rawQuery("""SELECT 1 FROM library_folder WHERE parent_id IS NULL AND name_key=?
                UNION ALL SELECT 1 FROM library_item WHERE folder_id IS NULL AND name_key=? AND lifecycle='READY' LIMIT 1""", arrayOf(key, key)).use { it.moveToFirst() }
            if (!occupied) return name
        }
        throw EditFailure(EditError.PUBLICATION_UNCERTAIN)
    }
    private fun checkContext(context: OperationContext) {
        if (context.cancelToken.isCancelled) throw EditFailure(EditError.CANCELLED)
        if (System.currentTimeMillis() >= context.deadlineEpochMillis) throw EditFailure(EditError.TIMEOUT)
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private inline fun <T> guard(action: () -> T): T = synchronized(lock) {
        if (isClosed()) throw EditFailure(EditError.INVALID_STATE)
        action()
    }
    companion object {
        const val EXECUTION_SQL = "SELECT execution_start_us,execution_end_us,frame_count FROM edit_source WHERE operation_id=? ORDER BY sequence LIMIT 34"
        const val SOURCE_SQL = "SELECT sequence,item_id,revision,generation,start_us,end_us,size_bytes,sha256,source_kind,session_id,segment_id,segment_sequence FROM edit_source WHERE operation_id=? ORDER BY sequence LIMIT 34"
        const val FINAL_OWNERSHIP_SQL = "SELECT 1 FROM media_representation WHERE opaque_locator=? OR item_id=? UNION ALL SELECT 1 FROM save_owned WHERE media_id=? LIMIT 1"
        const val RECOVERY_SQL = "SELECT operation_id FROM edit_job WHERE phase NOT IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED') ORDER BY operation_id LIMIT ?"
    }
}
