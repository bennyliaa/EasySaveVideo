package com.easysavevideo.app.library.destination

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.library.LibraryIds
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.destination.*
import java.util.UUID

/** The Save storage owner supplies its one database, publication lock and lifetime. */
internal class AndroidOrdinaryDestinationJournal(
    private val database: () -> SQLiteDatabase,
    private val lock: Any,
    private val closed: () -> Boolean,
) : OrdinaryDestinationJournal {
    override fun preference(): OrdinaryDestinationPreference = selection().preference
    override fun selection(): OrdinaryDestinationSelectionSnapshot = guard {
        database().rawQuery("""SELECT CASE WHEN length(preference)<=32 THEN preference END,
            CASE WHEN selected_folder_id IS NULL OR length(selected_folder_id) BETWEEN 1 AND 128 THEN 1 ELSE 0 END,
            CASE WHEN length(selected_folder_id) BETWEEN 1 AND 128 THEN selected_folder_id END,
            CASE WHEN typeof(selection_revision)='integer' AND selection_revision>=0 THEN selection_revision END
            FROM ordinary_destination_state WHERE singleton=1""", null).use {
            if (!it.moveToFirst() || it.isNull(0) || it.getInt(1) != 1 || it.isNull(3)) invalid()
            val selected = OrdinaryDestinationRows.selection(it.getString(0), if (it.isNull(2)) null else it.getString(2), it.getLong(3))
            selected.folderGrantId?.let { id -> requireActiveFolder(database(), id) }
            selected
        }
    }
    override fun selectedFolderId(): String? = selection().folderGrantId
    override fun setPreference(value: OrdinaryDestinationPreference) = guard { transaction { db ->
        // Changing the default preserves a remembered folder, but never invents one.
        if (value == OrdinaryDestinationPreference.FOLDER_COPY)
            requireActiveFolder(db, selectedFolderId() ?: invalid())
        if (db.update("ordinary_destination_state", ContentValues().apply { put("preference", value.name) }, "singleton=1", null) != 1) invalid()
        Unit
    } }
    override fun setFolderPreference(folderGrantId: String) = guard { transaction { db ->
        requireActiveFolder(db, folderGrantId)
        if (db.update("ordinary_destination_state", ContentValues().apply {
            put("preference", OrdinaryDestinationPreference.FOLDER_COPY.name); put("selected_folder_id", folderGrantId)
        }, "singleton=1", null) != 1) invalid()
        Unit
    } }
    override fun admit(creationId: String, choice: OrdinaryDestinationChoice): OrdinaryDestinationAdmission = admit(creationId, choice, null)
    override fun admit(creationId: String, choice: OrdinaryDestinationChoice, folderGrantId: String?): OrdinaryDestinationAdmission = guard { transaction { db ->
        LibraryIds.requireValid(creationId)
        if ((choice == OrdinaryDestinationChoice.FOLDER_COPY) != (folderGrantId != null)) invalid()
        folderGrantId?.let(LibraryIds::requireValid)
        get(creationId)?.let { prior ->
            if (prior.choice != choice || prior.folderGrantId != folderGrantId)
                throw OrdinaryDestinationFailure(OrdinaryDestinationError.IDEMPOTENCY_CONFLICT)
            // Retirement permits existing work; observed authority loss irreversibly blocks reuse.
            prior.folderGrantId?.let { if ((FolderGrantRows.get(db, it) ?: invalid()).invalidated) invalid() }
            return@transaction prior
        }
        folderGrantId?.let { requireActiveFolder(db, it) }
        // Snapshot must precede actual creation. Never rebind a historical item or retained session.
        requireNotCreated(db, creationId)
        if (publicationCreation(db, creationId) != null) throw OrdinaryDestinationFailure(OrdinaryDestinationError.IDEMPOTENCY_CONFLICT)
        val admission = OrdinaryDestinationAdmission(creationId, choice, UUID.randomUUID().toString(), UUID.randomUUID().toString(), folderGrantId = folderGrantId)
        db.insertOrThrow("ordinary_destination", null, ContentValues().apply {
            put("creation_id", admission.creationId); put("choice", admission.choice.name)
            put("operation_id", admission.operationId); put("grant_id", admission.grantId)
            put("folder_grant_id", admission.folderGrantId)
        })
        insertLink(db, creationId, creationId)
        admission
    } }
    /** Resolve the immutable admission, never mutable settings. Retired rows remain resolvable. */
    fun folderGrantForOperation(operationId: String): String? = guard {
        LibraryIds.requireValid(operationId)
        val creationId = database().rawQuery("SELECT CASE WHEN length(creation_id) BETWEEN 1 AND 128 THEN creation_id END FROM ordinary_destination WHERE operation_id=?", arrayOf(operationId)).use {
            if (!it.moveToFirst()) null else { if (it.isNull(0)) invalid(); it.getString(0).also(LibraryIds::requireValid) }
        }
        creationId?.let { id -> (get(id) ?: invalid()).folderGrantId }
    }
    override fun get(creationId: String): OrdinaryDestinationAdmission? = guard {
        LibraryIds.requireValid(creationId)
        OrdinaryDestinationRows.get(database(), creationId)
    }
    override fun linkPublication(publicationId: String, creationId: String) = guard { transaction { db ->
        LibraryIds.requireValid(publicationId); LibraryIds.requireValid(creationId)
        publicationCreation(db, publicationId)?.let { prior ->
            if (prior != creationId) throw OrdinaryDestinationFailure(OrdinaryDestinationError.IDEMPOTENCY_CONFLICT)
            if (get(creationId) == null) invalid()
            return@transaction
        }
        // Legacy retained recordings have no admission and remain private-library-only.
        val admission = get(creationId) ?: return@transaction
        if (admission.source != null) throw OrdinaryDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH)
        requireNotCreated(db, publicationId)
        insertLink(db, publicationId, creationId)
    } }
    override fun pendingCopy(limit: Int): List<OrdinaryDestinationAdmission> = guard { transaction { db ->
        require(limit in 1..OrdinaryDestinationLimits.MAX_BATCH)
        val cursor = db.rawQuery("SELECT CASE WHEN length(copy_cursor)<=128 THEN copy_cursor END FROM ordinary_destination_state WHERE singleton=1", null).use {
            if (!it.moveToFirst() || it.isNull(0)) invalid()
            it.getString(0).also { value -> if (value.isNotEmpty()) LibraryIds.requireValid(value) }
        }
        fun page(after: String): List<String> = db.rawQuery(PENDING_COPY_SQL, arrayOf(after, limit.toString())).use { c -> buildList {
            while (c.moveToNext()) { if (c.isNull(0)) invalid(); add(c.getString(0)) }
        } }
        val ids = page(cursor).ifEmpty { page("") }
        if (ids.isNotEmpty()) db.execSQL("UPDATE ordinary_destination_state SET copy_cursor=? WHERE singleton=1", arrayOf(ids.last()))
        ids.map { get(it) ?: invalid() }
    } }
    override fun recent(limit: Int): List<OrdinaryDestinationAdmission> = guard {
        require(limit in 1..OrdinaryDestinationLimits.MAX_BATCH)
        val ids = database().rawQuery("SELECT CASE WHEN length(creation_id) BETWEEN 1 AND 128 THEN creation_id END FROM ordinary_destination ORDER BY rowid DESC LIMIT ?", arrayOf(limit.toString())).use { c -> buildList {
            while (c.moveToNext()) { if (c.isNull(0)) invalid(); add(c.getString(0)) }
        } }
        ids.map { get(it) ?: invalid() }
    }
    /** This must be part of the caller's verified private publication transaction, not a callback. */
    internal fun bindVerifiedPublication(db: SQLiteDatabase, publicationId: String, source: MediaReadRef) = guard {
        check(db === database() && db.inTransaction())
        val creationId = publicationCreation(db, publicationId) ?: return@guard
        val prior = OrdinaryDestinationRows.get(db, creationId) ?: invalid()
        val bound = prior.bindVerifiedSource(source)
        if (prior == bound) return@guard
        val readable = db.rawQuery("""SELECT 1 FROM library_item i JOIN media_representation r ON r.representation_id=i.representation_id AND r.item_id=i.item_id AND r.generation=i.generation
            WHERE i.item_id=? AND i.revision=? AND i.generation=? AND i.lifecycle='READY' AND i.privacy_class='ORDINARY' AND r.state='READY' AND r.kind='ORDINARY_FILE_V1'""",
            arrayOf(source.itemId, source.revision.toString(), source.representationGeneration.toString())).use { it.moveToFirst() }
        if (!readable) throw OrdinaryDestinationFailure(OrdinaryDestinationError.SOURCE_MISMATCH)
        if (db.update("ordinary_destination", ContentValues().apply {
            put("source_item", source.itemId); put("source_revision", source.revision); put("source_generation", source.representationGeneration)
        }, "creation_id=? AND source_item IS NULL AND source_revision IS NULL AND source_generation IS NULL", arrayOf(creationId)) != 1) invalid()
    }
    private fun requireActiveFolder(db: SQLiteDatabase, id: String) {
        LibraryIds.requireValid(id)
        val row = FolderGrantRows.get(db, id) ?: invalid()
        if (row.retired || row.invalidated) invalid()
    }
    private fun insertLink(db: SQLiteDatabase, publicationId: String, creationId: String) {
        db.insertOrThrow("ordinary_destination_publication", null, ContentValues().apply { put("publication_id", publicationId); put("creation_id", creationId) })
    }
    private fun publicationCreation(db: SQLiteDatabase, publicationId: String): String? {
        LibraryIds.requireValid(publicationId)
        return db.rawQuery("SELECT CASE WHEN length(creation_id) BETWEEN 1 AND 128 THEN creation_id END FROM ordinary_destination_publication WHERE publication_id=?", arrayOf(publicationId)).use {
            if (!it.moveToFirst()) null else { if (it.isNull(0)) invalid(); it.getString(0).also(LibraryIds::requireValid) }
        }
    }
    private fun requireNotCreated(db: SQLiteDatabase, id: String) {
        val exists = db.rawQuery("""SELECT 1 FROM save_tasks WHERE task_id=? UNION ALL SELECT 1 FROM edit_job WHERE operation_id=?
            UNION ALL SELECT 1 FROM recording_session WHERE session_id=? LIMIT 1""", arrayOf(id, id, id)).use { it.moveToFirst() }
        if (exists) throw OrdinaryDestinationFailure(OrdinaryDestinationError.ALREADY_CREATED)
    }
    private inline fun <T> guard(block: () -> T): T = synchronized(lock) {
        if (closed()) throw OrdinaryDestinationFailure(OrdinaryDestinationError.STORAGE_FAILURE)
        try { block() } catch (failure: OrdinaryDestinationFailure) { throw failure }
        catch (_: Exception) { throw OrdinaryDestinationFailure(OrdinaryDestinationError.STORAGE_FAILURE) }
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    companion object {
        // Existing ambiguous/error/terminal exports are owned exclusively by ExportCoordinator recovery.
        const val PENDING_COPY_SQL = "SELECT CASE WHEN length(d.creation_id) BETWEEN 1 AND 128 THEN d.creation_id END FROM ordinary_destination d LEFT JOIN library_export e ON e.operation_id=d.operation_id WHERE d.choice IN ('ALBUM_COPY','FOLDER_COPY') AND d.source_item IS NOT NULL AND (e.operation_id IS NULL OR e.phase='PREPARED') AND d.creation_id>? ORDER BY d.creation_id LIMIT ?"
        private fun invalid(): Nothing = throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
    }
}

/** Small validated row decoder; unknown enum values never become a permissive default. */
internal object OrdinaryDestinationRows {
    fun preference(value: String): OrdinaryDestinationPreference = decode { OrdinaryDestinationPreference.valueOf(value) }
    fun selection(value: String, folderGrantId: String?, revision: Long = 0): OrdinaryDestinationSelectionSnapshot = decode {
        OrdinaryDestinationSelectionSnapshot(preference(value), folderGrantId, revision)
    }
    fun admission(creationId: String, choice: String, operationId: String, grantId: String,
        itemId: String?, revision: Long?, generation: Long?, folderGrantId: String? = null): OrdinaryDestinationAdmission = decode {
        require((itemId == null && revision == null && generation == null) || (itemId != null && revision != null && generation != null))
        OrdinaryDestinationAdmission(creationId, OrdinaryDestinationChoice.valueOf(choice), operationId, grantId,
            itemId?.let { MediaReadRef(it, requireNotNull(revision), requireNotNull(generation)) }, folderGrantId)
    }
    fun get(db: SQLiteDatabase, creationId: String): OrdinaryDestinationAdmission? = db.rawQuery("""SELECT
        CASE WHEN length(choice)<=32 THEN choice END,CASE WHEN length(operation_id) BETWEEN 1 AND 128 THEN operation_id END,
        CASE WHEN length(grant_id) BETWEEN 1 AND 128 THEN grant_id END,
        CASE WHEN source_item IS NULL OR length(source_item) BETWEEN 1 AND 128 THEN 1 ELSE 0 END,
        CASE WHEN length(source_item) BETWEEN 1 AND 128 THEN source_item END,source_revision,source_generation,
        CASE WHEN folder_grant_id IS NULL OR length(folder_grant_id) BETWEEN 1 AND 128 THEN 1 ELSE 0 END,
        CASE WHEN length(folder_grant_id) BETWEEN 1 AND 128 THEN folder_grant_id END,
        CASE WHEN source_revision IS NULL OR (typeof(source_revision)='integer' AND source_revision>0) THEN 1 ELSE 0 END,
        CASE WHEN source_generation IS NULL OR (typeof(source_generation)='integer' AND source_generation>0) THEN 1 ELSE 0 END
        FROM ordinary_destination WHERE creation_id=?""", arrayOf(creationId)).use {
        if (!it.moveToFirst()) null else {
            if (it.isNull(0) || it.isNull(1) || it.isNull(2) || it.getInt(3) != 1 || it.getInt(7) != 1 || it.getInt(9) != 1 || it.getInt(10) != 1) throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
            admission(creationId, it.getString(0), it.getString(1), it.getString(2), if (it.isNull(4)) null else it.getString(4),
                if (it.isNull(5)) null else it.getLong(5), if (it.isNull(6)) null else it.getLong(6), if (it.isNull(8)) null else it.getString(8))
        }
    }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (_: Exception) { throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD) }
}
