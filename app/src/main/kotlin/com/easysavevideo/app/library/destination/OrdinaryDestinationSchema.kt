package com.easysavevideo.app.library.destination

import android.database.sqlite.SQLiteDatabase

/** Additive v5 -> v6. No historic Save/Edit/recording rows receive a destination admission. */
internal object OrdinaryDestinationSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE ordinary_destination_state(singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1),
            preference TEXT NOT NULL DEFAULT 'APP_LIBRARY' CHECK(preference IN ('APP_LIBRARY','ASK','ALBUM_COPY')),
            copy_cursor TEXT NOT NULL DEFAULT '' CHECK(length(copy_cursor)<=128))""")
        db.execSQL("INSERT INTO ordinary_destination_state(singleton) VALUES(1)")
        db.execSQL("""CREATE TABLE ordinary_destination(creation_id TEXT PRIMARY KEY NOT NULL CHECK(length(creation_id) BETWEEN 1 AND 128),
            choice TEXT NOT NULL CHECK(choice IN ('APP_LIBRARY','ALBUM_COPY')),
            operation_id TEXT UNIQUE NOT NULL CHECK(length(operation_id) BETWEEN 1 AND 128),
            grant_id TEXT UNIQUE NOT NULL CHECK(length(grant_id) BETWEEN 1 AND 128),
            source_item TEXT REFERENCES library_item(item_id),source_revision INTEGER,source_generation INTEGER,
            CHECK((source_item IS NULL AND source_revision IS NULL AND source_generation IS NULL) OR
                (source_item IS NOT NULL AND length(source_item) BETWEEN 1 AND 128 AND source_revision IS NOT NULL AND source_revision>0 AND source_generation IS NOT NULL AND source_generation>0)))""")
        db.execSQL("CREATE INDEX ordinary_destination_copy ON ordinary_destination(choice,creation_id)")
        db.execSQL("""CREATE TABLE ordinary_destination_publication(publication_id TEXT PRIMARY KEY NOT NULL CHECK(length(publication_id) BETWEEN 1 AND 128),
            creation_id TEXT NOT NULL REFERENCES ordinary_destination(creation_id))""")
        db.execSQL("CREATE INDEX ordinary_destination_publication_creation ON ordinary_destination_publication(creation_id)")
        db.execSQL("""CREATE TRIGGER ordinary_destination_immutable BEFORE UPDATE ON ordinary_destination
            WHEN NEW.creation_id IS NOT OLD.creation_id OR NEW.choice IS NOT OLD.choice OR NEW.operation_id IS NOT OLD.operation_id OR NEW.grant_id IS NOT OLD.grant_id
                OR (OLD.source_item IS NOT NULL AND (NEW.source_item IS NOT OLD.source_item OR NEW.source_revision IS NOT OLD.source_revision OR NEW.source_generation IS NOT OLD.source_generation))
            BEGIN SELECT RAISE(ABORT,'DESTINATION_IDENTITY_IMMUTABLE'); END""")
        db.execSQL("""CREATE TRIGGER ordinary_destination_publication_immutable BEFORE UPDATE ON ordinary_destination_publication
            WHEN NEW.publication_id IS NOT OLD.publication_id OR NEW.creation_id IS NOT OLD.creation_id
            BEGIN SELECT RAISE(ABORT,'DESTINATION_LINK_IMMUTABLE'); END""")
    }
}
