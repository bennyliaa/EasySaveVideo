package com.easysavevideo.app.recording.segments

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.app.editor.engine.EditorOwnedInput
import com.easysavevideo.app.editor.storage.EditRecordCodec
import com.easysavevideo.app.editor.verification.EditOwnedOutputRead
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.library.edit.*
import com.easysavevideo.recording.*
import com.easysavevideo.app.storage.OwnedFileOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

internal class RecordedSegmentOutput(val handle: String, val openWriter: () -> FileOutputStream,
    val retainNativeWriter: () -> AutoCloseable, val availableBytes: () -> Long)

/**
 * Private, durable segment owner. Closed is not verified; verified is not published. Native fences
 * cannot be cleared by cancellation or a timeout. Restart never resumes capture or auto-publishes.
 */
internal class AndroidRecordedSegmentStorage(private val database: () -> SQLiteDatabase, private val lock: Any,
    root: File, private val isClosed: () -> Boolean, syncDirectory: (File) -> Unit) {
    private val files = RecordedSegmentFiles(root, syncDirectory)
    private class Owner { var writers = 0; var native = 0; var readers = 0; var poisoned = false
        val idle get() = writers == 0 && native == 0 && readers == 0 && !poisoned }
    private val owners = mutableMapOf<String, Owner>()
    private data class Session(val config: RecordedCaptureConfig, val state: String, val publishedOperation: String?, val pendingOperation: String?)
    private data class Row(val id: String, val sequence: Int, val state: String, val identity: String?, val start: Long,
        val end: Long?, val bytes: Long?, val sha: String?, val capture: RecordingArtifactExpectation?, val proof: EditVerificationEvidence?, val geometry: CaptureGeometryObservation?)
    data class CleanupResult(val removed: Int, val pending: Int) { val complete get() = pending == 0 }
    data class Recovery(val retained: Int, val cleanupPending: Int)
    data class SegmentSummary(val ordinal: Int, val durationUs: Long?, val verified: Boolean, val state: String) {
        val retained: Boolean get() = state != "DISCARDED"
        val cleanupPending: Boolean get() = state == "CLEANUP_PENDING"
        val independentlyValid: Boolean get() = retained && verified
    }
    data class Snapshot(val descriptor: CaptureDescriptor, val geometry: CaptureDisplayGeometry, val region: RegionCaptureRecipe?,
        val state: String, val segments: List<SegmentSummary>, val publishedReference: com.easysavevideo.library.MediaReadRef?)
    fun snapshot(session: RecordingSessionId): Snapshot = guard {
        val state = session(session)
        val published = (state.publishedOperation ?: state.pendingOperation)?.let { operation ->
            database().rawQuery("SELECT item_id,revision,generation FROM library_item WHERE origin_edit_id=? AND lifecycle='READY'", arrayOf(operation)).use { c ->
                if (c.moveToFirst()) com.easysavevideo.library.MediaReadRef(c.getString(0), c.getLong(1), c.getLong(2)) else null
            }
        }
        Snapshot(state.config.descriptor, state.config.geometry, state.config.region, state.state,
            rows(session).map { SegmentSummary(it.sequence, it.capture?.effectiveDurationUs, it.state == "VERIFIED", it.state) }, published)
    }
    fun listRecoverableSessions(): List<Snapshot> = guard {
        database().rawQuery("""SELECT session_id,generation FROM recording_session s WHERE state<>'CANCELLED'
            AND (state<>'PUBLISHED' OR EXISTS(SELECT 1 FROM recording_segment r WHERE r.session_id=s.session_id AND r.state<>'DISCARDED'))
            ORDER BY created_at,session_id LIMIT 8""", null).use { c ->
            buildList { while (c.moveToNext()) add(snapshot(RecordingSessionId(c.getString(0), c.getLong(1)))) }
        }
    }
    fun hasActiveOwners(): Boolean = synchronized(lock) { files.requiresRecovery || owners.values.any { !it.idle } }
    fun hasUnconfirmedOwners(): Boolean = synchronized(lock) { files.requiresRecovery || owners.values.any { it.poisoned } }

    fun prepareSession(descriptor: CaptureDescriptor, geometry: CaptureDisplayGeometry, region: RegionCaptureRecipe?) = guard {
        if (hasActiveOwners()) fail(EditError.CAPACITY)
        require(region == descriptor.regionCaptureRecipe && (region == null || region.source == geometry))
        val count = database().rawQuery("SELECT COUNT(*) FROM recording_session s WHERE state<>'CANCELLED' AND (state<>'PUBLISHED' OR EXISTS(SELECT 1 FROM recording_segment r WHERE r.session_id=s.session_id AND r.state<>'DISCARDED'))", null).use { it.moveToFirst(); it.getLong(0) }
        if (count >= MAX_SESSIONS) fail(EditError.CAPACITY)
        val config = RecordedCaptureConfig(descriptor, geometry, region)
        database().insertOrThrow("recording_session", null, ContentValues().apply {
            put("session_id", descriptor.sessionId); put("generation", descriptor.generation); put("config", RecordedSegmentCodec.config(config))
            put("state", "CAPTURING"); put("created_at", System.currentTimeMillis())
        }); Unit
    }
    fun openOutput(session: RecordingSessionId, index: Int): RecordedSegmentOutput = guard {
        val state = session(session); check(state.state == "CAPTURING")
        val existing = rows(session)
        if (index !in 0 until EditLimits.MAX_RECORDED_SEGMENTS || existing.size != index ||
            existing.any { it.state !in setOf("CLOSED_UNVERIFIED", "VERIFIED") } || owners.values.any { !it.idle }) fail(EditError.INVALID_STATE)
        val start = existing.lastOrNull()?.end ?: 0L
        val totalBytes = existing.sumOf { requireNotNull(it.bytes) }
        if (start >= EditLimits.MAX_OUTPUT_DURATION_US || totalBytes >= MAX_GROUP_BYTES || files.bytesAvailable() == 0L) fail(EditError.SOURCE_LIMIT)
        val id = UUID.randomUUID().toString()
        database().insertOrThrow("recording_segment", null, ContentValues().apply {
            put("segment_id", id); put("session_id", session.sessionId); put("sequence", index); put("state", "ALLOCATED"); put("effective_start_us", start)
        })
        val (_, identity) = files.create(id)
        check(database().update("recording_segment", ContentValues().apply { put("file_identity", identity) }, "segment_id=? AND file_identity IS NULL", arrayOf(id)) == 1)
        val owner = Owner(); owners[id] = owner
        RecordedSegmentOutput(id, openWriter = {
            guard {
                val row = row(session, index); check(session(session).state == "CAPTURING" && row.id == id && row.state == "ALLOCATED" && owner.writers == 0 && owner.readers == 0 && !owner.poisoned)
                val file = files.resolve(id, identity); check(file.length() == 0L)
                OwnedFileOutputStream(file, ownershipLock = lock,
                    onReleased = { owner.writers-- },
                    onUnconfirmed = { synchronized(lock) { owner.poisoned = true }; fail(EditError.RELEASE_UNCONFIRMED) })
                    .also { owner.writers++ }
            }
        }, retainNativeWriter = {
            guard {
                val row = row(session, index)
                check(session(session).state == "CAPTURING" && row.id == id && row.state == "ALLOCATED" && owner.native == 0 && owner.readers == 0 && !owner.poisoned)
                owner.native++
                object : AutoCloseable { private var closed = false
                    override fun close() = synchronized(lock) { if (!closed) { closed = true; owner.native-- } }
                }
            }
        }, availableBytes = { guard { minOf(files.bytesAvailable(), (MAX_GROUP_BYTES - totalBytes - files.resolve(id, identity).length()).coerceAtLeast(0)) } })
    }
    fun confirmClosed(session: RecordingSessionId, index: Int, expectation: RecordingArtifactExpectation, timing: RecordingFrameTimeline, geometry: CaptureGeometryObservation) = guard {
        val state = session(session); val row = row(session, index); val owner = owners[row.id] ?: fail(EditError.INVALID_STATE)
        check(state.state == "CAPTURING" && row.state == "ALLOCATED" && owner.idle)
        require(expectation.descriptor == state.config.descriptor && expectation.resumeBoundaries?.isEmpty() == true)
        require(timing.frameCount.toLong() == expectation.video.sampleCount && timing.firstFrameUs == expectation.video.firstPtsUs &&
            timing.lastFrameUs == expectation.video.lastPtsUs && timing.endUsExclusive == expectation.video.endPtsUs)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        repeat(timing.frameCount) { digest.update(java.nio.ByteBuffer.allocate(8).putLong(timing.frameTimeUs(it)).array()) }
        digest.update(java.nio.ByteBuffer.allocate(8).putLong(timing.endUsExclusive).array())
        val timingHash = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        val previous = rows(session).take(index)
        val region = state.config.region
        val end = row.start + expectation.effectiveDurationUs
        val hash = files.seal(row.id, requireNotNull(row.identity), expectation.bytesWritten)
        check(database().update("recording_segment", ContentValues().apply {
            put("state", "CLOSED_UNVERIFIED"); put("size_bytes", expectation.bytesWritten); put("sha256", hash)
            put("effective_end_us", end); put("geometry", RecordedSegmentCodec.observation(geometry)); put("timing_sha256", timingHash); put("capture", RecordedSegmentCodec.capture(expectation))
        }, "segment_id=? AND state='ALLOCATED'", arrayOf(row.id)) == 1)
        if (geometry.geometry != state.config.geometry || (region != null && !RegionCapturePolicy.matches(region, geometry))) fail(EditError.INVALID_CROP)
        // Persist genuinely closed raw data before reporting incompatible/unsupported processing.
        if (previous.any { it.capture?.actualProfile != expectation.actualProfile || ((it.capture?.audio == null) != (expectation.audio == null)) }) fail(EditError.INCOMPATIBLE_SEGMENTS)
        if (region != null && (expectation.actualProfile.width != region.pixelCrop.width || expectation.actualProfile.height != region.pixelCrop.height)) fail(EditError.INVALID_CROP)
        if (end > EditLimits.MAX_OUTPUT_DURATION_US || previous.sumOf { requireNotNull(it.bytes) } + expectation.bytesWritten > MAX_GROUP_BYTES) fail(EditError.SOURCE_LIMIT)
    }
    fun stopped(session: RecordingSessionId) = guard {
        session(session); check(!hasActiveOwners())
        setState(session, "STOPPED", setOf("CAPTURING")); Unit
    }
    fun abandoned(session: RecordingSessionId, index: Int) = guard {
        val row = row(session, index); val owner = owners[row.id]
        if (owner != null && !owner.idle) { owner.poisoned = true; setState(session, "RECOVERY_REQUIRED"); return@guard }
        check(row.state == "ALLOCATED")
        if (!cleanup(row)) { setState(session, "RECOVERY_REQUIRED"); fail(EditError.PUBLICATION_UNCERTAIN) }
        check(database().delete("recording_segment", "segment_id=? AND state='DISCARDED' AND capture IS NULL", arrayOf(row.id)) == 1)
        Unit
    }
    fun fence(session: RecordingSessionId) = guard {
        rows(session).forEach { owners.getOrPut(it.id) { Owner() }.poisoned = true }
        setState(session, "RECOVERY_REQUIRED"); Unit
    }
    fun candidates(session: RecordingSessionId): List<Int> = guard {
        val state = session(session); check(state.state in setOf("STOPPED", "RETAINED", "PROCESSING"))
        val rows = rows(session)
        if (rows.isEmpty() || rows.any { it.state !in setOf("CLOSED_UNVERIFIED", "VERIFIED") }) fail(EditError.INVALID_STATE)
        val first = requireNotNull(rows.first().capture)
        if (rows.any { it.capture?.actualProfile != first.actualProfile || ((it.capture?.audio == null) != (first.audio == null)) } ||
            rows.sumOf { requireNotNull(it.bytes) } > MAX_GROUP_BYTES || requireNotNull(rows.last().end) > EditLimits.MAX_OUTPUT_DURATION_US ||
            rows.sumOf { requireNotNull(it.capture).video.sampleCount } > EditLimits.MAX_VIDEO_FRAMES) fail(EditError.INCOMPATIBLE_SEGMENTS)
        if (rows.any { it.geometry?.geometry != state.config.geometry || it.geometry?.scopeEvidence != rows.first().geometry?.scopeEvidence }) fail(EditError.INCOMPATIBLE_SEGMENTS)
        state.config.region?.let { region -> if (first.actualProfile.width != region.encodedWidth || first.actualProfile.height != region.encodedHeight ||
            rows.any { it.geometry?.let { observation -> RegionCapturePolicy.matches(region, observation) } != true }) fail(EditError.INVALID_CROP) }
        rows.map { it.sequence }
    }
    fun verificationTarget(session: RecordingSessionId, index: Int): Pair<EditOutputRef, EditOutputExpectation> = guard {
        val row = row(session, index); check(row.state in setOf("CLOSED_UNVERIFIED", "VERIFIED"))
        val capture = requireNotNull(row.capture); val profile = capture.actualProfile
        val audio = if (capture.audio == null) EditAudioProfile.Silent else EditAudioProfile.Aac(profile.audioSampleRate, profile.audioChannels)
        // Configured fps is a candidate to TEST. The independent verifier must prove every observed
        // timestamp on that CFR lattice; neither native completion nor this expectation asserts CFR.
        EditOutputRef(row.id, row.id) to EditOutputExpectation(capture.video.endPtsUs, profile.width, profile.height,
            profile.frameRate, audio, (1_000_000L + profile.frameRate - 1) / profile.frameRate,
            if (audio is EditAudioProfile.Aac) (2_048_000_000L + audio.sampleRate - 1) / audio.sampleRate else 0,
            maxBytes = requireNotNull(row.bytes), videoFrameCount = capture.video.sampleCount, recordedSegmentCount = 1)
    }
    fun openVerification(output: EditOutputRef): EditOwnedOutputRead = guard {
        check(output.operationId == output.artifactId)
        val pair = find(output.artifactId); val row = pair.second
        check(row.state in setOf("CLOSED_UNVERIFIED", "VERIFIED"))
        val owner = owners.getOrPut(row.id) { Owner() }; check(owner.idle)
        val input = openExact(row); owner.readers++
        object : EditOwnedOutputRead {
            override val output = output
            override val expectedBytes = requireNotNull(row.bytes)
            override val input = input
            private var released = false
            override fun closeAfterNativeRelease() = synchronized(lock) {
                if (!released) {
                    try { input.close() } finally {
                        if (runCatching { !input.fd.valid() }.getOrDefault(false)) { released = true; owner.readers-- }
                    }
                    if (!released) { owner.poisoned = true; fail(EditError.RELEASE_UNCONFIRMED) }
                }
            }
            override fun retainForProcessRecovery() = synchronized(lock) { owner.poisoned = true }
        }
    }
    fun confirmVerified(session: RecordingSessionId, index: Int, proof: EditVerificationEvidence, context: OperationContext) = guard {
        val row = row(session, index); val target = verificationTarget(session, index)
        if (owners[row.id]?.idle == false) fail(EditError.RELEASE_UNCONFIRMED)
        if (!proof.validFor(target.first, target.second) || proof.bytes != row.bytes || proof.sha256 != row.sha) fail(EditError.VERIFICATION_FAILED)
        exact(row) { checkpoint(context) }
        check(database().update("recording_segment", ContentValues().apply { put("state", "VERIFIED"); put("proof", EditRecordCodec.evidence(proof)) },
            "segment_id=? AND state IN ('CLOSED_UNVERIFIED','VERIFIED')", arrayOf(row.id)) == 1)
    }
    fun verifiedSources(session: RecordingSessionId): List<RecordedSegmentReadRef> = guard {
        candidates(session)
        rows(session).map { row ->
            check(row.state == "VERIFIED" && requireNotNull(row.proof).validFor(verificationTarget(session, row.sequence).first, verificationTarget(session, row.sequence).second))
            RecordedSegmentReadRef(session.sessionId, row.id, session.generation, row.sequence)
        }
    }
    fun sourceDuration(reference: RecordedSegmentReadRef): Long = guard { requireNotNull(requireSource(reference).capture).video.endPtsUs }
    fun retainSource(reference: RecordedSegmentReadRef): AutoCloseable = guard {
        val row = requireSource(reference); val owner = owners.getOrPut(row.id) { Owner() }
        check(owner.writers == 0 && owner.native == 0 && !owner.poisoned)
        owner.readers++
        object : AutoCloseable { private var released = false
            override fun close() = synchronized(lock) { if (!released) { released = true; owner.readers-- } }
        }
    }
    fun openSource(reference: RecordedSegmentReadRef): EditorOwnedInput = guard {
        val row = requireSource(reference); val owner = owners.getOrPut(row.id) { Owner() }
        check(owner.writers == 0 && owner.native == 0 && !owner.poisoned)
        val input = openExact(row); owner.readers++; var closed = false
        EditorOwnedInput(input, requireNotNull(row.bytes), requireNotNull(row.sha)) {
            synchronized(lock) {
                if (!closed) {
                    try { input.close() } finally { if (runCatching { !input.fd.valid() }.getOrDefault(false)) { closed = true; owner.readers-- } }
                    if (!closed) { owner.poisoned = true; fail(EditError.RELEASE_UNCONFIRMED) }
                }
            }
        }
    }
    fun processing(session: RecordingSessionId, operationId: String) = guard {
        com.easysavevideo.library.LibraryIds.requireValid(operationId)
        verifiedSources(session)
        check(snapshot(session).publishedReference == null)
        setState(session, "PROCESSING", setOf("STOPPED", "RETAINED"))
        check(database().update("recording_session", ContentValues().apply { put("pending_operation", operationId) }, "session_id=?", arrayOf(session.sessionId)) == 1)
    }
    fun retainAfterFailure(session: RecordingSessionId) = guard { if (session(session).state != "RECOVERY_REQUIRED") setState(session, "RETAINED", setOf("STOPPED", "PROCESSING", "RETAINED")); Unit }
    fun published(session: RecordingSessionId, operationId: String) = guard {
        val committed = database().rawQuery("SELECT 1 FROM edit_job e JOIN library_item l ON l.origin_edit_id=e.operation_id WHERE e.operation_id=? AND e.phase='SUCCEEDED' AND l.lifecycle='READY'", arrayOf(operationId)).use { it.moveToFirst() }
        val state = session(session)
        check(committed && state.pendingOperation == operationId && state.state in setOf("PROCESSING", "RETAINED", "PUBLISHED"))
        val sourceCount = database().rawQuery("SELECT COUNT(*) FROM edit_source WHERE operation_id=? AND source_kind='RECORDED_SEGMENT' AND session_id=? AND generation=?", arrayOf(operationId, session.sessionId, session.generation.toString())).use { it.moveToFirst(); it.getInt(0) }
        check(sourceCount == rows(session).size)
        check(database().update("recording_session", ContentValues().apply { put("state", "PUBLISHED"); put("published_operation", operationId) }, "session_id=?", arrayOf(session.sessionId)) == 1)
    }
    /** Discard is explicit; post-processing failure never invokes it. */
    fun discard(session: RecordingSessionId): CleanupResult = guard {
        val state = session(session); val rows = rows(session)
        if (files.requiresRecovery || rows.any { owners[it.id]?.idle == false }) fail(EditError.RELEASE_UNCONFIRMED)
        if (state.state != "PUBLISHED") setState(session, "CANCEL_PENDING")
        var removed = 0; var pending = 0
        rows.forEach { if (cleanup(it)) removed++ else pending++ }
        if (pending == 0 && state.state != "PUBLISHED") setState(session, "CANCELLED")
        CleanupResult(removed, pending)
    }
    /** Bounded journal-only recovery. Closed raw recordings survive, even if CFR is unsupported. */
    fun recover(): Recovery = guard {
        check(!hasActiveOwners())
        val sessions = database().rawQuery("SELECT session_id,generation FROM recording_session s WHERE state<>'CANCELLED' AND (state<>'PUBLISHED' OR EXISTS(SELECT 1 FROM recording_segment r WHERE r.session_id=s.session_id AND r.state<>'DISCARDED')) ORDER BY session_id LIMIT ?", arrayOf((MAX_SESSIONS + 1).toString())).use { c ->
            buildList { while (c.moveToNext()) add(RecordingSessionId(c.getString(0), c.getLong(1))) }
        }
        if (sessions.size > MAX_SESSIONS) fail(EditError.CAPACITY)
        var retained = 0; var pending = 0
        sessions.forEach { session ->
            var state = session(session)
            if (state.pendingOperation != null && snapshot(session).publishedReference != null) {
                published(session, state.pendingOperation); state = session(session)
            }
            val cleanupAll = state.state == "CANCEL_PENDING"
            rows(session).forEach { row ->
                if (cleanupAll || row.state in setOf("ALLOCATED", "CLEANUP_PENDING")) {
                    if (!cleanup(row)) pending++
                    else if (!cleanupAll && row.capture == null) database().delete("recording_segment", "segment_id=? AND state='DISCARDED'", arrayOf(row.id))
                }
                else if (row.state != "DISCARDED") { try { exact(row); retained++ } catch (_: Exception) { pending++ } }
            }
            if (state.state !in setOf("PUBLISHED", "CANCEL_PENDING")) setState(session, "RETAINED")
            if (state.state == "CANCEL_PENDING" && rows(session).all { it.state == "DISCARDED" }) setState(session, "CANCELLED")
        }
        Recovery(retained, pending)
    }
    private fun requireSource(reference: RecordedSegmentReadRef): Row {
        val session = RecordingSessionId(reference.sessionId, reference.generation)
        check(session(session).state in setOf("STOPPED", "PROCESSING", "RETAINED"))
        val row = row(session, reference.sequence)
        if (row.id != reference.segmentId || row.state != "VERIFIED" || row.proof == null) fail(EditError.STALE_SOURCE)
        val expected = verificationTarget(session, reference.sequence)
        if (!row.proof.validFor(expected.first, expected.second) || row.proof.bytes != row.bytes || row.proof.sha256 != row.sha) fail(EditError.STALE_SOURCE)
        return row
    }
    private fun openExact(row: Row): FileInputStream {
        val file = files.resolve(row.id, requireNotNull(row.identity)); val stream = FileInputStream(file)
        try { check(stream.channel.size() == row.bytes); files.resolve(row.id, row.identity); return stream }
        catch (failure: Exception) { stream.close(); throw failure }
    }
    private fun exact(row: Row, checkpoint: () -> Unit = {}) {
        if (files.hash(row.id, requireNotNull(row.identity), requireNotNull(row.bytes), checkpoint) != row.sha) fail(EditError.STALE_SOURCE)
    }
    private fun cleanup(row: Row): Boolean {
        if (files.requiresRecovery) fail(EditError.RELEASE_UNCONFIRMED)
        if (row.state == "DISCARDED") return true
        check(owners[row.id]?.idle != false)
        return try {
            files.remove(row.id, row.identity, row.bytes, row.sha)
            database().update("recording_segment", ContentValues().apply { put("state", "DISCARDED") }, "segment_id=?", arrayOf(row.id)); owners.remove(row.id); true
        } catch (_: Exception) {
            database().update("recording_segment", ContentValues().apply { put("state", "CLEANUP_PENDING") }, "segment_id=?", arrayOf(row.id)); false
        }
    }
    private fun session(id: RecordingSessionId): Session = database().rawQuery("SELECT config,state,published_operation,generation,pending_operation FROM recording_session WHERE session_id=? AND length(config) BETWEEN 8 AND 2048", arrayOf(id.sessionId)).use { c ->
        check(c.moveToFirst() && c.getLong(3) == id.generation)
        Session(RecordedSegmentCodec.config(c.getBlob(0)), c.getString(1), if (c.isNull(2)) null else c.getString(2), if (c.isNull(4)) null else c.getString(4)).also { check(it.config.descriptor.session == id) }
    }
    private fun find(id: String): Pair<RecordingSessionId, Row> = database().rawQuery("SELECT r.session_id,s.generation,r.sequence FROM recording_segment r JOIN recording_session s ON s.session_id=r.session_id WHERE segment_id=?", arrayOf(id)).use { c ->
        check(c.moveToFirst()); val session = RecordingSessionId(c.getString(0), c.getLong(1)); session to row(session, c.getInt(2))
    }
    private fun row(session: RecordingSessionId, index: Int): Row = rows(session).singleOrNull { it.sequence == index } ?: fail(EditError.INVALID_STATE)
    private fun rows(session: RecordingSessionId): List<Row> {
        session(session)
        val count = database().rawQuery("SELECT COUNT(*) FROM recording_segment WHERE session_id=?", arrayOf(session.sessionId)).use { c -> c.moveToFirst(); c.getLong(0) }
        check(count in 0..EditLimits.MAX_RECORDED_SEGMENTS.toLong())
        return database().rawQuery("""SELECT segment_id,sequence,state,file_identity,effective_start_us,effective_end_us,size_bytes,sha256,capture,proof,geometry
            FROM recording_segment WHERE session_id=? AND (capture IS NULL OR length(capture) BETWEEN 8 AND 2048)
            AND (proof IS NULL OR length(proof) BETWEEN 8 AND 4096)
            AND (geometry IS NULL OR length(geometry) BETWEEN 8 AND 2048) ORDER BY sequence LIMIT 34""", arrayOf(session.sessionId)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    check(size < EditLimits.MAX_RECORDED_SEGMENTS && c.getInt(1) == size)
                    add(Row(c.getString(0), c.getInt(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getLong(4),
                        if (c.isNull(5)) null else c.getLong(5), if (c.isNull(6)) null else c.getLong(6), if (c.isNull(7)) null else c.getString(7),
                        if (c.isNull(8)) null else RecordedSegmentCodec.capture(c.getBlob(8)), if (c.isNull(9)) null else EditRecordCodec.evidence(c.getBlob(9)), if (c.isNull(10)) null else RecordedSegmentCodec.observation(c.getBlob(10))))
                    val row = last(); check(row.start == if (size == 1) 0L else requireNotNull(get(size - 2).end))
                    row.capture?.let { check(it.descriptor.session == session && row.end == row.start + it.effectiveDurationUs && row.bytes == it.bytesWritten) }
                }
                check(size.toLong() == count) // A corrupt oversized tail must not become a shorter accepted recipe.
            }
        }
    }
    private fun setState(session: RecordingSessionId, state: String, allowed: Set<String>? = null) {
        if (allowed != null) check(session(session).state in allowed)
        check(database().update("recording_session", ContentValues().apply { put("state", state) }, "session_id=? AND generation=?", arrayOf(session.sessionId, session.generation.toString())) == 1)
    }
    private fun checkpoint(context: OperationContext) { if (context.cancelToken.isCancelled) fail(EditError.CANCELLED); if (System.currentTimeMillis() >= context.deadlineEpochMillis) fail(EditError.TIMEOUT) }
    private inline fun <T> guard(block: () -> T): T = synchronized(lock) { check(!isClosed()); block() }
    private fun fail(error: EditError): Nothing = throw EditFailure(error)
    companion object { const val MAX_SESSIONS = 8; const val MAX_GROUP_BYTES = 2L * 1024 * 1024 * 1024 }
}
