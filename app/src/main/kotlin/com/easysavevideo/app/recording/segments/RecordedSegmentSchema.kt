package com.easysavevideo.app.recording.segments

import android.database.sqlite.SQLiteDatabase

/** Private capture ownership only. No temporary segment is an ordinary catalog representation. */
internal object RecordedSegmentSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE recording_session(session_id TEXT PRIMARY KEY NOT NULL CHECK(length(session_id) BETWEEN 1 AND 128),
            generation INTEGER NOT NULL CHECK(generation>0),config BLOB NOT NULL CHECK(length(config) BETWEEN 8 AND 2048),
            state TEXT NOT NULL CHECK(state IN ('CAPTURING','STOPPED','PROCESSING','RETAINED','PUBLISHED','CANCEL_PENDING','CANCELLED','RECOVERY_REQUIRED')),
            pending_operation TEXT CHECK(pending_operation IS NULL OR length(pending_operation) BETWEEN 1 AND 128),
            published_operation TEXT REFERENCES edit_job(operation_id),created_at INTEGER NOT NULL CHECK(created_at>=0))""")
        db.execSQL("""CREATE TABLE recording_segment(segment_id TEXT PRIMARY KEY NOT NULL CHECK(length(segment_id)=36),
            session_id TEXT NOT NULL REFERENCES recording_session(session_id),sequence INTEGER NOT NULL CHECK(sequence BETWEEN 0 AND 32),
            state TEXT NOT NULL CHECK(state IN ('ALLOCATED','CLOSED_UNVERIFIED','VERIFIED','CLEANUP_PENDING','DISCARDED')),
            file_identity TEXT CHECK(file_identity IS NULL OR length(file_identity) BETWEEN 1 AND 256),
            effective_start_us INTEGER NOT NULL CHECK(effective_start_us>=0),effective_end_us INTEGER CHECK(effective_end_us>effective_start_us),
            size_bytes INTEGER CHECK(size_bytes>0 AND size_bytes<=2147483648),sha256 TEXT CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^a-f0-9]*'),
            geometry BLOB CHECK(geometry IS NULL OR length(geometry) BETWEEN 8 AND 2048),
            timing_sha256 TEXT CHECK(timing_sha256 IS NULL OR (length(timing_sha256)=64 AND timing_sha256 NOT GLOB '*[^a-f0-9]*')),
            capture BLOB CHECK(capture IS NULL OR length(capture) BETWEEN 8 AND 2048),proof BLOB CHECK(proof IS NULL OR length(proof) BETWEEN 8 AND 4096),
            UNIQUE(session_id,sequence),CHECK(state NOT IN ('CLOSED_UNVERIFIED','VERIFIED') OR
                (file_identity IS NOT NULL AND effective_end_us IS NOT NULL AND size_bytes IS NOT NULL AND sha256 IS NOT NULL AND timing_sha256 IS NOT NULL AND geometry IS NOT NULL AND capture IS NOT NULL)),
            CHECK(state<>'VERIFIED' OR proof IS NOT NULL))""")
        db.execSQL("CREATE INDEX recording_session_recovery ON recording_session(state,session_id)")
    }
}
