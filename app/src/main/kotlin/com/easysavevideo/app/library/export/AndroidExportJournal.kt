package com.easysavevideo.app.library.export

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.library.LibraryIds
import com.easysavevideo.library.export.*
import java.util.UUID

/** Constructed only by AndroidSaveStorage, sharing its sole DB, directory lease and lock. */
internal class AndroidExportJournal(
    private val database: () -> SQLiteDatabase,
    private val lock: Any,
    private val closed: () -> Boolean,
    private val requireSourceAdmission: (com.easysavevideo.library.MediaReadRef) -> Unit = {},
) : ExportJournal {
    data class Target(val operationId: String, val kind: ExportKind, val grantId: String, val nonce: String, val uri: String?, val flags: Int) {
        override fun toString() = "ExportTarget(kind=$kind, [redacted])"
    }
    override fun create(request: ExportRequest): ExportRecord = guard { transaction { db ->
        requireSourceAdmission(request.source)
        get(request.operationId)?.let {
            if (it.request != request) throw ExportFailure(ExportError.IDEMPOTENCY_CONFLICT)
            return@transaction it
        }
        db.rawQuery("SELECT COUNT(*) FROM library_export WHERE $ACTIVE_WHERE", null).use {
            it.moveToFirst()
            if (it.getLong(0) >= ExportLimits.MAX_ACTIVE) throw ExportFailure(ExportError.CAPACITY)
        }
        ExportRecord(request).also { db.insertOrThrow("library_export", null, values(it)) }
    } }
    override fun get(operationId: String): ExportRecord? = guard {
        LibraryIds.requireValid(operationId)
        database().rawQuery("SELECT fingerprint,phase,cancelled,record FROM library_export WHERE operation_id=? AND length(record) BETWEEN 8 AND 4096 AND length(fingerprint)=64 AND length(phase)<=32", arrayOf(operationId)).use {
            if (!it.moveToFirst()) null else ExportRecordCodec.decode(it.getBlob(3)).also { record ->
                require(record.operationId == operationId && record.fingerprint == it.getString(0) && record.phase.name == it.getString(1) && record.cancelRequested == (it.getInt(2) == 1))
            }
        }
    }
    override fun put(record: ExportRecord) = guard {
        val prior = get(record.operationId) ?: throw ExportFailure(ExportError.INVALID_STATE)
        if (prior.request != record.request || (prior.cancelRequested && !record.cancelRequested)) throw ExportFailure(ExportError.IDEMPOTENCY_CONFLICT)
        require(database().update("library_export", values(record), "operation_id=?", arrayOf(record.operationId)) == 1)
        Unit
    }
    override fun unfinished(limit: Int): List<ExportRecord> = guard {
        require(limit in 1..ExportLimits.MAX_RECOVERY_BATCH)
        transaction { db ->
            val cursor = db.rawQuery("SELECT recovery_cursor FROM library_export_state WHERE singleton=1", null).use { require(it.moveToFirst()); it.getString(0) }
            fun page(after: String): List<String> = db.rawQuery(RECOVERY_SQL, arrayOf(after, limit.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val ids = page(cursor).ifEmpty { page("") }
            if (ids.isNotEmpty()) db.execSQL("UPDATE library_export_state SET recovery_cursor=? WHERE singleton=1", arrayOf(ids.last()))
            ids.map { get(it) ?: throw ExportFailure(ExportError.RECOVERY_REQUIRED) }
        }
    }
    data class Counts(val unfinished: Long)
    fun counts(): Counts = guard {
        database().rawQuery("SELECT COUNT(*) FROM library_export WHERE $ACTIVE_WHERE", null).use {
            require(it.moveToFirst()); Counts(it.getLong(0)).also { count -> require(count.unfinished >= 0) }
        }
    }
    data class ReviewEntry(val record: ExportRecord, val order: Long)
    data class ReviewPage(val records: List<ReviewEntry>, val total: Long, val page: Long)
    /** Every unresolved SAF warning remains discoverable, including across process death. */
    fun listUnverified(page: Long): ReviewPage = guard { transaction { db ->
        require(page >= 0)
        val total = db.rawQuery("SELECT COUNT(*) FROM library_export e JOIN library_export_target t ON t.operation_id=e.operation_id WHERE $REVIEW_WHERE", null).use {
            require(it.moveToFirst()); it.getLong(0).also { count -> require(count >= 0) }
        }
        val boundedPage = minOf(page, (total - 1).coerceAtLeast(0L) / REVIEW_PAGE_SIZE)
        val ids = db.rawQuery("SELECT e.operation_id,e.rowid FROM library_export e JOIN library_export_target t ON t.operation_id=e.operation_id WHERE $REVIEW_WHERE ORDER BY e.operation_id LIMIT ? OFFSET ?",
            arrayOf(REVIEW_PAGE_SIZE.toString(), (boundedPage * REVIEW_PAGE_SIZE).toString())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } }
        ReviewPage(ids.map { (id, order) -> ReviewEntry(get(id) ?: throw ExportFailure(ExportError.RECOVERY_REQUIRED), order) }, total, boundedPage)
    } }
    fun reserve(request: ExportRequest): Target = guard { transaction { db ->
        require(request.destination.kind != ExportKind.SHARE_COPY)
        val record = get(request.operationId) ?: throw ExportFailure(ExportError.INVALID_STATE)
        require(record.request == request)
        target(request.operationId)?.let {
            require(it.kind == request.destination.kind && it.grantId == request.destination.grantId)
            return@transaction it
        }
        Target(request.operationId, request.destination.kind, request.destination.grantId, UUID.randomUUID().toString(), null, 0).also {
            db.insertOrThrow("library_export_target", null, ContentValues().apply {
                put("operation_id", it.operationId); put("kind", it.kind.name); put("grant_id", it.grantId); put("nonce", it.nonce); putNull("uri"); put("grant_flags", 0)
            })
        }
    } }
    fun target(operationId: String): Target? = guard {
        LibraryIds.requireValid(operationId)
        database().rawQuery("""SELECT kind,grant_id,nonce,uri,grant_flags FROM library_export_target WHERE operation_id=?
            AND length(kind)<=16 AND length(grant_id) BETWEEN 1 AND 128 AND length(nonce)=36 AND (uri IS NULL OR length(CAST(uri AS BLOB))<=8192)""", arrayOf(operationId)).use {
            if (!it.moveToFirst()) null else Target(operationId, ExportKind.valueOf(it.getString(0)), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getString(3), it.getInt(4))
        }
    }
    /** Atomic adoption of the exact document created by this operation's outstanding picker. */
    fun bindSafOutput(request: ExportRequest, uri: String, flags: Int): Target = guard { transaction {
        val before = get(request.operationId) ?: throw ExportFailure(ExportError.INVALID_STATE)
        require(before.request == request && before.phase in setOf(ExportPhase.PREPARED, ExportPhase.CREATE_INTENT) && request.destination.kind == ExportKind.SAF)
        val target = bind(request.operationId, request.destination.grantId, uri, flags)
        put(before.copy(phase = ExportPhase.PREPARED, output = ExportOutput(request.operationId, ExportKind.SAF)))
        target
    } }
    fun bind(operationId: String, grantId: String, uri: String, flags: Int): Target = guard {
        require(uri.toByteArray(Charsets.UTF_8).size in 1..8192 && flags in 0..3)
        val target = target(operationId) ?: throw ExportFailure(ExportError.INVALID_STATE)
        require(target.grantId == grantId && (target.uri == null || target.uri == uri))
        require(database().rawQuery("SELECT 1 FROM library_export_target WHERE uri=? AND operation_id<>? LIMIT 1",arrayOf(uri,operationId)).use{!it.moveToFirst()})
        if (target.uri == null) require(database().update("library_export_target", ContentValues().apply { put("uri", uri); put("grant_flags", flags) }, "operation_id=? AND uri IS NULL", arrayOf(operationId)) == 1)
        target.copy(uri = uri, flags = if (target.uri == null) flags else target.flags)
    }
    private fun values(record: ExportRecord) = ContentValues().apply {
        put("operation_id", record.operationId); put("fingerprint", record.fingerprint); put("phase", record.phase.name)
        put("cancelled", if (record.cancelRequested) 1 else 0); put("record", ExportRecordCodec.encode(record))
    }
    private inline fun <T> guard(block: () -> T): T = synchronized(lock) {
        if (closed()) throw ExportFailure(ExportError.IO)
        try { block() } catch (e: ExportFailure) { throw e } catch (_: Exception) { throw ExportFailure(ExportError.IO) }
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    companion object {
        const val ACTIVE_WHERE = "phase IN ('PREPARED','CREATE_INTENT','COPYING','VERIFYING','PUBLISH_INTENT','CLEANUP_PENDING','RECONCILIATION_REQUIRED')"
        const val REVIEW_PAGE_SIZE = 32
        const val REVIEW_WHERE = "t.kind='SAF' AND e.phase IN ('COPIED_UNVERIFIED','CLEANUP_PENDING','RECONCILIATION_REQUIRED')"
        const val RECOVERY_SQL = "SELECT operation_id FROM library_export WHERE phase NOT IN ('SUCCEEDED','COPIED_UNVERIFIED','FAILED','CANCELLED') AND operation_id>? ORDER BY operation_id LIMIT ?"
    }
}
