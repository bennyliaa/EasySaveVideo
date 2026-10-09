package com.easysavevideo.app.library.export

import android.database.sqlite.SQLiteDatabase

/** Additive v2 -> v3, inside the same owner's SQLiteOpenHelper version transaction. */
internal object ExportSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE library_export(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^a-f0-9]*'),
            phase TEXT NOT NULL CHECK(phase IN ('PREPARED','CREATE_INTENT','COPYING','VERIFYING','PUBLISH_INTENT','SUCCEEDED','COPIED_UNVERIFIED','CLEANUP_PENDING','FAILED','CANCELLED','RECONCILIATION_REQUIRED')),
            cancelled INTEGER NOT NULL CHECK(cancelled IN (0,1)),record BLOB NOT NULL CHECK(length(record) BETWEEN 8 AND 4096))""")
        db.execSQL("CREATE INDEX library_export_recovery ON library_export(phase,operation_id)")
        db.execSQL("""CREATE TABLE library_export_target(operation_id TEXT PRIMARY KEY NOT NULL REFERENCES library_export(operation_id),
            kind TEXT NOT NULL CHECK(kind IN ('MEDIA_STORE','SAF')),grant_id TEXT NOT NULL CHECK(length(grant_id) BETWEEN 1 AND 128),
            nonce TEXT UNIQUE NOT NULL CHECK(length(nonce)=36),uri TEXT CHECK(uri IS NULL OR length(CAST(uri AS BLOB)) BETWEEN 1 AND 8192),
            grant_flags INTEGER NOT NULL DEFAULT 0 CHECK(grant_flags BETWEEN 0 AND 3))""")
        db.execSQL("""CREATE TABLE library_export_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),recovery_cursor TEXT NOT NULL DEFAULT '' CHECK(length(recovery_cursor)<=128))""")
        db.execSQL("INSERT INTO library_export_state(singleton) VALUES(1)")
    }
}
