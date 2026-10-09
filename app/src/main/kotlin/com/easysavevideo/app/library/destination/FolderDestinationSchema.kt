package com.easysavevideo.app.library.destination

import android.database.sqlite.SQLiteDatabase

/** v6 -> v7, run inside the owner's version transaction, including fresh-create v6 tables.
 * Rebuild both sides of the publication FK explicitly; foreign keys need not be disabled.
 * Historic source, export, publication, and admission identities are copied exactly.
 */
internal object FolderDestinationSchema {
    fun migrate(db: SQLiteDatabase) {
        check(db.inTransaction())
        db.execSQL("""CREATE TABLE ordinary_folder_grant(folder_grant_id TEXT PRIMARY KEY NOT NULL CHECK(length(folder_grant_id) BETWEEN 1 AND 128),
            tree_uri TEXT NOT NULL CHECK(typeof(tree_uri)='text' AND length(tree_uri) BETWEEN 11 AND 8192 AND length(CAST(tree_uri AS BLOB))<=8192 AND instr(tree_uri,char(0))=0),
            flags INTEGER NOT NULL CHECK(typeof(flags)='integer' AND flags IN (1,2,3)),
            retired INTEGER NOT NULL DEFAULT 0 CHECK(typeof(retired)='integer' AND retired IN (0,1)),
            invalidated INTEGER NOT NULL DEFAULT 0 CHECK(typeof(invalidated)='integer' AND invalidated IN (0,1)))""")
        db.execSQL("CREATE UNIQUE INDEX ordinary_folder_grant_active ON ordinary_folder_grant(tree_uri,flags) WHERE retired=0 AND invalidated=0")
        db.execSQL("""CREATE TRIGGER ordinary_folder_grant_limit BEFORE INSERT ON ordinary_folder_grant
            WHEN (SELECT COUNT(*) FROM ordinary_folder_grant)>=128
            BEGIN SELECT RAISE(ABORT,'FOLDER_GRANT_CAPACITY'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_folder_grant_immutable BEFORE UPDATE ON ordinary_folder_grant
            WHEN NEW.folder_grant_id IS NOT OLD.folder_grant_id OR NEW.tree_uri IS NOT OLD.tree_uri OR NEW.flags IS NOT OLD.flags OR NEW.retired<OLD.retired OR NEW.invalidated<OLD.invalidated
            BEGIN SELECT RAISE(ABORT,'FOLDER_GRANT_IDENTITY_IMMUTABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_folder_grant_retained BEFORE DELETE ON ordinary_folder_grant
            BEGIN SELECT RAISE(ABORT,'FOLDER_GRANT_RETAINED'); END""")
        db.execSQL("""CREATE TABLE ordinary_destination_state_v7(singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1),
            preference TEXT NOT NULL DEFAULT 'APP_LIBRARY' CHECK(preference IN ('APP_LIBRARY','ASK','ALBUM_COPY','FOLDER_COPY')),
            copy_cursor TEXT NOT NULL DEFAULT '' CHECK(length(copy_cursor)<=128),
            selected_folder_id TEXT REFERENCES ordinary_folder_grant(folder_grant_id),
            selection_revision INTEGER NOT NULL DEFAULT 0 CHECK(typeof(selection_revision)='integer' AND selection_revision>=0),
            CHECK(selected_folder_id IS NULL OR length(selected_folder_id) BETWEEN 1 AND 128),
            CHECK(preference!='FOLDER_COPY' OR selected_folder_id IS NOT NULL))""")
        db.execSQL("INSERT INTO ordinary_destination_state_v7(singleton,preference,copy_cursor) SELECT singleton,preference,copy_cursor FROM ordinary_destination_state")
        db.execSQL("""CREATE TABLE ordinary_destination_v7(creation_id TEXT PRIMARY KEY NOT NULL CHECK(length(creation_id) BETWEEN 1 AND 128),
            choice TEXT NOT NULL CHECK(choice IN ('APP_LIBRARY','ALBUM_COPY','FOLDER_COPY')),
            operation_id TEXT UNIQUE NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            grant_id TEXT UNIQUE NOT NULL CHECK(length(grant_id) BETWEEN 1 AND 128),
            source_item TEXT REFERENCES library_item(item_id),source_revision INTEGER,source_generation INTEGER,
            folder_grant_id TEXT REFERENCES ordinary_folder_grant(folder_grant_id),
            CHECK((choice='FOLDER_COPY' AND folder_grant_id IS NOT NULL AND length(folder_grant_id) BETWEEN 1 AND 128 AND folder_grant_id!=grant_id) OR
                (choice!='FOLDER_COPY' AND folder_grant_id IS NULL)),
            CHECK((source_item IS NULL AND source_revision IS NULL AND source_generation IS NULL) OR
                (source_item IS NOT NULL AND length(source_item) BETWEEN 1 AND 128 AND source_revision IS NOT NULL AND typeof(source_revision)='integer' AND source_revision>0 AND source_generation IS NOT NULL AND typeof(source_generation)='integer' AND source_generation>0)))""")
        db.execSQL("INSERT INTO ordinary_destination_v7(rowid,creation_id,choice,operation_id,grant_id,source_item,source_revision,source_generation) SELECT rowid,creation_id,choice,operation_id,grant_id,source_item,source_revision,source_generation FROM ordinary_destination")
        db.execSQL("""CREATE TABLE ordinary_destination_publication_v7(publication_id TEXT PRIMARY KEY NOT NULL CHECK(length(publication_id) BETWEEN 1 AND 128),
            creation_id TEXT NOT NULL REFERENCES ordinary_destination_v7(creation_id))""")
        db.execSQL("INSERT INTO ordinary_destination_publication_v7(rowid,publication_id,creation_id) SELECT rowid,publication_id,creation_id FROM ordinary_destination_publication")
        db.execSQL("DROP TABLE ordinary_destination_publication")
        db.execSQL("DROP TABLE ordinary_destination")
        db.execSQL("DROP TABLE ordinary_destination_state")
        db.execSQL("ALTER TABLE ordinary_destination_v7 RENAME TO ordinary_destination")
        db.execSQL("ALTER TABLE ordinary_destination_publication_v7 RENAME TO ordinary_destination_publication")
        db.execSQL("ALTER TABLE ordinary_destination_state_v7 RENAME TO ordinary_destination_state")
        db.execSQL("CREATE INDEX ordinary_destination_copy ON ordinary_destination(choice,creation_id)")
        db.execSQL("CREATE INDEX ordinary_destination_publication_creation ON ordinary_destination_publication(creation_id)")
        db.execSQL("""CREATE TRIGGER ordinary_destination_immutable BEFORE UPDATE ON ordinary_destination
            WHEN NEW.creation_id IS NOT OLD.creation_id OR NEW.choice IS NOT OLD.choice OR NEW.operation_id IS NOT OLD.operation_id OR NEW.grant_id IS NOT OLD.grant_id
                OR NEW.folder_grant_id IS NOT OLD.folder_grant_id
                OR (OLD.source_item IS NOT NULL AND (NEW.source_item IS NOT OLD.source_item OR NEW.source_revision IS NOT OLD.source_revision OR NEW.source_generation IS NOT OLD.source_generation))
            BEGIN SELECT RAISE(ABORT,'DESTINATION_IDENTITY_IMMUTABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_destination_publication_immutable BEFORE UPDATE ON ordinary_destination_publication
            WHEN NEW.publication_id IS NOT OLD.publication_id OR NEW.creation_id IS NOT OLD.creation_id
            BEGIN SELECT RAISE(ABORT,'DESTINATION_LINK_IMMUTABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_destination_folder_active BEFORE INSERT ON ordinary_destination
            WHEN NEW.folder_grant_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM ordinary_folder_grant WHERE folder_grant_id=NEW.folder_grant_id AND retired=0 AND invalidated=0)
            BEGIN SELECT RAISE(ABORT,'FOLDER_GRANT_UNAVAILABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_destination_selection_active_insert BEFORE INSERT ON ordinary_destination_state
            WHEN NEW.selected_folder_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM ordinary_folder_grant WHERE folder_grant_id=NEW.selected_folder_id AND retired=0 AND invalidated=0)
            BEGIN SELECT RAISE(ABORT,'FOLDER_GRANT_UNAVAILABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_destination_selection_active_update BEFORE UPDATE ON ordinary_destination_state
            WHEN NEW.selected_folder_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM ordinary_folder_grant WHERE folder_grant_id=NEW.selected_folder_id AND retired=0 AND invalidated=0)
            BEGIN SELECT RAISE(ABORT,'FOLDER_GRANT_UNAVAILABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_destination_selection_revision AFTER UPDATE OF preference,selected_folder_id ON ordinary_destination_state
            WHEN NEW.preference IS NOT OLD.preference OR NEW.selected_folder_id IS NOT OLD.selected_folder_id
            BEGIN
                SELECT CASE WHEN OLD.selection_revision>=9223372036854775807 THEN RAISE(ABORT,'DESTINATION_SELECTION_REVISION_EXHAUSTED') END;
                UPDATE ordinary_destination_state SET selection_revision=OLD.selection_revision+1 WHERE singleton=NEW.singleton;
            END""")
        db.execSQL("""CREATE TRIGGER ordinary_folder_grant_unavailable_selection AFTER UPDATE OF retired,invalidated ON ordinary_folder_grant
            WHEN NEW.retired=1 OR NEW.invalidated=1
            BEGIN UPDATE ordinary_destination_state SET preference='APP_LIBRARY',selected_folder_id=NULL WHERE selected_folder_id=NEW.folder_grant_id; END""")
    }
}
