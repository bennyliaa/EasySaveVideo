package com.easysavevideo.app.save.storage

import android.database.sqlite.SQLiteDatabase

/** Additive database v9. No Save/library publication semantics or existing rows are rewritten. */
internal object NormalizationWorkspaceSchema {
    val statements = listOf(
        "CREATE UNIQUE INDEX save_owned_normalization_binding ON save_owned(task_id,artifact_id,commit_id)",
        """CREATE TABLE save_normalization_workspace(
            sequence INTEGER PRIMARY KEY AUTOINCREMENT,
            normalization_id TEXT UNIQUE NOT NULL CHECK(length(normalization_id)=36),
            task_id TEXT NOT NULL CHECK(length(task_id) BETWEEN 1 AND 128),
            artifact_id TEXT NOT NULL CHECK(length(artifact_id)=36),
            commit_id TEXT NOT NULL CHECK(length(commit_id) BETWEEN 1 AND 128),
            revision INTEGER NOT NULL CHECK(revision>0),
            lifecycle TEXT NOT NULL CHECK(lifecycle IN ('RAW_COMPLETE','WRITING','OUTPUT_READY','ADOPT_INTENT','ADOPTED','CLEANUP_PENDING','CLEANED','RECOVERY_REQUIRED')),
            disposition TEXT NOT NULL CHECK(disposition IN ('CONTINUE','DISCARD_ONLY')),
            raw_size INTEGER NOT NULL CHECK(raw_size BETWEEN 1 AND 2147483648),
            raw_sha TEXT NOT NULL CHECK(length(raw_sha)=64 AND raw_sha NOT GLOB '*[^a-f0-9]*'),
            source_sha TEXT NOT NULL CHECK(length(source_sha)=64 AND source_sha NOT GLOB '*[^a-f0-9]*'),
            output_size INTEGER CHECK(output_size BETWEEN 1 AND 2147483648),
            output_sha TEXT CHECK(output_sha IS NULL OR (length(output_sha)=64 AND output_sha NOT GLOB '*[^a-f0-9]*')),
            proof_sha TEXT CHECK(proof_sha IS NULL OR (length(proof_sha)=64 AND proof_sha NOT GLOB '*[^a-f0-9]*')),
            record BLOB NOT NULL CHECK(length(record) BETWEEN 8 AND 2048),
            FOREIGN KEY(task_id,artifact_id,commit_id) REFERENCES save_owned(task_id,artifact_id,commit_id),
            CHECK((output_size IS NULL)=(output_sha IS NULL) AND (output_sha IS NULL)=(proof_sha IS NULL)),
            CHECK(lifecycle NOT IN ('OUTPUT_READY','ADOPT_INTENT','ADOPTED') OR output_size IS NOT NULL),
            CHECK(lifecycle NOT IN ('RAW_COMPLETE','WRITING') OR output_size IS NULL))""",
        "CREATE UNIQUE INDEX save_normalization_live_artifact ON save_normalization_workspace(artifact_id) WHERE lifecycle!='CLEANED'",
        "CREATE UNIQUE INDEX save_normalization_live_commit ON save_normalization_workspace(commit_id) WHERE lifecycle!='CLEANED'",
        "CREATE INDEX save_normalization_pending ON save_normalization_workspace(sequence) WHERE lifecycle!='CLEANED'",
        """CREATE TRIGGER save_normalization_insert_guard BEFORE INSERT ON save_normalization_workspace
            WHEN NEW.revision!=1 OR NEW.lifecycle!='RAW_COMPLETE' OR NEW.disposition!='CONTINUE' OR
            NOT EXISTS(SELECT 1 FROM save_tasks t JOIN save_owned o ON o.commit_id=t.commit_id AND o.task_id=t.task_id
                WHERE t.task_id=NEW.task_id AND t.commit_id=NEW.commit_id AND o.artifact_id=NEW.artifact_id
                AND t.state NOT IN ('SUCCEEDED','FAILED','CANCELLED') AND o.state='STAGING')
            BEGIN SELECT RAISE(ABORT,'NORMALIZATION_CREATE_CONFLICT'); END""",
        """CREATE TRIGGER save_normalization_immutable BEFORE UPDATE ON save_normalization_workspace
            WHEN NEW.sequence IS NOT OLD.sequence OR NEW.normalization_id IS NOT OLD.normalization_id OR
            NEW.task_id IS NOT OLD.task_id OR NEW.artifact_id IS NOT OLD.artifact_id OR NEW.commit_id IS NOT OLD.commit_id OR
            NEW.revision!=OLD.revision+1 OR OLD.lifecycle='CLEANED' OR
            NEW.raw_size IS NOT OLD.raw_size OR NEW.raw_sha IS NOT OLD.raw_sha OR NEW.source_sha IS NOT OLD.source_sha OR
            (OLD.output_size IS NOT NULL AND (NEW.output_size IS NOT OLD.output_size OR NEW.output_sha IS NOT OLD.output_sha OR NEW.proof_sha IS NOT OLD.proof_sha)) OR
            (OLD.disposition='DISCARD_ONLY' AND NEW.disposition!='DISCARD_ONLY') OR
            (NEW.disposition='CONTINUE' AND EXISTS(SELECT 1 FROM save_tasks WHERE task_id=NEW.task_id AND state IN ('FAILED','CANCELLED'))) OR
            (NEW.disposition='DISCARD_ONLY' AND EXISTS(SELECT 1 FROM save_tasks WHERE task_id=NEW.task_id AND state='SUCCEEDED'))
            BEGIN SELECT RAISE(ABORT,'NORMALIZATION_IMMUTABLE'); END""",
        """CREATE TRIGGER save_normalization_retained BEFORE DELETE ON save_normalization_workspace
            BEGIN SELECT RAISE(ABORT,'NORMALIZATION_RETAINED'); END""",
    )
    fun create(db: SQLiteDatabase) { statements.forEach(db::execSQL) }
}
