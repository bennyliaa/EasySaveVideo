-- Frozen independently from baseline a3a028d on 2026-10-07. Host synthetic fixture.
-- Empty v5 schema after the original library parent-table migration.
CREATE TABLE save_tasks(task_id TEXT PRIMARY KEY NOT NULL,idempotency_key TEXT UNIQUE NOT NULL,
                revision INTEGER NOT NULL,state TEXT NOT NULL,commit_id TEXT UNIQUE NOT NULL,record BLOB NOT NULL);

CREATE TABLE save_owned(commit_id TEXT PRIMARY KEY NOT NULL REFERENCES save_tasks(commit_id),
                task_id TEXT UNIQUE NOT NULL REFERENCES save_tasks(task_id),artifact_id TEXT UNIQUE NOT NULL,
                media_id TEXT UNIQUE NOT NULL,state TEXT NOT NULL,evidence BLOB);

CREATE TABLE save_media(media_id TEXT PRIMARY KEY NOT NULL,commit_id TEXT UNIQUE NOT NULL REFERENCES save_owned(commit_id),
                artifact_id TEXT UNIQUE NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),sha256 TEXT NOT NULL,
                visible INTEGER NOT NULL DEFAULT 0 CHECK(visible IN (0,1)));

CREATE TABLE library_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL CHECK(revision>=0),delete_cursor TEXT NOT NULL DEFAULT '' CHECK(length(delete_cursor)<=128));

INSERT INTO library_state(singleton,revision) VALUES(1,0);

CREATE TABLE library_folder(folder_id TEXT PRIMARY KEY NOT NULL CHECK(length(folder_id) BETWEEN 1 AND 128),parent_id TEXT CHECK(parent_id IS NULL OR length(parent_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),
            revision INTEGER NOT NULL CHECK(revision>0),display_name TEXT NOT NULL CHECK(length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),name_key TEXT NOT NULL CHECK(length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024));

CREATE UNIQUE INDEX library_folder_sibling ON library_folder(IFNULL(parent_id,''),name_key);

CREATE TABLE library_item(item_id TEXT PRIMARY KEY NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),origin_commit_id TEXT UNIQUE NOT NULL CHECK(length(origin_commit_id) BETWEEN 1 AND 128) REFERENCES save_tasks(commit_id),
            revision INTEGER NOT NULL CHECK(revision>0),lifecycle TEXT NOT NULL CHECK(lifecycle IN ('READY','DELETE_PENDING','DELETED')),
            privacy_class TEXT NOT NULL CHECK(privacy_class='ORDINARY'),folder_id TEXT CHECK(folder_id IS NULL OR length(folder_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),
            representation_id TEXT UNIQUE NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128) REFERENCES media_representation(representation_id) DEFERRABLE INITIALLY DEFERRED,
            generation INTEGER NOT NULL CHECK(generation>0),display_name TEXT CHECK(display_name IS NULL OR length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),
            name_key TEXT CHECK(name_key IS NULL OR length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024),created_at INTEGER CHECK(created_at IS NULL OR created_at>=0),
            date_kind TEXT CHECK(date_kind IN ('SAVED','IMPORTED')),duration_millis INTEGER CHECK(duration_millis IS NULL OR duration_millis>0),width INTEGER CHECK(width IS NULL OR width>0),height INTEGER CHECK(height IS NULL OR height>0),
            CHECK((width IS NULL)=(height IS NULL)),
            CHECK(lifecycle='DELETED' OR (display_name IS NOT NULL AND name_key IS NOT NULL AND created_at IS NOT NULL AND created_at>=0 AND date_kind IS NOT NULL)));

CREATE UNIQUE INDEX library_item_sibling ON library_item(IFNULL(folder_id,''),name_key) WHERE lifecycle='READY';

CREATE INDEX library_item_page ON library_item(folder_id,lifecycle,created_at,item_id);

CREATE TABLE media_representation(representation_id TEXT PRIMARY KEY NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128),item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128) REFERENCES library_item(item_id),
            generation INTEGER NOT NULL CHECK(generation>0),kind TEXT NOT NULL CHECK(kind='ORDINARY_FILE_V1'),
            opaque_locator TEXT UNIQUE CHECK(opaque_locator IS NULL OR length(opaque_locator)=36),state TEXT NOT NULL CHECK(state IN ('READY','DELETE_PENDING','DELETED')),
            size_bytes INTEGER,sha256 TEXT CHECK(sha256 IS NULL OR (length(sha256)=64 AND sha256 NOT GLOB '*[^a-f0-9]*')),UNIQUE(item_id,generation),
            CHECK(state='DELETED' OR (opaque_locator IS NOT NULL AND size_bytes IS NOT NULL AND sha256 IS NOT NULL AND size_bytes>0 AND length(sha256)=64)));

CREATE TABLE library_operation(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 32),target_id TEXT NOT NULL CHECK(length(target_id) BETWEEN 1 AND 128),
            expected_revision INTEGER NOT NULL,argument_fingerprint TEXT NOT NULL CHECK(length(argument_fingerprint)=64),
            result_revision INTEGER NOT NULL CHECK(result_revision>0),phase TEXT NOT NULL CHECK(phase IN ('DELETE_PENDING','COMPLETE')));

CREATE INDEX library_delete_recovery ON library_operation(phase,operation_id);

CREATE TABLE library_export(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^a-f0-9]*'),
            phase TEXT NOT NULL CHECK(phase IN ('PREPARED','CREATE_INTENT','COPYING','VERIFYING','PUBLISH_INTENT','SUCCEEDED','COPIED_UNVERIFIED','CLEANUP_PENDING','FAILED','CANCELLED','RECONCILIATION_REQUIRED')),
            cancelled INTEGER NOT NULL CHECK(cancelled IN (0,1)),record BLOB NOT NULL CHECK(length(record) BETWEEN 8 AND 4096));

CREATE INDEX library_export_recovery ON library_export(phase,operation_id);

CREATE TABLE library_export_target(operation_id TEXT PRIMARY KEY NOT NULL REFERENCES library_export(operation_id),
            kind TEXT NOT NULL CHECK(kind IN ('MEDIA_STORE','SAF')),grant_id TEXT NOT NULL CHECK(length(grant_id) BETWEEN 1 AND 128),
            nonce TEXT UNIQUE NOT NULL CHECK(length(nonce)=36),uri TEXT CHECK(uri IS NULL OR length(CAST(uri AS BLOB)) BETWEEN 1 AND 8192),
            grant_flags INTEGER NOT NULL DEFAULT 0 CHECK(grant_flags BETWEEN 0 AND 3));

CREATE TABLE library_export_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),recovery_cursor TEXT NOT NULL DEFAULT '' CHECK(length(recovery_cursor)<=128));

INSERT INTO library_export_state(singleton) VALUES(1);

CREATE TABLE recording_session(session_id TEXT PRIMARY KEY NOT NULL CHECK(length(session_id) BETWEEN 1 AND 128),
            generation INTEGER NOT NULL CHECK(generation>0),config BLOB NOT NULL CHECK(length(config) BETWEEN 8 AND 2048),
            state TEXT NOT NULL CHECK(state IN ('CAPTURING','STOPPED','PROCESSING','RETAINED','PUBLISHED','CANCEL_PENDING','CANCELLED','RECOVERY_REQUIRED')),
            pending_operation TEXT CHECK(pending_operation IS NULL OR length(pending_operation) BETWEEN 1 AND 128),
            published_operation TEXT REFERENCES edit_job(operation_id),created_at INTEGER NOT NULL CHECK(created_at>=0));

CREATE TABLE recording_segment(segment_id TEXT PRIMARY KEY NOT NULL CHECK(length(segment_id)=36),
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
            CHECK(state<>'VERIFIED' OR proof IS NOT NULL));

CREATE INDEX recording_session_recovery ON recording_session(state,session_id);

CREATE TABLE edit_job(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '*[^a-f0-9]*'),
            artifact_id TEXT UNIQUE NOT NULL CHECK(length(artifact_id)=36),media_id TEXT UNIQUE NOT NULL CHECK(length(media_id)=36),
            phase TEXT NOT NULL CHECK(phase IN ('PREPARED','EXECUTING','VERIFYING','PUBLISH_INTENT','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED','CLEANUP_PENDING')),
            cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN (0,1)),request BLOB NOT NULL CHECK(length(request) BETWEEN 8 AND 16384),
            expectation BLOB CHECK(expectation IS NULL OR length(expectation) BETWEEN 8 AND 1024),
            evidence BLOB CHECK(evidence IS NULL OR length(evidence) BETWEEN 8 AND 4096),
            created_at INTEGER NOT NULL CHECK(created_at>=0),error TEXT CHECK(error IS NULL OR length(error) BETWEEN 1 AND 64),
            CHECK(phase NOT IN ('PUBLISH_INTENT','SUCCEEDED') OR (expectation IS NOT NULL AND evidence IS NOT NULL AND cancelled=0)));

CREATE INDEX edit_job_recovery ON edit_job(phase,operation_id);

CREATE TABLE edit_source(operation_id TEXT NOT NULL REFERENCES edit_job(operation_id),sequence INTEGER NOT NULL CHECK(sequence BETWEEN 0 AND 32),
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
            PRIMARY KEY(operation_id,sequence));

CREATE TABLE library_item_v4(item_id TEXT PRIMARY KEY NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),
            origin_commit_id TEXT UNIQUE CHECK(origin_commit_id IS NULL OR length(origin_commit_id) BETWEEN 1 AND 128) REFERENCES save_tasks(commit_id),
            origin_edit_id TEXT UNIQUE CHECK(origin_edit_id IS NULL OR length(origin_edit_id) BETWEEN 1 AND 128) REFERENCES edit_job(operation_id),
            revision INTEGER NOT NULL CHECK(revision>0),lifecycle TEXT NOT NULL CHECK(lifecycle IN ('READY','DELETE_PENDING','DELETED')),
            privacy_class TEXT NOT NULL CHECK(privacy_class='ORDINARY'),folder_id TEXT CHECK(folder_id IS NULL OR length(folder_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),
            representation_id TEXT UNIQUE NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128) REFERENCES media_representation(representation_id) DEFERRABLE INITIALLY DEFERRED,
            generation INTEGER NOT NULL CHECK(generation>0),display_name TEXT CHECK(display_name IS NULL OR length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),
            name_key TEXT CHECK(name_key IS NULL OR length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024),created_at INTEGER CHECK(created_at IS NULL OR created_at>=0),
            date_kind TEXT CHECK(date_kind IN ('SAVED','IMPORTED')),duration_millis INTEGER CHECK(duration_millis IS NULL OR duration_millis>0),width INTEGER CHECK(width IS NULL OR width>0),height INTEGER CHECK(height IS NULL OR height>0),
            CHECK((width IS NULL)=(height IS NULL)),CHECK((origin_commit_id IS NULL)<>(origin_edit_id IS NULL)),
            CHECK(lifecycle='DELETED' OR (display_name IS NOT NULL AND name_key IS NOT NULL AND created_at IS NOT NULL AND created_at>=0 AND date_kind IS NOT NULL)));

INSERT INTO library_item_v4(item_id,origin_commit_id,revision,lifecycle,privacy_class,folder_id,representation_id,generation,display_name,name_key,created_at,date_kind,duration_millis,width,height)
            SELECT item_id,origin_commit_id,revision,lifecycle,privacy_class,folder_id,representation_id,generation,display_name,name_key,created_at,date_kind,duration_millis,width,height FROM library_item;

DROP TABLE library_item;

ALTER TABLE library_item_v4 RENAME TO library_item;

CREATE UNIQUE INDEX library_item_sibling ON library_item(IFNULL(folder_id,''),name_key) WHERE lifecycle='READY';

CREATE INDEX library_item_page ON library_item(folder_id,lifecycle,created_at,item_id);

PRAGMA user_version=5;
