package com.easysavevideo.app.editor.storage

import android.database.sqlite.SQLiteDatabase

/** Typed edit provenance is independent of network Save and recording/HLS proof. */
internal object EditSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE edit_job(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^a-f0-9]*'),
            artifact_id TEXT UNIQUE NOT NULL CHECK(length(artifact_id)=36),media_id TEXT UNIQUE NOT NULL CHECK(length(media_id)=36),
            phase TEXT NOT NULL CHECK(phase IN ('PREPARED','EXECUTING','VERIFYING','PUBLISH_INTENT','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED','CLEANUP_PENDING')),
            cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN (0,1)),request BLOB NOT NULL CHECK(length(request) BETWEEN 8 AND 16384),
            expectation BLOB CHECK(expectation IS NULL OR length(expectation) BETWEEN 8 AND 1024),
            evidence BLOB CHECK(evidence IS NULL OR length(evidence) BETWEEN 8 AND 4096),
            created_at INTEGER NOT NULL CHECK(created_at>=0),error TEXT CHECK(error IS NULL OR length(error) BETWEEN 1 AND 64),
            CHECK(phase NOT IN ('PUBLISH_INTENT','SUCCEEDED') OR (expectation IS NOT NULL AND evidence IS NOT NULL AND cancelled=0)))""")
        db.execSQL("CREATE INDEX edit_job_recovery ON edit_job(phase,operation_id)")
        createSources(db)
    }

    private fun createSources(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE edit_source(operation_id TEXT NOT NULL REFERENCES edit_job(operation_id),sequence INTEGER NOT NULL CHECK(sequence BETWEEN 0 AND 32),
            source_kind TEXT NOT NULL DEFAULT 'CATALOG' CHECK(source_kind IN ('CATALOG','RECORDED_SEGMENT')),
            item_id TEXT REFERENCES library_item(item_id),revision INTEGER CHECK(revision>0),generation INTEGER NOT NULL CHECK(generation>0),
            session_id TEXT,segment_id TEXT REFERENCES recording_segment(segment_id),segment_sequence INTEGER,
            start_us INTEGER NOT NULL CHECK(start_us>=0),end_us INTEGER NOT NULL CHECK(end_us>start_us),
            execution_start_us INTEGER,execution_end_us INTEGER,frame_count INTEGER,
            size_bytes INTEGER NOT NULL CHECK(size_bytes>0),sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK((execution_start_us IS NULL)=(execution_end_us IS NULL)),CHECK((execution_start_us IS NULL)=(frame_count IS NULL)),
            CHECK(execution_start_us IS NULL OR (execution_start_us>=start_us AND execution_end_us<=end_us AND execution_end_us>execution_start_us AND frame_count>0 AND frame_count<=108000)),
            CHECK((source_kind='CATALOG' AND item_id IS NOT NULL AND revision IS NOT NULL AND session_id IS NULL AND segment_id IS NULL AND segment_sequence IS NULL AND sequence<=7)
                OR (source_kind='RECORDED_SEGMENT' AND item_id IS NULL AND revision IS NULL AND session_id IS NOT NULL AND segment_id IS NOT NULL AND segment_sequence=sequence)) ,
            PRIMARY KEY(operation_id,sequence))""")
    }

    /** v4 ordinary source rows retain their exact identity; no destructive reset. */
    fun migrateRecordedSources(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE edit_source RENAME TO edit_source_v4")
        createSources(db)
        db.execSQL("""INSERT INTO edit_source(operation_id,sequence,item_id,revision,generation,start_us,end_us,
            execution_start_us,execution_end_us,frame_count,size_bytes,sha256)
            SELECT operation_id,sequence,item_id,revision,generation,start_us,end_us,
            execution_start_us,execution_end_us,frame_count,size_bytes,sha256 FROM edit_source_v4""")
        db.execSQL("DROP TABLE edit_source_v4")
        requireForeignKeysValid(db)
    }

    /**
     * v3 -> v4: preserves existing identities and source provenance. Existing databases disable FK
     * enforcement in onConfigure BEFORE SQLiteOpenHelper's transaction; the complete graph is
     * checked before commit and enforcement is restored/checked in onOpen. No deferred-counter
     * shortcut is used. Creating a new database has no rows and keeps FK enforcement enabled.
     */
    fun migrateLibrary(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE library_item_v4(item_id TEXT PRIMARY KEY NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),
            origin_commit_id TEXT UNIQUE CHECK(origin_commit_id IS NULL OR length(origin_commit_id) BETWEEN 1 AND 128) REFERENCES save_tasks(commit_id),
            origin_edit_id TEXT UNIQUE CHECK(origin_edit_id IS NULL OR length(origin_edit_id) BETWEEN 1 AND 128) REFERENCES edit_job(operation_id),
            revision INTEGER NOT NULL CHECK(revision>0),lifecycle TEXT NOT NULL CHECK(lifecycle IN ('READY','DELETE_PENDING','DELETED')),
            privacy_class TEXT NOT NULL CHECK(privacy_class='ORDINARY'),folder_id TEXT CHECK(folder_id IS NULL OR length(folder_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),
            representation_id TEXT UNIQUE NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128) REFERENCES media_representation(representation_id) DEFERRABLE INITIALLY DEFERRED,
            generation INTEGER NOT NULL CHECK(generation>0),display_name TEXT CHECK(display_name IS NULL OR length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),
            name_key TEXT CHECK(name_key IS NULL OR length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024),created_at INTEGER CHECK(created_at IS NULL OR created_at>=0),
            date_kind TEXT CHECK(date_kind IN ('SAVED','IMPORTED')),duration_millis INTEGER CHECK(duration_millis IS NULL OR duration_millis>0),width INTEGER CHECK(width IS NULL OR width>0),height INTEGER CHECK(height IS NULL OR height>0),
            CHECK((width IS NULL)=(height IS NULL)),CHECK((origin_commit_id IS NULL)<>(origin_edit_id IS NULL)),
            CHECK(lifecycle='DELETED' OR (display_name IS NOT NULL AND name_key IS NOT NULL AND created_at IS NOT NULL AND created_at>=0 AND date_kind IS NOT NULL)))""")
        db.execSQL("""INSERT INTO library_item_v4(item_id,origin_commit_id,revision,lifecycle,privacy_class,folder_id,representation_id,generation,display_name,name_key,created_at,date_kind,duration_millis,width,height)
            SELECT item_id,origin_commit_id,revision,lifecycle,privacy_class,folder_id,representation_id,generation,display_name,name_key,created_at,date_kind,duration_millis,width,height FROM library_item""")
        db.execSQL("DROP TABLE library_item")
        db.execSQL("ALTER TABLE library_item_v4 RENAME TO library_item")
        db.execSQL("CREATE UNIQUE INDEX library_item_sibling ON library_item(IFNULL(folder_id,''),name_key) WHERE lifecycle='READY'")
        db.execSQL("CREATE INDEX library_item_page ON library_item(folder_id,lifecycle,created_at,item_id)")
        requireForeignKeysValid(db)
    }
    fun requireForeignKeysValid(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "EDIT_MIGRATION_FOREIGN_KEY" } }
    }
}
