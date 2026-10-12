package com.easysavevideo.app.library.export

import android.database.sqlite.SQLiteDatabase

/** Additive v7 -> v8. URI authority remains in the existing Android folder registry. */
internal object DirectoryBatchSchema {
    val statements=listOf(
        """CREATE TABLE library_directory_batch(batch_id TEXT PRIMARY KEY NOT NULL CHECK(length(batch_id) BETWEEN 1 AND 128),
            folder_grant_id TEXT NOT NULL REFERENCES ordinary_folder_grant(folder_grant_id),
            phase TEXT NOT NULL CHECK(phase IN ('ADMITTED','STOPPED','FINISHED')))""",
        """CREATE TABLE library_directory_item(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            batch_id TEXT NOT NULL REFERENCES library_directory_batch(batch_id),ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 99),
            item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 AND 128),revision INTEGER NOT NULL CHECK(revision>0),
            generation INTEGER NOT NULL CHECK(generation>0),grant_id TEXT UNIQUE NOT NULL CHECK(length(grant_id) BETWEEN 1 AND 128),
            started INTEGER NOT NULL DEFAULT 0 CHECK(started IN (0,1)),outcome TEXT CHECK(outcome IS NULL OR length(outcome) BETWEEN 1 AND 32),
            UNIQUE(batch_id,ordinal),UNIQUE(batch_id,item_id))""",
        "CREATE INDEX library_directory_phase ON library_directory_batch(phase,batch_id)",
        """CREATE TRIGGER library_directory_batch_active_grant BEFORE INSERT ON library_directory_batch
            WHEN NOT EXISTS(SELECT 1 FROM ordinary_folder_grant WHERE folder_grant_id=NEW.folder_grant_id AND retired=0 AND invalidated=0)
            BEGIN SELECT RAISE(ABORT,'DIRECTORY_GRANT_UNAVAILABLE'); END""",
        """CREATE TRIGGER library_directory_batch_immutable BEFORE UPDATE ON library_directory_batch
            WHEN NEW.batch_id IS NOT OLD.batch_id OR NEW.folder_grant_id IS NOT OLD.folder_grant_id OR
            (OLD.phase!='ADMITTED' AND NEW.phase='ADMITTED') OR (OLD.phase='STOPPED' AND NEW.phase!='STOPPED')
            BEGIN SELECT RAISE(ABORT,'DIRECTORY_BATCH_IMMUTABLE'); END""",
        """CREATE TRIGGER library_directory_item_immutable BEFORE UPDATE ON library_directory_item
            WHEN NEW.operation_id IS NOT OLD.operation_id OR NEW.batch_id IS NOT OLD.batch_id OR NEW.ordinal IS NOT OLD.ordinal OR
            NEW.item_id IS NOT OLD.item_id OR NEW.revision IS NOT OLD.revision OR NEW.generation IS NOT OLD.generation OR
            NEW.grant_id IS NOT OLD.grant_id OR NEW.started<OLD.started OR (OLD.outcome IS NOT NULL AND NEW.outcome IS NOT OLD.outcome)
            BEGIN SELECT RAISE(ABORT,'DIRECTORY_ITEM_IMMUTABLE'); END""",
        """CREATE TRIGGER library_directory_item_start_guard BEFORE UPDATE OF started ON library_directory_item
            WHEN NEW.started>OLD.started AND (OLD.outcome IS NOT NULL OR NOT EXISTS(SELECT 1 FROM library_directory_batch WHERE batch_id=OLD.batch_id AND phase='ADMITTED'))
            BEGIN SELECT RAISE(ABORT,'DIRECTORY_BATCH_STOPPED'); END""",
        """CREATE TRIGGER library_directory_batch_retained BEFORE DELETE ON library_directory_batch
            BEGIN SELECT RAISE(ABORT,'DIRECTORY_BATCH_RETAINED'); END""",
        """CREATE TRIGGER library_directory_item_retained BEFORE DELETE ON library_directory_item
            BEGIN SELECT RAISE(ABORT,'DIRECTORY_ITEM_RETAINED'); END""",
    )
    fun create(db:SQLiteDatabase) { statements.forEach(db::execSQL) }
}
