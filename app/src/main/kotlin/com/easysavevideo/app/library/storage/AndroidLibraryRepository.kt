package com.easysavevideo.app.library.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.easysavevideo.library.*
import com.easysavevideo.save.MediaCommitReceipt
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** A narrow catalog adapter owned by AndroidSaveStorage, with its database and serialization lock. */
internal class AndroidLibraryRepository(
    private val database: () -> SQLiteDatabase,
    private val lock: Any,
    private val files: OrdinaryMediaFiles,
    private val isClosed: () -> Boolean,
) : LibraryRepository {
    private val leases = MediaLeaseRegistry { itemId -> recoverAfterRelease(itemId) }
    private var activeReaders = 0
    private val deleting = mutableSetOf<String>()

    override fun query(query: LibraryQuery): LibraryPage = guard {
        val db = database()
        val version = version(db)
        LibraryQueryPolicy.requireCurrentCursor(query, version)
        query.folderId?.let { requireFolder(it) }
        val selection = parentSelection("i.folder_id", query.folderId)
        val args = mutableListOf<String>().apply {
            query.folderId?.let(::add)
            add(LibraryNames.searchKey(query.text))
            add((query.pageSize + 1).toString())
            add((query.cursor?.offset ?: 0).toString())
        }
        val column = when (query.sort) {
            LibrarySort.NAME -> "i.name_key"
            LibrarySort.DATE -> "i.created_at"
            LibrarySort.DURATION -> "i.duration_millis"
            LibrarySort.SIZE -> "r.size_bytes"
        }
        val direction = if (query.direction == SortDirection.ASCENDING) "ASC" else "DESC"
        val nullOrder = if (query.sort == LibrarySort.DURATION) "i.duration_millis IS NULL ASC," else ""
        val rows = db.rawQuery("""$ITEM_SELECT WHERE $selection AND i.lifecycle='READY' AND r.state='READY'
            AND i.privacy_class='ORDINARY' AND r.kind='ORDINARY_FILE_V1' AND instr(i.name_key,?)>0
            ORDER BY $nullOrder $column $direction,i.item_id ASC LIMIT ? OFFSET ?""", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(readItem(c)) }
        }
        val offset = query.cursor?.offset ?: 0
        if (rows.isEmpty() && offset != 0) throw LibraryFailure(LibraryError.STALE_PAGE)
        val hasMore = rows.size > query.pageSize
        val nextOffset = offset.toLong() + minOf(rows.size, query.pageSize)
        if (nextOffset > Int.MAX_VALUE) throw LibraryFailure(LibraryError.INVALID_QUERY)
        LibraryPage(rows.take(query.pageSize), version,
            if (hasMore) LibraryCursor(version, query.queryKey(), nextOffset.toInt()) else null)
    }

    override fun item(itemId: String): MediaItem? = guard { LibraryIds.requireValid(itemId); readItem(itemId) }
    override fun folder(folderId: String): LibraryFolder? = guard { LibraryIds.requireValid(folderId); readFolder(folderId) }
    override fun folders(parentId: String?): List<LibraryFolder> = guard {
        parentId?.let { requireFolder(it) }
        database().rawQuery("$FOLDER_SELECT WHERE ${parentSelection("f.parent_id", parentId)} ORDER BY f.name_key,f.folder_id LIMIT 1001",
            parentId?.let { arrayOf(it) }).use { c -> buildList {
            while (c.moveToNext()) add(readFolder(c))
        } }.also { if (it.size > LibraryLimits.MAX_FOLDERS) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED) }
    }

    override fun createFolder(displayName: String, parentId: String?, operationId: String): LibraryFolder = guard {
        val name = LibraryNames.validate(displayName)
        parentId?.let(LibraryIds::requireValid)
        val fingerprint = fingerprint("CREATE_FOLDER", null, 0, parentId, name)
        transaction { db ->
            replay(operationId, fingerprint)?.let { return@transaction requireFolder(it.targetId) }
            LibraryFolderPolicy.requireCreateAllowed(parentId, name, allFolders())
            requireUniqueName(parentId, name)
            val id = UUID.randomUUID().toString()
            db.insertOrThrow("library_folder", null, ContentValues().apply {
                put("folder_id", id); nullable("parent_id", parentId); put("revision", 1L)
                put("display_name", name); put("name_key", LibraryNames.key(name))
            })
            recordOperation(db, operationId, "CREATE_FOLDER", id, 0, fingerprint, 1)
            LibrarySchema.bumpVersion(db)
            requireFolder(id)
        }
    }

    override fun renameFolder(folderId: String, expectedRevision: Long, displayName: String, operationId: String): LibraryFolder = guard {
        val name = LibraryNames.validate(displayName)
        mutateFolder(folderId, expectedRevision, operationId, "RENAME_FOLDER", name) { current ->
            requireUniqueName(current.parentId, name, excludingFolder = folderId)
            ContentValues().apply { put("display_name", name); put("name_key", LibraryNames.key(name)) }
        }
    }

    override fun moveFolder(folderId: String, expectedRevision: Long, parentId: String?, operationId: String): LibraryFolder = guard {
        parentId?.let(LibraryIds::requireValid)
        mutateFolder(folderId, expectedRevision, operationId, "MOVE_FOLDER", parentId) { current ->
            LibraryFolderPolicy.requireMoveAllowed(folderId, parentId, allFolders())
            requireUniqueName(parentId, current.displayName, excludingFolder = folderId)
            ContentValues().apply { nullable("parent_id", parentId) }
        }
    }

    override fun deleteEmptyFolder(folderId: String, expectedRevision: Long, operationId: String): Unit = guard {
        LibraryIds.requireValid(folderId)
        val fingerprint = fingerprint("DELETE_FOLDER", folderId, expectedRevision)
        transaction { db ->
            if (replay(operationId, fingerprint) != null) return@transaction
            val current = requireFolder(folderId)
            checkRevision(current.revision, expectedRevision)
            if (current.itemCount != 0 || current.childFolderCount != 0) throw LibraryFailure(LibraryError.FOLDER_NOT_EMPTY)
            if (db.delete("library_folder", "folder_id=? AND revision=?", arrayOf(folderId, expectedRevision.toString())) != 1)
                throw LibraryFailure(LibraryError.STALE_REVISION)
            recordOperation(db, operationId, "DELETE_FOLDER", folderId, expectedRevision, fingerprint, expectedRevision + 1)
            LibrarySchema.bumpVersion(db)
        }
    }

    override fun renameItem(itemId: String, expectedRevision: Long, displayName: String, operationId: String): MediaItem = guard {
        val name = LibraryNames.validate(displayName)
        mutateItem(itemId, expectedRevision, operationId, "RENAME_ITEM", name) { current ->
            requireUniqueName(current.folderId, name, excludingItem = itemId)
            ContentValues().apply { put("display_name", name); put("name_key", LibraryNames.key(name)) }
        }
    }

    override fun moveItem(itemId: String, expectedRevision: Long, folderId: String?, operationId: String): MediaItem = guard {
        folderId?.let(LibraryIds::requireValid)
        mutateItem(itemId, expectedRevision, operationId, "MOVE_ITEM", folderId) { current ->
            folderId?.let { requireFolder(it) }
            requireUniqueName(folderId, current.displayName, excludingItem = itemId)
            ContentValues().apply { nullable("folder_id", folderId) }
        }
    }

    override fun requestDelete(request: ConfirmedDeleteRequest): DeleteOutcome = guard {
        request.requireConfirmation()
        val ref = request.ref
        val fingerprint = fingerprint("DELETE_ITEM", ref.itemId, ref.revision, ref.representationGeneration.toString())
        transaction { db ->
            if (replay(request.operationId, fingerprint) != null) return@transaction
            requireReady(ref)
            if (ref.revision > Long.MAX_VALUE - 2) throw LibraryFailure(LibraryError.STALE_REVISION)
            val changed = db.update("library_item", ContentValues().apply {
                put("lifecycle", "DELETE_PENDING"); put("revision", ref.revision + 1)
            }, "item_id=? AND revision=? AND generation=? AND lifecycle='READY'",
                arrayOf(ref.itemId, ref.revision.toString(), ref.representationGeneration.toString()))
            if (changed != 1) throw LibraryFailure(LibraryError.STALE_REVISION)
            if (db.update("media_representation", ContentValues().apply { put("state", "DELETE_PENDING") },
                    "item_id=? AND generation=? AND state='READY'", arrayOf(ref.itemId, ref.representationGeneration.toString())) != 1)
                throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            recordOperation(db, request.operationId, "DELETE_ITEM", ref.itemId, ref.revision, fingerprint,
                ref.revision + 1, "DELETE_PENDING")
            LibrarySchema.bumpVersion(db)
        }
        finishDelete(ref.itemId, request.operationId)
        if (readItem(ref.itemId)?.lifecycle == MediaLifecycle.DELETED) DeleteOutcome.DELETED else DeleteOutcome.DELETE_PENDING
    }

    override fun processPendingDeletes(limit: Int): PendingDeleteReport = guard {
        if (limit !in 1..LibraryLimits.MAX_DELETE_BATCH) throw LibraryFailure(LibraryError.INVALID_QUERY)
        val cursor = database().rawQuery("SELECT delete_cursor FROM library_state WHERE singleton=1", null).use {
            if (!it.moveToFirst()) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            it.getString(0)
        }
        fun batch(after: String): List<Pair<String, String>> = database().rawQuery(DELETE_BATCH_SQL,
            arrayOf(after, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val pending = batch(cursor).ifEmpty { if (cursor.isEmpty()) emptyList() else batch("") }
        if (pending.isNotEmpty()) {
            // Persist rotation before attempting IO: restart cannot repeatedly privilege failing IDs.
            database().update("library_state", ContentValues().apply { put("delete_cursor", pending.last().second) }, "singleton=1", null)
        }
        var completed = 0; var failed = 0
        for ((id, operationId) in pending) when (finishDelete(id, operationId)) {
            DeleteAttempt.COMPLETE -> completed++
            DeleteAttempt.WAITING -> Unit
            DeleteAttempt.FAILED -> failed++
        }
        val remaining = database().rawQuery("SELECT COUNT(*) FROM library_operation WHERE phase='DELETE_PENDING'", null).use {
            it.moveToFirst(); it.getLong(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        PendingDeleteReport(completed, remaining, failed)
    }

    /** Exact revision and representation generation are rechecked under the shared storage lock. */
    fun acquireRead(ref: MediaReadRef): ReadAuthority = guard {
        requireReady(ref)
        val lease = leases.tryAcquireRead(ref.itemId) ?: throw LibraryFailure(LibraryError.BUSY)
        try {
            val representation = representation(ref.itemId)
            if (representation.state != "READY" || representation.generation != ref.representationGeneration)
                throw LibraryFailure(LibraryError.NOT_FOUND)
            val file = files.readable(requireNotNull(representation.locator), representation.sizeBytes)
            activeReaders++
            ReadAuthority(file, representation.sizeBytes, requireNotNull(representation.sha256), object : AutoCloseable {
                private var released = false
                override fun close() = synchronized(lock) {
                    if (!released) { released = true; activeReaders--; lease.close() }
                    Unit
                }
            })
        } catch (failure: Exception) { lease.close(); throw failure }
    }

    /** Historic receipts are provenance. Resolve the current disposition rather than their old file name. */
    fun resolveReceipt(receipt: MediaCommitReceipt): MediaReadRef = guard {
        val item = readItem(receipt.mediaId) ?: throw LibraryFailure(LibraryError.NOT_FOUND)
        if (item.lifecycle != MediaLifecycle.READY) throw LibraryFailure(LibraryError.NOT_FOUND)
        val representation = representation(item.itemId)
        if (representation.state != "READY" || representation.commitId != receipt.commitId ||
            representation.sizeBytes != receipt.sizeBytes || representation.sha256 != receipt.sha256)
            throw LibraryFailure(LibraryError.NOT_FOUND)
        database().rawQuery("SELECT artifact_id FROM save_media WHERE media_id=? AND commit_id=? AND visible=1",
            arrayOf(receipt.mediaId, receipt.commitId)).use { c ->
            if (!c.moveToFirst() || c.getString(0) != receipt.artifactId) throw LibraryFailure(LibraryError.NOT_FOUND)
        }
        item.readRef()
    }

    fun hasActiveReaders(): Boolean = synchronized(lock) { activeReaders != 0 }
    internal data class ReadAuthority(val file: File, val sizeBytes: Long, val sha256: String, val lease: AutoCloseable)

    private fun mutateFolder(id: String, expected: Long, operationId: String, kind: String, argument: String?, values: (LibraryFolder) -> ContentValues): LibraryFolder {
        LibraryIds.requireValid(id)
        val fingerprint = fingerprint(kind, id, expected, argument)
        return transaction { db ->
            replay(operationId, fingerprint)?.let { return@transaction requireFolder(it.targetId) }
            val current = requireFolder(id); checkRevision(current.revision, expected)
            val update = values(current).apply { put("revision", expected + 1) }
            if (db.update("library_folder", update, "folder_id=? AND revision=?", arrayOf(id, expected.toString())) != 1)
                throw LibraryFailure(LibraryError.STALE_REVISION)
            recordOperation(db, operationId, kind, id, expected, fingerprint, expected + 1)
            LibrarySchema.bumpVersion(db)
            requireFolder(id)
        }
    }

    private fun mutateItem(id: String, expected: Long, operationId: String, kind: String, argument: String?, values: (MediaItem) -> ContentValues): MediaItem {
        LibraryIds.requireValid(id)
        val fingerprint = fingerprint(kind, id, expected, argument)
        return transaction { db ->
            replay(operationId, fingerprint)?.let { return@transaction readItem(it.targetId) ?: throw LibraryFailure(LibraryError.NOT_FOUND) }
            val current = readItem(id) ?: throw LibraryFailure(LibraryError.NOT_FOUND)
            if (current.lifecycle != MediaLifecycle.READY) throw LibraryFailure(LibraryError.NOT_FOUND)
            checkRevision(current.revision, expected)
            val exclusive = leases.tryAcquireWrite(id, operationId) ?: run {
                leases.cancelWriteIntent(id, operationId)
                throw LibraryFailure(LibraryError.BUSY)
            }
            try {
                // Names and folder membership are logical; no media file is moved or rewritten.
                val update = values(current).apply { put("revision", expected + 1) }
                if (db.update("library_item", update, "item_id=? AND revision=? AND lifecycle='READY'", arrayOf(id, expected.toString())) != 1)
                    throw LibraryFailure(LibraryError.STALE_REVISION)
                recordOperation(db, operationId, kind, id, expected, fingerprint, expected + 1)
                LibrarySchema.bumpVersion(db)
                requireNotNull(readItem(id))
            } finally { exclusive.close() }
        }
    }

    private enum class DeleteAttempt { COMPLETE, WAITING, FAILED }
    private fun finishDelete(itemId: String, operationId: String): DeleteAttempt {
        if (!deleting.add(itemId)) return DeleteAttempt.WAITING
        try { return finishDeleteOwned(itemId, operationId) } finally { deleting.remove(itemId) }
    }
    private fun finishDeleteOwned(itemId: String, operationId: String): DeleteAttempt {
        val operation = operation(operationId) ?: throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        if (operation.targetId != itemId || operation.kind != "DELETE_ITEM") throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        if (operation.phase == "COMPLETE") return DeleteAttempt.COMPLETE
        val current = readItem(itemId) ?: throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        if (current.lifecycle != MediaLifecycle.DELETE_PENDING) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        val exclusive = leases.tryAcquireWrite(itemId, operationId) ?: return DeleteAttempt.WAITING
        try {
            val representation = representation(itemId)
            if (representation.state != "DELETE_PENDING" || representation.generation != current.representationGeneration)
                throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            files.remove(requireNotNull(representation.locator))
            transaction { db ->
                if (db.update("media_representation", ContentValues().apply {
                        put("state", "DELETED"); putNull("opaque_locator"); putNull("size_bytes"); putNull("sha256")
                    }, "representation_id=? AND state='DELETE_PENDING'", arrayOf(representation.id)) != 1)
                    throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
                if (db.update("library_item", ContentValues().apply {
                        put("lifecycle", "DELETED"); put("revision", current.revision + 1)
                        putNull("folder_id"); putNull("display_name"); putNull("name_key"); putNull("created_at")
                        putNull("date_kind"); putNull("duration_millis"); putNull("width"); putNull("height")
                    }, "item_id=? AND revision=? AND lifecycle='DELETE_PENDING'", arrayOf(itemId, current.revision.toString())) != 1)
                    throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
                if (db.update("library_operation", ContentValues().apply {
                        put("phase", "COMPLETE"); put("result_revision", current.revision + 1)
                    }, "operation_id=? AND phase='DELETE_PENDING'", arrayOf(operationId)) != 1)
                    throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
                LibrarySchema.bumpVersion(db)
            }
            return DeleteAttempt.COMPLETE
        } catch (_: IOException) { return DeleteAttempt.FAILED }
        catch (_: SQLiteException) { return DeleteAttempt.FAILED }
        catch (_: SecurityException) { return DeleteAttempt.FAILED }
        finally { exclusive.close() }
    }

    private fun recoverAfterRelease(itemId: String) = synchronized(lock) {
        if (isClosed()) return@synchronized
        try {
            val operationId = database().rawQuery("SELECT operation_id FROM library_operation WHERE target_id=? AND phase='DELETE_PENDING' LIMIT 1",
                arrayOf(itemId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (operationId != null) finishDelete(itemId, operationId)
        } catch (_: Exception) { /* Durable intent remains pending; explicit recovery exposes the failure count. */ }
    }

    private fun requireReady(ref: MediaReadRef): MediaItem {
        val item = readItem(ref.itemId) ?: throw LibraryFailure(LibraryError.NOT_FOUND)
        if (item.lifecycle != MediaLifecycle.READY) throw LibraryFailure(LibraryError.NOT_FOUND)
        if (item.revision != ref.revision || item.representationGeneration != ref.representationGeneration)
            throw LibraryFailure(LibraryError.STALE_REVISION)
        return item
    }
    private fun readItem(id: String): MediaItem? = database().rawQuery("$ITEM_SELECT WHERE i.item_id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) readItem(c) else null
    }
    private fun readItem(c: Cursor): MediaItem = MediaItem(c.getString(0), c.getLong(1), c.getLong(2),
        if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) "Deleted item" else c.getString(4),
        if (c.isNull(5)) 0 else c.getLong(5), if (c.isNull(6)) MediaDateKind.IMPORTED else MediaDateKind.valueOf(c.getString(6)),
        if (c.isNull(7)) 0 else c.getLong(7), if (c.isNull(8)) null else c.getLong(8),
        if (c.isNull(9)) null else c.getInt(9), if (c.isNull(10)) null else c.getInt(10), MediaLifecycle.valueOf(c.getString(11)))
    private fun readFolder(id: String): LibraryFolder? = database().rawQuery("$FOLDER_SELECT WHERE f.folder_id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) readFolder(c) else null
    }
    private fun readFolder(c: Cursor) = LibraryFolder(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getString(2),
        c.getString(3), c.getInt(4), c.getInt(5))
    private fun requireFolder(id: String): LibraryFolder {
        LibraryIds.requireValid(id)
        return readFolder(id) ?: throw LibraryFailure(LibraryError.NOT_FOUND)
    }
    private fun allFolders(): List<LibraryFolder> = database().rawQuery("$FOLDER_SELECT LIMIT 1001", null).use { c ->
        buildList { while (c.moveToNext()) add(readFolder(c)) }
    }.also { if (it.size > LibraryLimits.MAX_FOLDERS) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED) }
    private fun requireUniqueName(parentId: String?, name: String, excludingItem: String? = null, excludingFolder: String? = null) {
        val key = LibraryNames.key(name)
        fun collision(table: String, parent: String, id: String, excluded: String?, predicate: String = ""): Boolean {
            val args = mutableListOf<String>().apply { parentId?.let(::add); add(key); add(excluded ?: "") }
            return database().rawQuery("SELECT 1 FROM $table WHERE ${parentSelection(parent, parentId)} AND name_key=? AND $id<>? $predicate LIMIT 1",
                args.toTypedArray()).use { it.moveToFirst() }
        }
        if (collision("library_folder", "parent_id", "folder_id", excludingFolder) ||
            collision("library_item", "folder_id", "item_id", excludingItem, "AND lifecycle='READY'"))
            throw LibraryFailure(LibraryError.ALREADY_EXISTS)
    }
    private data class Representation(val id: String, val generation: Long, val locator: String?, val state: String,
        val sizeBytes: Long, val sha256: String?, val commitId: String?)
    private fun representation(itemId: String): Representation = database().rawQuery("""SELECT r.representation_id,r.generation,r.opaque_locator,
        r.state,r.size_bytes,r.sha256,i.origin_commit_id FROM library_item i JOIN media_representation r
        ON r.representation_id=i.representation_id AND r.item_id=i.item_id AND r.generation=i.generation
        WHERE i.item_id=? AND i.privacy_class='ORDINARY' AND r.kind='ORDINARY_FILE_V1'""", arrayOf(itemId)).use { c ->
        if (!c.moveToFirst()) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        Representation(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getString(2), c.getString(3),
            if (c.isNull(4)) 0 else c.getLong(4), if (c.isNull(5)) null else c.getString(5), if (c.isNull(6)) null else c.getString(6))
    }
    private data class Operation(val kind: String, val targetId: String, val fingerprint: String, val phase: String)
    private fun operation(id: String): Operation? = database().rawQuery(
        "SELECT kind,target_id,argument_fingerprint,phase FROM library_operation WHERE operation_id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) Operation(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null
    }
    private fun replay(operationId: String, fingerprint: String): Operation? {
        LibraryIds.requireValid(operationId)
        return operation(operationId)?.also { if (it.fingerprint != fingerprint) throw LibraryFailure(LibraryError.IDEMPOTENCY_CONFLICT) }
    }
    private fun recordOperation(db: SQLiteDatabase, operationId: String, kind: String, targetId: String, expected: Long,
        fingerprint: String, resultRevision: Long, phase: String = "COMPLETE") {
        db.insertOrThrow("library_operation", null, ContentValues().apply {
            put("operation_id", operationId); put("kind", kind); put("target_id", targetId); put("expected_revision", expected)
            put("argument_fingerprint", fingerprint); put("result_revision", resultRevision); put("phase", phase)
        })
    }
    private fun version(db: SQLiteDatabase): Long = db.rawQuery("SELECT revision FROM library_state WHERE singleton=1", null).use {
        if (!it.moveToFirst()) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        it.getLong(0)
    }
    private fun checkRevision(current: Long, expected: Long) {
        if (expected <= 0 || expected == Long.MAX_VALUE || current != expected) throw LibraryFailure(LibraryError.STALE_REVISION)
    }
    private fun fingerprint(kind: String, target: String?, expected: Long, vararg arguments: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in listOf("library-operation-v1", kind, target, expected.toString()) + arguments) {
            if (value == null) digest.update(ByteBuffer.allocate(4).putInt(-1).array()) else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private inline fun <T> guard(block: () -> T): T = synchronized(lock) {
        if (isClosed()) throw LibraryFailure(LibraryError.STORAGE_FAILURE)
        try { block() }
        catch (failure: LibraryFailure) { throw failure }
        catch (_: SQLiteException) { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
        catch (_: IOException) { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
        catch (_: SecurityException) { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
        catch (_: IllegalArgumentException) { throw LibraryFailure(LibraryError.RECOVERY_REQUIRED) }
    }
    private fun parentSelection(column: String, parent: String?): String = if (parent == null) "$column IS NULL" else "$column=?"
    private fun ContentValues.nullable(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }

    companion object {
        internal const val DELETE_BATCH_SQL = "SELECT target_id,operation_id FROM library_operation WHERE phase='DELETE_PENDING' AND operation_id>? ORDER BY operation_id LIMIT ?"
        private const val ITEM_SELECT = """SELECT i.item_id,i.revision,i.generation,i.folder_id,i.display_name,i.created_at,
            i.date_kind,r.size_bytes,i.duration_millis,i.width,i.height,i.lifecycle FROM library_item i
            JOIN media_representation r ON r.representation_id=i.representation_id AND r.item_id=i.item_id AND r.generation=i.generation"""
        private const val FOLDER_SELECT = """SELECT f.folder_id,f.revision,f.parent_id,f.display_name,
            (SELECT COUNT(*) FROM library_item i WHERE i.folder_id=f.folder_id AND i.lifecycle<>'DELETED'),
            (SELECT COUNT(*) FROM library_folder c WHERE c.parent_id=f.folder_id) FROM library_folder f"""
    }
}
