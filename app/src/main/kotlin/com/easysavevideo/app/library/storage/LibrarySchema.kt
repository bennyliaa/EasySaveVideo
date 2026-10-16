package com.easysavevideo.app.library.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.app.save.storage.SaveRecordCodec
import com.easysavevideo.library.LibraryNames
import com.easysavevideo.save.MediaCommitReceipt
import com.easysavevideo.save.SaveRecord
import java.util.UUID

/** Additive v1 -> v2 migration, executed inside SQLiteOpenHelper's version transaction. */
internal object LibrarySchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE library_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL CHECK(revision>=0),delete_cursor TEXT NOT NULL DEFAULT '' CHECK(length(delete_cursor)<=128))""")
        db.execSQL("INSERT INTO library_state(singleton,revision) VALUES(1,0)")
        db.execSQL("""CREATE TABLE library_folder(folder_id TEXT PRIMARY KEY NOT NULL CHECK(length(folder_id) BETWEEN 1 AND 128),parent_id TEXT CHECK(parent_id IS NULL OR length(parent_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),
            revision INTEGER NOT NULL CHECK(revision>0),display_name TEXT NOT NULL CHECK(length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),name_key TEXT NOT NULL CHECK(length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024))""")
        db.execSQL("CREATE UNIQUE INDEX library_folder_sibling ON library_folder(IFNULL(parent_id,''),name_key)")
        db.execSQL("""CREATE TABLE library_item(item_id TEXT PRIMARY KEY NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),origin_commit_id TEXT UNIQUE NOT NULL CHECK(length(origin_commit_id) BETWEEN 1 AND 128) REFERENCES save_tasks(commit_id),
            revision INTEGER NOT NULL CHECK(revision>0),lifecycle TEXT NOT NULL CHECK(lifecycle IN ('READY','DELETE_PENDING','DELETED')),
            privacy_class TEXT NOT NULL CHECK(privacy_class='ORDINARY'),folder_id TEXT CHECK(folder_id IS NULL OR length(folder_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),
            representation_id TEXT UNIQUE NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128) REFERENCES media_representation(representation_id) DEFERRABLE INITIALLY DEFERRED,
            generation INTEGER NOT NULL CHECK(generation>0),display_name TEXT CHECK(display_name IS NULL OR length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),
            name_key TEXT CHECK(name_key IS NULL OR length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024),created_at INTEGER CHECK(created_at IS NULL OR created_at>=0),
            date_kind TEXT CHECK(date_kind IN ('SAVED','IMPORTED')),duration_millis INTEGER CHECK(duration_millis IS NULL OR duration_millis>0),width INTEGER CHECK(width IS NULL OR width>0),height INTEGER CHECK(height IS NULL OR height>0),
            CHECK((width IS NULL)=(height IS NULL)),
            CHECK(lifecycle='DELETED' OR (display_name IS NOT NULL AND name_key IS NOT NULL AND created_at IS NOT NULL AND created_at>=0 AND date_kind IS NOT NULL)))""")
        db.execSQL("CREATE UNIQUE INDEX library_item_sibling ON library_item(IFNULL(folder_id,''),name_key) WHERE lifecycle='READY'")
        db.execSQL("CREATE INDEX library_item_page ON library_item(folder_id,lifecycle,created_at,item_id)")
        db.execSQL("""CREATE TABLE media_representation(representation_id TEXT PRIMARY KEY NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128),item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128) REFERENCES library_item(item_id),
            generation INTEGER NOT NULL CHECK(generation>0),kind TEXT NOT NULL CHECK(kind='ORDINARY_FILE_V1'),
            opaque_locator TEXT UNIQUE CHECK(opaque_locator IS NULL OR length(opaque_locator)=36),state TEXT NOT NULL CHECK(state IN ('READY','DELETE_PENDING','DELETED')),
            size_bytes INTEGER,sha256 TEXT CHECK(sha256 IS NULL OR (length(sha256)=64 AND sha256 NOT GLOB '*[^a-f0-9]*')),UNIQUE(item_id,generation),
            CHECK(state='DELETED' OR (opaque_locator IS NOT NULL AND size_bytes IS NOT NULL AND sha256 IS NOT NULL AND size_bytes>0 AND length(sha256)=64)))""")
        db.execSQL("""CREATE TABLE library_operation(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 32),target_id TEXT NOT NULL CHECK(length(target_id) BETWEEN 1 AND 128),
            expected_revision INTEGER NOT NULL,argument_fingerprint TEXT NOT NULL CHECK(length(argument_fingerprint)=64),
            result_revision INTEGER NOT NULL CHECK(result_revision>0),phase TEXT NOT NULL CHECK(phase IN ('DELETE_PENDING','COMPLETE')))""")
        db.execSQL("CREATE INDEX library_delete_recovery ON library_operation(phase,operation_id)")
        createVaultMoveDisposition(db)
    }

    /** Additive v9 -> v10. Durable ordinary admission fence survives every process restart. */
    fun createVaultMoveDisposition(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE library_vault_move(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 64),
            item_id TEXT NOT NULL REFERENCES library_item(item_id),source_revision INTEGER NOT NULL CHECK(source_revision>0),
            generation INTEGER NOT NULL CHECK(generation>0),phase TEXT NOT NULL CHECK(phase IN ('RESERVED','DELETE_PENDING','DELETED','CANCELLED')))""")
        db.execSQL("CREATE UNIQUE INDEX library_vault_move_active ON library_vault_move(item_id,generation) WHERE phase<>'CANCELLED'")
    }

    fun backfill(db: SQLiteDatabase, importedAtMillis: Long, mediaFiles: OrdinaryMediaFiles) {
        // Page by rowid and inspect length before fetching a blob. No unbounded records list or CursorWindow.
        var after = 0L
        while (true) {
            val batch = db.rawQuery("""SELECT m.rowid,
                CASE WHEN length(m.commit_id) BETWEEN 1 AND 128 THEN m.commit_id END,
                CASE WHEN length(t.task_id) BETWEEN 1 AND 128 THEN t.task_id END,length(t.record)
                FROM save_media m JOIN save_tasks t ON t.commit_id=m.commit_id
                WHERE m.visible=1 AND t.state='SUCCEEDED' AND m.rowid>? ORDER BY m.rowid LIMIT 64""",
                arrayOf(after.toString())).use { c -> buildList {
                while (c.moveToNext()) {
                    require(!c.isNull(1) && !c.isNull(2) && c.getLong(3) in 8..SaveRecordCodec.MAX_BYTES.toLong())
                    add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
                }
            } }
            if (batch.isEmpty()) break
            for ((rowId, commitId, taskId) in batch) {
                val record = db.rawQuery("SELECT record,revision,idempotency_key FROM save_tasks WHERE task_id=? AND length(record) BETWEEN 8 AND 32768 AND length(idempotency_key) BETWEEN 1 AND 128", arrayOf(taskId)).use { c ->
                    require(c.moveToFirst()); LibraryPublicationEvidence.decode(c.getBlob(0), legacy = true).also {
                        require(it.task.revision == c.getLong(1) && it.request.idempotencyKey == c.getString(2))
                    }
                }
                require(record.commitId == commitId && record.task.taskId == taskId)
                requireVerifiedPublication(db, record, legacy = true)
                val receipt = requireNotNull(record.receipt)
                // Read-only identity check. Missing/wrong-size/symlinked bytes block and roll back
                // the upgrade, preserving v1 rather than manufacturing a playable READY item.
                java.io.FileInputStream(mediaFiles.readable(receipt.mediaId, receipt.sizeBytes)).use {
                    require(it.fd.valid())
                }
                publish(db, record, importedAtMillis, imported = true)
                after = rowId
            }
        }
    }

    /** An old codec's permissive compatibility decoder is not sufficient publication evidence. */
    fun requireVerifiedPublication(db: SQLiteDatabase, record: SaveRecord, legacy: Boolean = false) {
        LibraryPublicationEvidence.requireValid(record, legacy)
        val artifact = requireNotNull(record.artifact)
        val evidence = requireNotNull(record.verification)
        val receipt = requireNotNull(record.receipt)
        db.rawQuery("""SELECT o.task_id,o.artifact_id,o.media_id,o.state,length(o.evidence),
            m.artifact_id,m.media_id,m.size_bytes,m.sha256,m.visible
            FROM save_owned o JOIN save_media m ON m.commit_id=o.commit_id WHERE o.commit_id=?
            AND length(o.task_id) BETWEEN 1 AND 128 AND length(o.artifact_id) BETWEEN 1 AND 128
            AND length(o.media_id) BETWEEN 1 AND 128 AND length(o.state) BETWEEN 1 AND 32
            AND length(m.artifact_id) BETWEEN 1 AND 128 AND length(m.media_id) BETWEEN 1 AND 128 AND length(m.sha256)=64""", arrayOf(record.commitId)).use { c ->
            require(c.moveToFirst() && c.getString(0) == record.task.taskId && c.getString(1) == artifact.artifactId &&
                c.getString(2) == receipt.mediaId && c.getString(3) == "INDEXED" && !c.isNull(4) && c.getLong(4) in 1..4096)
            require(MediaCommitReceipt(record.commitId, c.getString(5), c.getString(6), c.getLong(7), c.getString(8)) == receipt)
            require(c.getInt(9) == 1)
        }
        db.rawQuery("SELECT evidence FROM save_owned WHERE commit_id=? AND length(evidence) BETWEEN 1 AND 4096", arrayOf(record.commitId)).use { c ->
            require(c.moveToFirst() && SaveRecordCodec.evidence(c.getBlob(0)) == evidence)
        }
    }

    /** Caller must also publish SUCCEEDED in this same SQL transaction. Files were already verified by Save. */
    fun publish(db: SQLiteDatabase, record: SaveRecord, atMillis: Long, imported: Boolean) {
        val receipt = requireNotNull(record.receipt)
        val evidence = requireNotNull(record.verification)
        // Historical Save receipts never recreate a moved/deleted representation.
        require(db.rawQuery("SELECT 1 FROM library_vault_move WHERE item_id=? AND phase<>'CANCELLED' LIMIT 1",
            arrayOf(receipt.mediaId)).use { !it.moveToFirst() })
        require(atMillis >= 0 && UUID.fromString(receipt.mediaId).toString() == receipt.mediaId)
        val representationId = UUID.nameUUIDFromBytes(("ordinary-file-v1:" + receipt.mediaId).toByteArray(Charsets.UTF_8)).toString()
        val name = publicationName(db, receipt.mediaId)
        db.insertOrThrow("library_item", null, ContentValues().apply {
            put("item_id", receipt.mediaId); put("origin_commit_id", record.commitId); put("revision", 1L)
            put("lifecycle", "READY"); put("privacy_class", "ORDINARY"); putNull("folder_id")
            put("representation_id", representationId); put("generation", 1L)
            put("display_name", name); put("name_key", LibraryNames.key(name)); put("created_at", atMillis)
            put("date_kind", if (imported) "IMPORTED" else "SAVED"); put("duration_millis", evidence.durationMillis)
            put("width", evidence.width); put("height", evidence.height)
        })
        db.insertOrThrow("media_representation", null, ContentValues().apply {
            put("representation_id", representationId); put("item_id", receipt.mediaId); put("generation", 1L)
            put("kind", "ORDINARY_FILE_V1"); put("opaque_locator", receipt.mediaId); put("state", "READY")
            put("size_bytes", receipt.sizeBytes); put("sha256", receipt.sha256)
        })
        bumpVersion(db)
    }

    private fun publicationName(db: SQLiteDatabase, mediaId: String): String {
        // Display names share the logical sibling namespace with folders. A user may have used
        // this generated-looking name already; choose another bounded local name, never a path.
        repeat(32) { attempt ->
            val name = if (attempt == 0) "Video $mediaId" else "Video ${UUID.randomUUID()}"
            val key = LibraryNames.key(name)
            val occupied = db.rawQuery("""SELECT 1 FROM library_folder WHERE parent_id IS NULL AND name_key=?
                UNION ALL SELECT 1 FROM library_item WHERE folder_id IS NULL AND name_key=? AND lifecycle='READY' LIMIT 1""",
                arrayOf(key, key)).use { it.moveToFirst() }
            if (!occupied) return name
        }
        throw IllegalArgumentException("LIBRARY_NAME_UNAVAILABLE")
    }

    fun bumpVersion(db: SQLiteDatabase) { db.execSQL("UPDATE library_state SET revision=revision+1 WHERE singleton=1") }
}
