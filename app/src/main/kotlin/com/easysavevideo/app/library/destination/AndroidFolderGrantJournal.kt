package com.easysavevideo.app.library.destination

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.library.LibraryIds
import com.easysavevideo.library.destination.OrdinaryDestinationError
import com.easysavevideo.library.destination.OrdinaryDestinationFailure
import java.util.UUID

/** Private lookup data, never an Android permission or authority. Never expose URI diagnostics. */
internal data class FolderGrantRow(val id: String, val uri: String, val flags: Int, val retired: Boolean, val invalidated: Boolean = false) {
    init {
        LibraryIds.requireValid(id)
        require(uri.length in 11..FolderGrantRows.MAX_URI_LENGTH && uri.startsWith("content://"))
        require(uri.none { it.isISOControl() } && uri.toByteArray(Charsets.UTF_8).size <= FolderGrantRows.MAX_URI_LENGTH)
        require(flags in 1..3) // Android read/write bits only; no persistable/prefix flags stored.
    }
    override fun toString() = "FolderGrantRow(retired=$retired, invalidated=$invalidated, [redacted])"
}

/** Shares the storage owner's lock, database, and lifetime. No resolver or OS permission mutation.
 * Active valid exact URI+flags selections reuse their opaque ID; unavailable identities never reactivate.
 * Observed missing Android authority invalidates every matching URI identity irreversibly. A later
 * regrant gets a new ID and cannot resurrect old work. Retirement alone only stops new admissions.
 * All rows and OS grants remain retained for existing operations and cleanup. Forgetting only stops
 * future admissions. The app must not release a persisted OS grant on selection change or retirement;
 * permission revocation remains an explicit system-settings action, which may block old work.
 */
internal class AndroidFolderGrantJournal(
    private val database: () -> SQLiteDatabase,
    private val lock: Any,
    private val closed: () -> Boolean,
) {
    /** Read-only preflight, before asking Android to retain a new permission. issue rechecks atomically. */
    fun availableCapacity(uri: String, flags: Int): Boolean = guard {
        FolderGrantRows.decode("preflight", uri, flags, 0)
        findActive(database(), uri, flags) != null || count(database()) < MAX_ROWS
    }
    fun issue(uri: String, flags: Int): FolderGrantRow = guard { transaction { db ->
        val candidate = FolderGrantRows.decode(UUID.randomUUID().toString(), uri, flags, 0)
        findActive(db, uri, flags)?.let { return@transaction it }
        if (count(db) >= MAX_ROWS) unavailable()
        db.insertOrThrow("ordinary_folder_grant", null, ContentValues().apply {
            put("folder_grant_id", candidate.id); put("tree_uri", candidate.uri); put("flags", candidate.flags); put("retired", 0); put("invalidated", 0)
        })
        candidate
    } }
    fun get(id: String): FolderGrantRow? = guard { LibraryIds.requireValid(id); FolderGrantRows.get(database(), id) }
    fun current(): FolderGrantRow? = guard {
        val id = database().rawQuery("""SELECT CASE WHEN selected_folder_id IS NULL OR length(selected_folder_id) BETWEEN 1 AND 128 THEN 1 ELSE 0 END,
            CASE WHEN length(selected_folder_id) BETWEEN 1 AND 128 THEN selected_folder_id END FROM ordinary_destination_state WHERE singleton=1""", null).use {
            if (!it.moveToFirst() || it.getInt(0) != 1) invalid()
            if (it.isNull(1)) null else it.getString(1).also(LibraryIds::requireValid)
        }
        id?.let { selected -> (FolderGrantRows.get(database(), selected) ?: invalid()).also { if (it.retired || it.invalidated) invalid() } }
    }
    /** The schema atomically clears a matching remembered/default selection on retirement. */
    fun retire(id: String) = guard { transaction { db ->
        LibraryIds.requireValid(id)
        val row = FolderGrantRows.get(db, id) ?: invalid()
        if (!row.retired && db.update("ordinary_folder_grant", ContentValues().apply { put("retired", 1) },
                "folder_grant_id=? AND retired=0", arrayOf(id)) != 1) invalid()
        Unit
    } }
    /** Record an observed authority gap before retaking any same-URI grant. This is not OS revocation.
     * Existing operation references survive for status/cleanup, but must never use a later grant.
     */
    fun invalidateUri(uri: String) = guard { transaction { db ->
        FolderGrantRows.decode("invalidate", uri, 3, 0)
        db.update("ordinary_folder_grant", ContentValues().apply { put("invalidated", 1) },
            "tree_uri=? AND invalidated=0", arrayOf(uri))
        Unit
    } }
    fun forgetCurrent() = guard { transaction { db ->
        current()?.let { retire(it.id) }
        Unit
    } }
    private fun findActive(db: SQLiteDatabase, uri: String, flags: Int): FolderGrantRow? =
        db.rawQuery("SELECT CASE WHEN length(folder_grant_id) BETWEEN 1 AND 128 THEN folder_grant_id END FROM ordinary_folder_grant WHERE tree_uri=? AND flags=? AND retired=0 AND invalidated=0", arrayOf(uri, flags.toString())).use {
            if (!it.moveToFirst()) null else {
                if (it.isNull(0)) invalid()
                FolderGrantRows.get(db, it.getString(0)) ?: invalid()
            }
        }
    private fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM ordinary_folder_grant", null).use {
        if (!it.moveToFirst()) invalid()
        val count = it.getLong(0)
        if (count !in 0..MAX_ROWS.toLong()) invalid()
        count.toInt()
    }
    private inline fun <T> guard(block: () -> T): T = synchronized(lock) {
        if (closed()) unavailable()
        try { block() } catch (failure: OrdinaryDestinationFailure) { throw failure }
        catch (_: Exception) { unavailable() }
    }
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = database(); db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    companion object {
        const val MAX_ROWS = 128
        private fun invalid(): Nothing = throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
        private fun unavailable(): Nothing = throw OrdinaryDestinationFailure(OrdinaryDestinationError.STORAGE_FAILURE)
    }
}

internal object FolderGrantRows {
    const val MAX_URI_LENGTH = 8192
    fun decode(id: String, uri: String, flags: Int, retired: Int, invalidated: Int = 0): FolderGrantRow = try {
        require(retired in 0..1 && invalidated in 0..1)
        FolderGrantRow(id, uri, flags, retired == 1, invalidated == 1)
    } catch (_: Exception) { invalid() }
    fun get(db: SQLiteDatabase, id: String): FolderGrantRow? = db.rawQuery("""SELECT
        CASE WHEN typeof(tree_uri)='text' AND length(tree_uri) BETWEEN 11 AND 8192 AND length(CAST(tree_uri AS BLOB))<=8192 THEN tree_uri END,
        CASE WHEN typeof(flags)='integer' AND flags IN (1,2,3) THEN flags END,
        CASE WHEN typeof(retired)='integer' AND retired IN (0,1) THEN retired END,
        CASE WHEN typeof(invalidated)='integer' AND invalidated IN (0,1) THEN invalidated END
        FROM ordinary_folder_grant WHERE folder_grant_id=?""", arrayOf(id)).use {
        if (!it.moveToFirst()) null else {
            if (it.isNull(0) || it.isNull(1) || it.isNull(2) || it.isNull(3)) invalid()
            decode(id, it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3))
        }
    }
    private fun invalid(): Nothing = throw OrdinaryDestinationFailure(OrdinaryDestinationError.INVALID_RECORD)
}
