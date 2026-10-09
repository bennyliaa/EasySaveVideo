package com.easysavevideo.app.save.storage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOpenHelper
import android.system.Os
import android.system.OsConstants
import com.easysavevideo.app.editor.storage.EditSchema
import com.easysavevideo.app.editor.engine.EditorOwnedInput
import com.easysavevideo.app.recording.segments.AndroidRecordedSegmentStorage
import com.easysavevideo.app.recording.segments.RecordedSegmentSchema
import com.easysavevideo.library.EditInputRef
import com.easysavevideo.library.RecordedSegmentReadRef
import com.easysavevideo.app.editor.storage.AndroidEditStorage
import com.easysavevideo.app.library.destination.AndroidFolderGrantJournal
import com.easysavevideo.app.library.destination.FolderDestinationSchema
import com.easysavevideo.app.library.destination.AndroidOrdinaryDestinationJournal
import com.easysavevideo.app.library.destination.OrdinaryDestinationSchema
import com.easysavevideo.app.library.export.AndroidExportJournal
import com.easysavevideo.app.library.export.ExportSchema
import com.easysavevideo.app.library.storage.AndroidLibraryRepository
import com.easysavevideo.app.library.storage.LibrarySchema
import com.easysavevideo.app.library.storage.OrdinaryMediaFiles
import com.easysavevideo.app.library.storage.StorageOwnerRelease
import com.easysavevideo.library.LibraryFailure
import com.easysavevideo.library.LibraryRepository
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.*
import com.easysavevideo.app.storage.OwnedFileInputStream
import com.easysavevideo.app.storage.OwnedFileOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Private Android storage. Nothing here uses public MediaStore, M2 databases, external names or backups.
 * One instance is owned by the app composition root and closed only after active Save work has stopped.
 */
class AndroidSaveStorage(context: Context) : SaveJournal, PrivateMediaStore, AutoCloseable {
    private val directory = File(context.applicationContext.noBackupFilesDir, "save_m3_v1")
    private val helper: Database
    private val catalog: Catalog
    private val protocol: PrivateCommitProtocol
    private val libraryStorage: AndroidLibraryRepository
    /** The catalog shares this storage owner's database, lock and directory lifetime. */
    val library: LibraryRepository get() = libraryStorage
    internal val exportJournal: AndroidExportJournal by lazy { AndroidExportJournal({ helper.writableDatabase }, fileLock, { closed }) }
    internal val folderGrants: AndroidFolderGrantJournal by lazy { AndroidFolderGrantJournal({ helper.writableDatabase }, fileLock, { closed }) }
    internal val destinations: AndroidOrdinaryDestinationJournal by lazy { AndroidOrdinaryDestinationJournal({ helper.writableDatabase }, fileLock, { closed }) }
    private var segmentStorage: AndroidRecordedSegmentStorage? = null
    internal val recordedSegments: AndroidRecordedSegmentStorage get() = synchronized(fileLock) {
        segmentStorage ?: AndroidRecordedSegmentStorage({ helper.writableDatabase }, fileLock, directory, { closed }, AndroidFileOps::syncDirectory)
            .also { segmentStorage = it }
    }
    private fun openEditSource(ref: EditInputRef): EditorOwnedInput = when (ref) {
        is MediaReadRef -> openLibraryMedia(ref).let { EditorOwnedInput(it.stream, it.sizeBytes, it.sha256, it::close) }
        is RecordedSegmentReadRef -> recordedSegments.openSource(ref)
    }
    private fun retainEditSource(ref: EditInputRef): AutoCloseable = when (ref) {
        is MediaReadRef -> retainNativeMediaReader(ref)
        is RecordedSegmentReadRef -> recordedSegments.retainSource(ref)
    }
    private var editorStorage: AndroidEditStorage? = null
    internal val edits: AndroidEditStorage get() = synchronized(fileLock) {
        editorStorage ?: AndroidEditStorage({ helper.writableDatabase }, fileLock, directory, { closed }, libraryStorage,
            ::openEditSource, ::retainEditSource, AndroidFileOps::syncDirectory, AndroidFileOps::promoteNoReplace,
            destinations::bindVerifiedPublication)
            .also { editorStorage = it }
    }
    private val openWriters = mutableSetOf<String>()
    private val nativeWriters = mutableSetOf<String>()
    private var openReaders = 0
    // Sole monitor for Save, Library and Editor. Never nest this under an instance monitor.
    private val fileLock = Any()
    private val ownerLease: PrivateDirectoryLease
    @Volatile private var closed = false
    init {
        if (Files.isSymbolicLink(directory.toPath()) || (!directory.isDirectory && !directory.mkdirs()))
            throw SaveFailure(SaveError.STORAGE_FAILURE)
        synchronized(failedOwnerLock) {
            if (failedStorageOwner != null) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        ownerLease = PrivateDirectoryLease(directory)
        var initializingHelper: Database? = null
        try {
            helper = Database(context.applicationContext, File(directory, "save_m3_v1.db")).also { initializingHelper = it }
            catalog = Catalog()
            protocol = PrivateCommitProtocol(directory, catalog, AndroidFileOps)
            libraryStorage = AndroidLibraryRepository({ helper.writableDatabase }, fileLock,
                OrdinaryMediaFiles(File(directory, "media"), AndroidFileOps::syncDirectory), { closed })
            // Bounded, durable-only recovery. Failed unlink remains a visible pending operation.
            libraryStorage.processPendingDeletes(100)
        } catch (failure: Exception) {
            releaseStorageOwner(initializingHelper)
            throw failure
        }
    }
    private companion object {
        val failedOwnerLock = Any()
        var failedStorageOwner: Pair<AutoCloseable?, AutoCloseable>? = null
    }
    override fun findByIdempotencyKey(key: String): SaveRecord? = dbCall {
        queryRecord("idempotency_key=?", arrayOf(key))
    }
    override fun read(taskId: String): SaveRecord? = dbCall { queryRecord("task_id=?", arrayOf(taskId)) }
    override fun insert(record: SaveRecord, maxEntries: Int): SaveRecord = dbCall {
        require(maxEntries in 1..1000)
        transaction { db ->
            queryRecord("idempotency_key=?", arrayOf(record.request.idempotencyKey))?.let { return@transaction it }
            // Terminal provenance and idempotency rows do not consume active-work capacity.
            val count = db.rawQuery("SELECT COUNT(*) FROM save_tasks WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')", null).use { it.moveToFirst(); it.getLong(0) }
            if (count >= maxEntries) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            db.insertOrThrow("save_tasks", null, recordValues(record))
            record
        }
    }
    override fun compareAndSet(expectedRevision: Long, next: SaveRecord): Boolean = dbCall {
        transaction { db ->
            val prior = queryRecord("task_id=?", arrayOf(next.task.taskId)) ?: return@transaction false
            if (prior.task.revision != expectedRevision || prior.task.state.isTerminal ||
                next.task.revision != expectedRevision + 1 || next.commitId != prior.commitId ||
                next.request != prior.request) return@transaction false
            if (next.task.state == TaskState.SUCCEEDED) {
                val receipt = next.receipt ?: return@transaction false
                val row = catalog.findOwned(next.commitId) ?: return@transaction false
                if (row.taskId != next.task.taskId || row.state != OwnedState.INDEXED || row.receipt() != receipt ||
                    row.artifact != next.artifact || row.evidence != next.verification || catalog.indexed(next.commitId) != receipt)
                    return@transaction false
                val updated = db.update("save_media", ContentValues().apply { put("visible", 1) },
                    "commit_id=? AND media_id=? AND visible=0", arrayOf(receipt.commitId, receipt.mediaId))
                if (updated != 1) return@transaction false
                LibrarySchema.requireVerifiedPublication(db, next)
                LibrarySchema.publish(db, next, System.currentTimeMillis(), imported = false)
                destinations.bindVerifiedPublication(db, next.task.taskId, MediaReadRef(receipt.mediaId, 1, 1))
            }
            val changed = db.update("save_tasks", recordValues(next), "task_id=? AND revision=?",
                arrayOf(next.task.taskId, expectedRevision.toString()))
            if (changed != 1) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) // Roll back provisional publication too.
            true
        }
    }
    override fun listUnfinished(limit: Int): List<SaveRecord> = dbCall {
        records("state NOT IN ('SUCCEEDED','FAILED','CANCELLED')", limit)
    }
    override fun listRecent(limit: Int): List<SaveRecord> = dbCall { records(null, limit) }
    /** Only items whose task was atomically published SUCCEEDED are visible to the user's private library. */
    fun visibleMedia(limit: Int): List<MediaCommitReceipt> = dbCall {
        require(limit in 1..1000)
        helper.readableDatabase.rawQuery("""SELECT m.commit_id,m.artifact_id,m.media_id,m.size_bytes,m.sha256
            FROM save_media m JOIN save_tasks t ON t.commit_id=m.commit_id
            JOIN library_item i ON i.origin_commit_id=m.commit_id
            JOIN media_representation r ON r.representation_id=i.representation_id AND r.item_id=i.item_id AND r.generation=i.generation
            WHERE m.visible=1 AND t.state='SUCCEEDED' AND i.lifecycle='READY' AND i.privacy_class='ORDINARY'
            AND r.state='READY' AND r.kind='ORDINARY_FILE_V1' ORDER BY m.rowid DESC LIMIT ?""", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(MediaCommitReceipt(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))) }
        }
    }
    override fun prepare(taskId: String, commitId: String, context: OperationContext): StagingArtifact = fileCall {
        protocol.prepare(taskId, commitId, context)
    }
    override fun commit(artifact: VerifiedArtifact, context: OperationContext): MediaCommitReceipt = fileCall {
        ensureActiveCommit(artifact.artifact.commitId)
        ensureNoWriter(artifact.artifact)
        protocol.commit(artifact.artifact, artifact.evidence, context)
    }
    override fun inspectCommitted(commitId: String, context: OperationContext): CommitInspection? = fileCall {
        // A terminal Save is historic provenance. Never promote/reindex an obsolete staging file
        // after a logical lifecycle change, even if that old path happens to exist again.
        if (queryRecord("commit_id=?", arrayOf(commitId))?.task?.state?.isTerminal == true) return@fileCall null
        catalog.findOwned(commitId)?.let { ensureNoWriter(it.artifact) }
        protocol.inspectCommitted(commitId, context)
    }
    override fun cleanup(artifact: StagingArtifact, committed: Boolean) = fileCall {
        ensureNoWriter(artifact)
        protocol.cleanupStream(artifact)
        protocol.cleanup(artifact, committed)
    }
    fun streamDirectory(artifact: StagingArtifact): File = fileCall { ensureNoWriter(artifact); protocol.streamDirectory(artifact) }
    fun cleanupStream(artifact: StagingArtifact) = fileCall { ensureNoWriter(artifact); protocol.cleanupStream(artifact) }
    fun adoptStreamOutput(artifact: StagingArtifact, output: File, context: OperationContext) = fileCall {
        ensureNoWriter(artifact); protocol.adoptStreamOutput(artifact, output, context)
    }
    /** Writable staging only. The path is generated locally and must match the durable ownership row. */
    fun stagingFile(artifact: StagingArtifact): File = fileCall { protocol.stagingFile(artifact) }
    /** Read-only validator input, also allowing a previously promoted, still provisional commit on recovery. */
    fun verificationFile(artifact: StagingArtifact): File = fileCall { ensureActiveCommit(artifact.commitId); ensureNoWriter(artifact); protocol.verificationFile(artifact) }
    fun availableBytes(artifact: StagingArtifact): Long = fileCall {
        protocol.stagingFile(artifact); protocol.availableBytes()
    }
    fun openStaging(artifact: StagingArtifact, append: Boolean): FileOutputStream = fileCall {
        ensureNoWriter(artifact)
        val file = if (append) protocol.stagingFile(artifact) else protocol.restartStaging(artifact)
        if (protocol.availableBytes() <= 0) throw SaveFailure(SaveError.STORAGE_FULL)
        val output = OwnedFileOutputStream(file, append, fileLock,
            onReleased = { openWriters.remove(artifact.artifactId); Unit },
            onUnconfirmed = { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) })
        openWriters.add(artifact.artifactId)
        output
    }
    /**
     * MediaMuxer duplicates a supplied FD. Closing FileOutputStream alone does not prove native
     * writer release. Retain this fence before native construction and close it only after a
     * successful native release; a throwing/hung release deliberately blocks until process restart.
     */
    fun retainNativeWriter(artifact: StagingArtifact): AutoCloseable = fileCall {
        protocol.stagingFile(artifact)
        if (artifact.artifactId !in openWriters || !nativeWriters.add(artifact.artifactId))
            throw SaveFailure(SaveError.BUSY)
        object : AutoCloseable {
            private var released = false
            override fun close() = synchronized(fileLock) {
                if (!released) { released = true; nativeWriters.remove(artifact.artifactId) }
                Unit
            }
        }
    }
    /**
     * An owned read-only descriptor for preview, with no path/URI input or exposure. The caller
     * holds it until its player is released. The catalog independently rechecks publication.
     */
    fun openPublishedMedia(receipt: MediaCommitReceipt): FileInputStream = fileCall {
        openCurrentRead(libraryStorage.resolveReceipt(receipt))
    }
    /** New callers must bind their selection to the exact current revision and representation. */
    fun openPublishedMedia(ref: MediaReadRef): FileInputStream = fileCall { openCurrentRead(ref) }
    /** Optional separately owned fence for a native reader that duplicates an input descriptor. */
    fun retainNativeMediaReader(ref: MediaReadRef): AutoCloseable = fileCall {
        libraryStorage.acquireRead(ref).lease
    }
    /** Exact current catalog authority and evidence, acquired together; never accepts a caller path. */
    fun openLibraryMedia(ref: MediaReadRef): LibraryMediaInput = fileCall {
        val authority = libraryStorage.acquireRead(ref)
        LibraryMediaInput(openAuthority(authority), authority.sizeBytes, authority.sha256)
    }
    class LibraryMediaInput internal constructor(val stream: FileInputStream, val sizeBytes: Long, val sha256: String) : AutoCloseable {
        override fun close() = stream.close()
        override fun toString() = "LibraryMediaInput([redacted])"
    }
    private fun openCurrentRead(ref: MediaReadRef): FileInputStream = openAuthority(libraryStorage.acquireRead(ref))
    private fun openAuthority(authority: AndroidLibraryRepository.ReadAuthority): FileInputStream {
        try {
            val input = OwnedFileInputStream(authority.file, fileLock,
                onReleased = { openReaders--; authority.lease.close() },
                onUnconfirmed = { throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) })
            openReaders++
            return input
        } catch (failure: Exception) { authority.lease.close(); throw failure }
    }
    /** Drop only an uncheckpointed tail after transport has validated the resumed response identity. */
    fun truncateStaging(artifact: StagingArtifact, length: Long) = fileCall {
        ensureNoWriter(artifact)
        val file = protocol.stagingFile(artifact)
        if (length <= 0 || length > file.length()) throw SaveFailure(SaveError.INVALID_RANGE)
        RandomAccessFile(file, "rw").use { it.setLength(length); it.fd.sync() }
    }
    fun syncStaging(artifact: StagingArtifact) = fileCall { protocol.syncStaging(artifact) }
    override fun close() = synchronized(fileLock) {
        synchronized(failedOwnerLock) {
            if (failedStorageOwner?.second === ownerLease) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)
        }
        if (openWriters.isNotEmpty() || nativeWriters.isNotEmpty() || openReaders != 0 || libraryStorage.hasActiveReaders() || editorStorage?.hasActiveOwners() == true || segmentStorage?.hasActiveOwners() == true) throw SaveFailure(SaveError.BUSY)
        if (!closed) {
            closed = true
            releaseStorageOwner(helper)
        }
    }
    private fun releaseStorageOwner(database: AutoCloseable?) {
        StorageOwnerRelease.close(database, ownerLease) { unconfirmedDatabase, unconfirmedOwner ->
            // One app-owned directory, one strongly retained failure fence. Never retry ownership
            // admission over a possibly live database/native directory lock in this process.
            synchronized(failedOwnerLock) { failedStorageOwner = unconfirmedDatabase to unconfirmedOwner }
        }
    }
    private fun ensureActiveCommit(commitId: String) {
        if (queryRecord("commit_id=?", arrayOf(commitId))?.task?.state?.isTerminal == true)
            throw SaveFailure(SaveError.INVALID_REQUEST)
    }
    private fun ensureNoWriter(artifact: StagingArtifact) {
        if (artifact.artifactId in openWriters || artifact.artifactId in nativeWriters) throw SaveFailure(SaveError.BUSY)
    }
    private fun queryRecord(selection: String, args: Array<String>): SaveRecord? {
        val rowId = helper.readableDatabase.query("save_tasks", arrayOf("rowid", "length(record)"),
            selection, args, null, null, null, "1").use { c ->
            if (!c.moveToFirst()) null else {
                if (c.getLong(1) !in 8..SaveRecordCodec.MAX_BYTES.toLong()) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                c.getLong(0)
            }
        }
        return rowId?.let(::readRecordRow)
    }
    private fun readRecordRow(rowId: Long): SaveRecord = helper.readableDatabase.rawQuery("""SELECT record,task_id,
        idempotency_key,revision,state,commit_id FROM save_tasks WHERE rowid=? AND length(record) BETWEEN 8 AND 32768
        AND length(task_id) BETWEEN 1 AND 128 AND length(idempotency_key) BETWEEN 1 AND 128
        AND length(state) BETWEEN 1 AND 32 AND length(commit_id) BETWEEN 1 AND 128""", arrayOf(rowId.toString())).use { c ->
        if (!c.moveToFirst()) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        decode(c.getBlob(0)).also { record ->
            if (record.task.taskId != c.getString(1) || record.request.idempotencyKey != c.getString(2) ||
                record.task.revision != c.getLong(3) || record.task.state.name != c.getString(4) || record.commitId != c.getString(5))
                throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        }
    }
    private fun records(selection: String?, limit: Int): List<SaveRecord> {
        require(limit in 1..1000)
        val rows = helper.readableDatabase.query("save_tasks", arrayOf("rowid", "length(record)"), selection, null,
            null, null, "rowid DESC", limit.toString()).use { c -> buildList {
                while (c.moveToNext()) {
                    if (c.getLong(1) !in 8..SaveRecordCodec.MAX_BYTES.toLong()) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                    add(c.getLong(0))
                }
            } }
        return rows.map(::readRecordRow)
    }
    private fun decode(bytes: ByteArray): SaveRecord = try { SaveRecordCodec.decode(bytes) }
    catch (_: Exception) { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }
    private fun recordValues(record: SaveRecord) = ContentValues().apply {
        put("task_id", record.task.taskId); put("idempotency_key", record.request.idempotencyKey)
        put("revision", record.task.revision); put("state", record.task.state.name)
        put("commit_id", record.commitId); put("record", SaveRecordCodec.encode(record))
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private inline fun <T> dbCall(block: () -> T): T = synchronized(fileLock) {
        if (closed) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
        try { block() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: SQLiteFullException) { throw SaveFailure(SaveError.STORAGE_FULL) }
        catch (_: SQLiteException) { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }
        catch (_: IllegalArgumentException) { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }
    }
    private inline fun <T> fileCall(block: () -> T): T = synchronized(fileLock) { ioCall(block) }
    private inline fun <T> ioCall(block: () -> T): T = dbCall {
        try { block() }
        catch (failure: SaveFailure) { throw failure }
        catch (_: IOException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
        catch (failure: android.system.ErrnoException) { throw SaveFailure(if (failure.errno == OsConstants.ENOSPC) SaveError.STORAGE_FULL else SaveError.STORAGE_FAILURE) }
        catch (_: SecurityException) { throw SaveFailure(SaveError.STORAGE_FAILURE) }
    }
    private inner class Catalog : OwnedMediaCatalog {
        override fun findOwned(commitId: String): OwnedMedia? {
            val exists = helper.readableDatabase.rawQuery("""SELECT length(evidence),length(task_id),length(artifact_id),
                length(media_id),length(state) FROM save_owned WHERE commit_id=? LIMIT 1""", arrayOf(commitId)).use { c ->
                if (!c.moveToFirst()) false else {
                    if ((!c.isNull(0) && c.getLong(0) !in 1..4096) || (1..3).any { c.getLong(it) !in 1..128 } ||
                        c.getLong(4) !in 1..32) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                    true
                }
            }
            if (!exists) return null
            return helper.readableDatabase.rawQuery("""SELECT task_id,artifact_id,media_id,state,evidence FROM save_owned
                WHERE commit_id=? AND (evidence IS NULL OR length(evidence) BETWEEN 1 AND 4096)
                AND length(task_id) BETWEEN 1 AND 128 AND length(artifact_id) BETWEEN 1 AND 128
                AND length(media_id) BETWEEN 1 AND 128 AND length(state) BETWEEN 1 AND 32 LIMIT 1""", arrayOf(commitId)).use { c ->
                if (!c.moveToFirst()) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
                OwnedMedia(c.getString(0), StagingArtifact(c.getString(1), commitId), c.getString(2), OwnedState.valueOf(c.getString(3)),
                    if (c.isNull(4)) null else SaveRecordCodec.evidence(c.getBlob(4)))
            }
        }
        override fun createOwned(row: OwnedMedia): OwnedMedia = transaction { db ->
            findOwned(row.artifact.commitId)?.let { return@transaction it }
            val task = queryRecord("task_id=?", arrayOf(row.taskId)) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (task.commitId != row.artifact.commitId || task.task.state.isTerminal) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            db.insertOrThrow("save_owned", null, ContentValues().apply {
                put("commit_id", row.artifact.commitId); put("task_id", row.taskId); put("artifact_id", row.artifact.artifactId)
                put("media_id", row.mediaId); put("state", row.state.name)
            })
            row
        }
        override fun persistIntent(row: OwnedMedia, evidence: VerificationEvidence): OwnedMedia = transaction { db ->
            val task = queryRecord("task_id=?", arrayOf(row.taskId)) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (task.task.state != TaskState.RUNNING || task.phase != SavePhase.COMMITTING || task.artifact != row.artifact ||
                task.verification != evidence) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            val current = findOwned(row.artifact.commitId) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (current.state != OwnedState.STAGING || current.artifact != row.artifact) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            val updated = db.update("save_owned", ContentValues().apply {
                put("state", OwnedState.INTENT.name); put("evidence", SaveRecordCodec.evidenceBytes(evidence))
            }, "commit_id=? AND state='STAGING'", arrayOf(row.artifact.commitId))
            if (updated != 1) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            current.copy(state = OwnedState.INTENT, evidence = evidence)
        }
        override fun indexProvisional(row: OwnedMedia) = transaction { db ->
            val task = queryRecord("task_id=?", arrayOf(row.taskId)) ?: throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (task.task.state.isTerminal) throw SaveFailure(SaveError.INVALID_REQUEST)
            val receipt = row.receipt()
            val existing = indexed(row.artifact.commitId)
            if (existing != null && existing != receipt) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (existing == null) db.insertOrThrow("save_media", null, ContentValues().apply {
                put("commit_id", receipt.commitId); put("artifact_id", receipt.artifactId); put("media_id", receipt.mediaId)
                put("size_bytes", receipt.sizeBytes); put("sha256", receipt.sha256); put("visible", 0)
            })
            db.update("save_owned", ContentValues().apply { put("state", OwnedState.INDEXED.name) },
                "commit_id=? AND state IN ('INTENT','INDEXED')", arrayOf(row.artifact.commitId))
            Unit
        }
        override fun indexed(commitId: String): MediaCommitReceipt? = helper.readableDatabase.query("save_media",
            arrayOf("artifact_id", "media_id", "size_bytes", "sha256"), "commit_id=?", arrayOf(commitId), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) null else MediaCommitReceipt(commitId, c.getString(0), c.getString(1), c.getLong(2), c.getString(3))
        }
        override fun published(commitId: String): MediaCommitReceipt? {
            val task = queryRecord("commit_id=?", arrayOf(commitId)) ?: return null
            val receipt = task.receipt ?: return null
            if (task.task.state != TaskState.SUCCEEDED || task.commitId != commitId) return null
            try { libraryStorage.resolveReceipt(receipt) } catch (_: LibraryFailure) { return null }
            val row = findOwned(commitId) ?: return null
            if (row.taskId != task.task.taskId || row.state != OwnedState.INDEXED || row.artifact != task.artifact ||
                row.evidence != task.verification || row.receipt() != receipt) return null
            return helper.readableDatabase.query("save_media",
                arrayOf("artifact_id", "media_id", "size_bytes", "sha256"), "commit_id=? AND visible=1",
                arrayOf(commitId), null, null, null, "1").use { c ->
                if (!c.moveToFirst()) null else MediaCommitReceipt(commitId, c.getString(0), c.getString(1),
                    c.getLong(2), c.getString(3)).takeIf { it == receipt }
            }
        }
        override fun discard(row: OwnedMedia) {
            helper.writableDatabase.update("save_owned", ContentValues().apply { put("state", OwnedState.DISCARDED.name) },
                "commit_id=? AND artifact_id=? AND state='STAGING'", arrayOf(row.artifact.commitId, row.artifact.artifactId))
        }
    }
    private class Database(context: Context, private val file: File) : SQLiteOpenHelper(context, file.absolutePath, null, 7,
        DatabaseErrorHandler { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }) {
        override fun onConfigure(db: SQLiteDatabase) {
            // Legacy parent-table migration requires this before the version transaction.
            db.setForeignKeyConstraintsEnabled(db.version !in 1..3)
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE save_tasks(task_id TEXT PRIMARY KEY NOT NULL,idempotency_key TEXT UNIQUE NOT NULL,
                revision INTEGER NOT NULL,state TEXT NOT NULL,commit_id TEXT UNIQUE NOT NULL,record BLOB NOT NULL)""")
            db.execSQL("""CREATE TABLE save_owned(commit_id TEXT PRIMARY KEY NOT NULL REFERENCES save_tasks(commit_id),
                task_id TEXT UNIQUE NOT NULL REFERENCES save_tasks(task_id),artifact_id TEXT UNIQUE NOT NULL,
                media_id TEXT UNIQUE NOT NULL,state TEXT NOT NULL,evidence BLOB)""")
            db.execSQL("""CREATE TABLE save_media(media_id TEXT PRIMARY KEY NOT NULL,commit_id TEXT UNIQUE NOT NULL REFERENCES save_owned(commit_id),
                artifact_id TEXT UNIQUE NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),sha256 TEXT NOT NULL,
                visible INTEGER NOT NULL DEFAULT 0 CHECK(visible IN (0,1)))""")
            LibrarySchema.create(db)
            ExportSchema.create(db)
            RecordedSegmentSchema.create(db)
            EditSchema.create(db)
            EditSchema.migrateLibrary(db)
            OrdinaryDestinationSchema.create(db)
            FolderDestinationSchema.migrate(db)
        }
        override fun onOpen(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA foreign_keys", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
            EditSchema.requireForeignKeysValid(db)
            db.rawQuery("PRAGMA quick_check(1)", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion !in 1..6 || newVersion != 7) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            // SQLiteOpenHelper rolls DDL, every backfilled row and user_version back together on failure.
            try {
                if (oldVersion == 1) {
                    LibrarySchema.create(db)
                    LibrarySchema.backfill(db, System.currentTimeMillis(), OrdinaryMediaFiles(File(file.parentFile, "media"), {}))
                }
                if (oldVersion < 3) ExportSchema.create(db)
                if (oldVersion < 5) RecordedSegmentSchema.create(db)
                if (oldVersion < 4) {
                    EditSchema.create(db)
                    EditSchema.migrateLibrary(db)
                } else if (oldVersion == 4) EditSchema.migrateRecordedSources(db)
                if (oldVersion < 6) OrdinaryDestinationSchema.create(db)
                FolderDestinationSchema.migrate(db)
                EditSchema.requireForeignKeysValid(db)
            }
            catch (_: Exception) { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }
    }
    private object AndroidFileOps : DurableFileOps {
        override fun syncDirectory(directory: File) {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw SaveFailure(SaveError.STORAGE_FAILURE)
                Os.fsync(fd)
            } finally { Os.close(fd) }
        }
        override fun promoteNoReplace(source: File, destination: File) {
            // The storage lifetime FileChannel lock excludes every cooperating process/instance.
            // Android private directories exclude other UIDs; generated names exclude server control.
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) throw SaveFailure(SaveError.COMMIT_MISMATCH)
            if (Os.stat(source.parentFile!!.absolutePath).st_dev != Os.stat(destination.parentFile!!.absolutePath).st_dev)
                throw SaveFailure(SaveError.STORAGE_FAILURE)
            Os.rename(source.absolutePath, destination.absolutePath)
        }
        override fun replaceOwnedStaging(source: File, destination: File) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
